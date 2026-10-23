package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteDiscordRules;
import com.younglings.bot.clansite.SiteDiscordRules.Refusal;
import com.younglings.bot.clansite.SiteDiscordSettings;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.member.MemberProfileRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Discord on a clan website (builder v3, T-365): the "who's online" and channel feed answers for the public site, and the settings the
 * dashboard edits. Both are off until an admin opts in: the online list by picking a mode, the feed channel by channel. A channel that
 * {@code @everyone} can't read, an age-restricted channel and a thread are refused when chosen and skipped again on every read, so a
 * channel made private later drops off the site by itself. Answers are cached per server in {@link SiteCache} (online 10 s, feed 15 s; the
 * messages themselves 60 s in {@link SiteNewsService}), so a deleted message is gone within a minute.
 */
@BService
public class SiteDiscordService {
    private static final Logger log = LoggerFactory.getLogger(SiteDiscordService.class);
    static final String CACHE_PREFIX = "discord-";
    private static final long ONLINE_TTL = 10_000;
    private static final long FEED_TTL = 15_000;
    private static final int MAX_CHANNELS = 6;

    private final SiteDiscordSettings settings;
    private final SiteNewsService news;
    private final MemberProfileRepository profiles;
    private final SiteCache cache;

    public SiteDiscordService(SiteDiscordSettings settings, SiteNewsService news, MemberProfileRepository profiles, SiteCache cache) {
        this.settings = settings;
        this.news = news;
        this.profiles = profiles;
        this.cache = cache;
    }

    // ---------- the public site ----------

    /** The online answer, or null while the admin hasn't turned it on (the route says "disabled"). */
    public DataObject online(Guild guild) {
        OnlineMode mode = settings.onlineMode(guild.getIdLong());
        if (mode == OnlineMode.OFF) return null;
        if (!PrivilegedIntents.presence()) return SiteDiscordRules.onlineUnavailable(mode);
        return cache.get(CACHE_PREFIX + "online#" + mode.key() + "#g" + guild.getIdLong(), ONLINE_TTL, () -> {
            // Members who chose not to be named here, and those who asked not to be tied to the online list on the home site (hide_discord_link).
            Set<Long> hidden = new HashSet<>(settings.hiddenMembers(guild.getIdLong()));
            hidden.addAll(profiles.hiddenDiscordUsers(guild.getIdLong()));
            List<SiteDiscordRules.Person> people = guild.getMembers().stream().map(SiteDiscordRules::person).toList();
            return SiteDiscordRules.online(people, mode, hidden);
        });
    }

    /**
     * The newest posts from the site's public channels, or from one of them ({@code channelId}), newest first. Null when that channel isn't
     * one the admin opted in (or no longer passes the rules). Posts carry the author's name but never their avatar (its address holds their
     * Discord id); the site decides whether to show names.
     */
    public DataObject feed(Guild guild, String channelId, int limit) {
        List<SiteNewsService.NewsChannel> open = publicChannels(guild);
        List<SiteNewsService.NewsChannel> chosen = open;
        if (channelId != null && !channelId.isBlank()) {
            chosen = open.stream().filter(c -> Long.toString(c.channelId()).equals(channelId.strip())).toList();
            if (chosen.isEmpty()) return null;
        }
        int capped = Math.max(1, Math.min(SiteDiscordRules.MAX_POSTS, limit));
        DataArray channels = DataArray.empty();
        for (var c : open) channels.add(DataObject.empty().put("id", Long.toString(c.channelId())).put("name", label(guild, c)));
        if (!PrivilegedIntents.messageContent()) {
            return DataObject.empty().put("unavailable", true).put("channels", channels).put("posts", DataArray.empty());
        }
        List<SiteNewsService.NewsChannel> finalChosen = chosen;
        String key = CACHE_PREFIX + "feed?" + (channelId == null ? "" : channelId.strip()) + "&" + capped + "#g" + guild.getIdLong();
        DataArray posts = cache.get(key, FEED_TTL, () -> {
            DataArray out = DataArray.empty();
            DataArray latest = news.latest(guild, finalChosen, capped);
            for (int i = 0; i < latest.length(); i++) {
                DataObject post = DataObject.fromJson(latest.getObject(i).toJson()); // a copy: the home site's news shares these objects
                post.remove("avatarUrl");
                out.add(post);
            }
            return out;
        });
        return DataObject.empty().put("unavailable", false).put("channels", channels).put("posts", posts);
    }

    /** The opted-in channels that still pass the rules right now, in the admin's order. */
    List<SiteNewsService.NewsChannel> publicChannels(Guild guild) {
        return news.channels(guild.getIdLong()).stream()
                .filter(c -> SiteDiscordRules.channelRefusal(guild, guild.getGuildChannelById(c.channelId())) == null)
                .toList();
    }

    private static String label(Guild guild, SiteNewsService.NewsChannel c) {
        if (c.label() != null && !c.label().isBlank()) return c.label();
        GuildChannel channel = guild.getGuildChannelById(c.channelId());
        return channel == null ? null : channel.getName();
    }

    /** A member's own choice (from the website's signed-in session): whether they're left out of the online names. */
    public DataObject memberChoice(Guild guild, long userId) {
        return DataObject.empty().put("hidden", settings.isHidden(guild.getIdLong(), userId));
    }

    public DataObject saveMemberChoice(Guild guild, long userId, boolean hidden) {
        settings.setHidden(guild.getIdLong(), userId, hidden);
        cache.invalidate(CACHE_PREFIX + "online");
        return memberChoice(guild, userId);
    }

    // ---------- the dashboard ----------

    /** The settings page: the online mode, whether Discord lets the bot see presence and message text, and every channel with its verdict. */
    public DataObject adminState(Guild guild) {
        Set<Long> chosenIds = new HashSet<>();
        DataArray chosen = DataArray.empty();
        for (var c : news.channels(guild.getIdLong())) {
            chosenIds.add(c.channelId());
            chosen.add(channelJson(guild, guild.getGuildChannelById(c.channelId()), c.channelId(), c.label()));
        }
        List<StandardGuildMessageChannel> all = new ArrayList<>();
        all.addAll(guild.getTextChannels());
        all.addAll(guild.getNewsChannels());
        all.sort(Comparator.comparingInt(StandardGuildMessageChannel::getPositionRaw));
        DataArray available = DataArray.empty();
        for (var channel : all) {
            if (!chosenIds.contains(channel.getIdLong())) available.add(channelJson(guild, channel, channel.getIdLong(), null));
        }
        return DataObject.empty()
                .put("onlineMode", settings.onlineMode(guild.getIdLong()).key())
                .put("presenceAvailable", PrivilegedIntents.presence())
                .put("messageContentAvailable", PrivilegedIntents.messageContent())
                .put("maxChannels", MAX_CHANNELS)
                .put("chosen", chosen)
                .put("channels", available);
    }

    private static DataObject channelJson(Guild guild, GuildChannel channel, long id, String label) {
        Refusal refusal = SiteDiscordRules.channelRefusal(guild, channel);
        return DataObject.empty().put("channelId", Long.toString(id)).put("name", channel == null ? null : channel.getName()).put("label", label)
                .put("eligible", refusal == null).put("reason", refusal == null ? null : refusal.reason());
    }

    /**
     * Saves what the body names ({@code onlineMode}, {@code channels: [{channelId, label}]}); a field left out is kept. A channel the rules
     * forbid (private, age-restricted, a thread) is a 403 with the reason; one the bot can't read yet is a 400.
     */
    public DataObject save(Guild guild, Member actor, DataObject body) {
        OnlineMode mode = null;
        if (body.hasKey("onlineMode") && !body.isNull("onlineMode")) {
            mode = OnlineMode.parse(body.getString("onlineMode", "")).orElseThrow(() -> new ApiError(400, "Pick off, count, names or names and avatars."));
        }
        List<SiteNewsService.NewsChannel> channels = null;
        if (body.hasKey("channels") && !body.isNull("channels")) channels = parseChannels(guild, body.getArray("channels"));

        if (mode != null) settings.saveOnlineMode(guild.getIdLong(), mode, actor.getIdLong());
        if (channels != null) news.replaceChannels(guild.getIdLong(), channels);
        cache.invalidate(CACHE_PREFIX);
        log.info("Dashboard: {} saved Discord on the website (online={}, channels={})", actor.getId(), mode == null ? "kept" : mode.key(),
                channels == null ? "kept" : channels.size());
        return adminState(guild);
    }

    private static List<SiteNewsService.NewsChannel> parseChannels(Guild guild, DataArray input) {
        if (input.length() > MAX_CHANNELS) throw new ApiError(400, "Pick at most " + MAX_CHANNELS + " channels.");
        List<SiteNewsService.NewsChannel> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            String raw = item.getString("channelId", "").strip();
            if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "A chosen channel isn't valid.");
            long channelId = Long.parseLong(raw);
            if (!seen.add(channelId)) continue;
            Refusal refusal = SiteDiscordRules.channelRefusal(guild, guild.getGuildChannelById(channelId));
            if (refusal != null) throw new ApiError(refusal.forbidden() ? 403 : 400, refusal.reason());
            String label = item.getString("label", "").strip();
            if (label.length() > 40) throw new ApiError(400, "Labels are up to 40 characters.");
            chosen.add(new SiteNewsService.NewsChannel(channelId, label.isEmpty() ? null : label, chosen.size()));
        }
        return chosen;
    }
}
