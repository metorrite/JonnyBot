package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteConnectionStore;
import com.younglings.bot.clansite.SiteConnectionStore.Channel;
import com.younglings.bot.clansite.SiteConnectionStore.Connection;
import com.younglings.bot.clansite.SiteDiscordRules;
import com.younglings.bot.clansite.SiteDiscordRules.Refusal;
import com.younglings.bot.clansite.SiteDiscordSettings;
import com.younglings.bot.clansite.SiteDiscordSettings.OnlineMode;
import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.hubsites.HubSites;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.member.MemberProfileRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
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
 * Discord on a clan website (builder v3, T-365): the "who's online" and channel feed answers for the public site, read from the site's
 * connections ({@link SiteConnectionStore}, N7), and the site admin's Connections page that edits them. RuneClan Bot is the provider here:
 * the website asks, the bot answers with what the site's settings allow. Both are off until an admin opts in: the online list by picking a
 * mode, the feed channel by channel; a disconnected server shows nothing at all. An age-restricted channel and a thread are refused
 * when chosen and skipped again on every read. A channel {@code @everyone} can't read is shown only while the admin's yes for it is stored
 * ({@code allowPrivate} on save, kept by {@link SiteConnectionStore#privateOk}); one that turns private after it was chosen drops off the site
 * until the admin says yes. The Connections page marks both. Answers are cached per server in {@link SiteCache} (online 10 s, feed 15 s; the
 * messages themselves 60 s in {@link SiteNewsService}), so a deleted message is gone within a minute.
 */
@BService
public class SiteDiscordService {
    private static final Logger log = LoggerFactory.getLogger(SiteDiscordService.class);
    static final String CACHE_PREFIX = "discord-";
    private static final long ONLINE_TTL = 10_000;
    private static final long FEED_TTL = 15_000;
    private static final int MAX_CHANNELS = 6;

    /** What RuneClan Bot can supply right now (capability names as the hub's switchboard will declare them; the last three are new there). */
    static final String PRESENCE = "discord.presence";
    static final String CHANNELS = "discord.channels.report";
    static final String MESSAGES = "discord.messages.report";
    static final String EVENTS = "discord.events.report";

    private final SiteConnectionStore connections;
    private final SiteDiscordSettings settings;
    private final SiteNewsService news;
    private final MemberProfileRepository profiles;
    private final SiteCache cache;
    private final HubSites hubSites;

    public SiteDiscordService(SiteConnectionStore connections, SiteDiscordSettings settings, SiteNewsService news, MemberProfileRepository profiles,
                              SiteCache cache, HubSites hubSites) {
        this.connections = connections;
        this.settings = settings;
        this.news = news;
        this.profiles = profiles;
        this.cache = cache;
        this.hubSites = hubSites;
    }

    /**
     * The site's connection as the public site's answers follow it: with {@code SITES_BACKEND=hub}, the hub's settings (T-545: online list,
     * feed limit, events) in place of the bot's copy; the server, provider and channel picks stay the bot's. The bot's copy when the hub has
     * no answer.
     */
    Connection publicConnection(long guildId) {
        Connection own = connections.of(guildId);
        return hubSites.settings(guildId)
                .map(s -> own.withFeatures(OnlineMode.parse(s.onlineMode()).orElse(own.onlineMode()), s.onlineLimit(), s.groupByRole(), s.feedLimit(),
                        s.eventsEnabled(), own.channels()))
                .orElse(own);
    }

    // ---------- the public site ----------

    /** The online answer, or null while the admin hasn't turned it on or the server is disconnected (the route says "disabled"). */
    public DataObject online(Guild guild) {
        Connection connection = publicConnection(guild.getIdLong());
        OnlineMode mode = connection.onlineMode();
        if (mode == OnlineMode.OFF || !connection.connected()) return null;
        if (!PrivilegedIntents.presence()) return SiteDiscordRules.onlineUnavailable(mode, connection.groupByRole());
        String key = CACHE_PREFIX + "online#" + mode.key() + "#" + connection.onlineLimit() + "#" + connection.groupByRole() + "#g" + guild.getIdLong();
        return cache.get(key, ONLINE_TTL, () -> {
            // Members who chose not to be named here, and those who asked not to be tied to the online list on the home site (hide_discord_link).
            Set<Long> hidden = new HashSet<>(settings.hiddenMembers(guild.getIdLong()));
            hidden.addAll(profiles.hiddenDiscordUsers(guild.getIdLong()));
            List<SiteDiscordRules.Person> people = guild.getMembers().stream().map(SiteDiscordRules::person).toList();
            return SiteDiscordRules.online(people, mode, hidden, connection.onlineLimit(), connection.groupByRole());
        });
    }

    /** Members who asked not to be named in this server's online lists (the home site's too); they are still counted. */
    public Set<Long> hiddenFromOnline(long guildId) {
        return settings.hiddenMembers(guildId);
    }

    /**
     * The newest posts from the site's public channels, or from one of them ({@code channelId}), newest first. Null when that channel isn't
     * one the admin opted in (or no longer passes the rules). Posts carry the author's server display name and avatar
     * ({@link SiteDiscordRules#feedPost}); the site decides whether to show them. How many is the site's feed setting (N7), never what the asker sends.
     */
    public DataObject feed(Guild guild, String channelId) {
        Connection connection = publicConnection(guild.getIdLong());
        int capped = Math.max(1, Math.min(SiteDiscordRules.MAX_POSTS, connection.feedLimit()));
        List<SiteNewsService.NewsChannel> open = connection.connected() ? publicChannels(guild, connection) : List.of();
        List<SiteNewsService.NewsChannel> chosen = open;
        if (channelId != null && !channelId.isBlank()) {
            chosen = open.stream().filter(c -> Long.toString(c.channelId()).equals(channelId.strip())).toList();
            if (chosen.isEmpty()) return null;
        }
        if (open.isEmpty()) {
            return DataObject.empty().put("unavailable", false).put("limit", capped).put("channels", DataArray.empty()).put("posts", DataArray.empty());
        }
        DataArray channels = DataArray.empty();
        for (var c : open) channels.add(DataObject.empty().put("id", Long.toString(c.channelId())).put("name", label(guild, c)));
        if (!PrivilegedIntents.messageContent()) {
            return DataObject.empty().put("unavailable", true).put("limit", capped).put("channels", channels).put("posts", DataArray.empty());
        }
        List<SiteNewsService.NewsChannel> finalChosen = chosen;
        String key = CACHE_PREFIX + "feed?" + (channelId == null ? "" : channelId.strip()) + "&" + capped + "#g" + guild.getIdLong();
        DataArray posts = cache.get(key, FEED_TTL, () -> {
            DataArray out = DataArray.empty();
            DataArray latest = news.latest(guild, finalChosen, capped);
            for (int i = 0; i < latest.length(); i++) out.add(SiteDiscordRules.feedPost(latest.getObject(i)));
            return out;
        });
        return DataObject.empty().put("unavailable", false).put("limit", capped).put("channels", channels).put("posts", posts);
    }

    /**
     * The opted-in channels that still pass the rules right now, in the admin's order (as the news reader takes them): a private one only with
     * the admin's stored yes (read only when a chosen channel is private).
     */
    List<SiteNewsService.NewsChannel> publicChannels(Guild guild, Connection connection) {
        Set<Long> privateOk = null;
        List<SiteNewsService.NewsChannel> out = new ArrayList<>();
        for (Channel c : connection.channels()) {
            GuildChannel channel = guild.getGuildChannelById(c.channelId());
            if (SiteDiscordRules.channelRefusal(guild, channel) != null) continue;
            if (SiteDiscordRules.isPrivate(guild, channel)) {
                if (privateOk == null) privateOk = connections.privateOk(guild.getIdLong());
                if (!privateOk.contains(c.channelId())) continue;
            }
            out.add(new SiteNewsService.NewsChannel(c.channelId(), c.label(), c.position()));
        }
        return List.copyOf(out);
    }

    /** Whether the site shows the server's Discord events (the events widgets and the bell): connected and switched on. */
    public boolean eventsShown(long siteId) {
        Connection connection = publicConnection(siteId);
        return connection.connected() && connection.eventsEnabled();
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

    // ---------- the site admin's Connections page (N7) ----------

    /**
     * {@code GET clan-site/connections}: the connected server and whether the bot is in it, the provider and what it can supply now, and
     * the Discord features with every channel and its verdict. A bot-less site ({@code guild} null) has no channels to offer.
     */
    DataObject connectionState(SiteCaller c) {
        Connection connection = connections.of(c.guildId());
        Guild guild = c.guild();
        boolean presence = guild != null && PrivilegedIntents.presence();
        boolean messages = guild != null && PrivilegedIntents.messageContent();
        DataArray capabilities = DataArray.empty();
        if (guild != null) {
            if (presence) capabilities.add(PRESENCE);
            capabilities.add(CHANNELS);
            if (messages) capabilities.add(MESSAGES);
            capabilities.add(EVENTS);
        }

        Set<Long> chosenIds = new HashSet<>();
        Set<Long> privateOk = guild == null || connection.channels().isEmpty() ? Set.of() : connections.privateOk(c.guildId());
        DataArray chosen = DataArray.empty();
        for (Channel ch : connection.channels()) {
            chosenIds.add(ch.channelId());
            chosen.add(guild == null ? DataObject.empty().put("channelId", Long.toString(ch.channelId())).put("name", null).put("label", ch.label())
                    .put("eligible", false).put("reason", "RuneClan Bot isn't in the server, so this channel can't be read.")
                    : channelJson(guild, guild.getGuildChannelById(ch.channelId()), ch.channelId(), ch.label(), privateOk.contains(ch.channelId())));
        }
        DataArray available = DataArray.empty();
        if (guild != null) {
            List<StandardGuildMessageChannel> all = new ArrayList<>();
            all.addAll(guild.getTextChannels());
            all.addAll(guild.getNewsChannels());
            all.sort(Comparator.comparingInt(StandardGuildMessageChannel::getPositionRaw));
            for (var channel : all) {
                if (!chosenIds.contains(channel.getIdLong())) available.add(channelJson(guild, channel, channel.getIdLong(), null, false));
            }
        }

        return DataObject.empty()
                .put("server", DataObject.empty()
                        .put("guildId", Long.toString(c.guildId()))
                        .put("name", guild == null ? null : guild.getName())
                        .put("iconUrl", guild == null ? null : guild.getIconUrl())
                        .put("connected", connection.connected())
                        .put("connectedBy", connection.connectedBy() == null ? null : Long.toString(connection.connectedBy()))
                        .put("connectedAt", connection.connectedAt() == null ? null : connection.connectedAt().toString())
                        .put("botPresent", guild != null))
                .put("provider", DataObject.empty()
                        .put("id", connection.providerId())
                        .put("ready", guild != null && connection.connected())
                        .put("capabilities", capabilities))
                .put("presenceAvailable", presence)
                .put("messageContentAvailable", messages)
                .put("online", DataObject.empty().put("mode", connection.onlineMode().key()).put("limit", connection.onlineLimit())
                        .put("groupByRole", connection.groupByRole()).put("maxLimit", SiteConnectionStore.MAX_ONLINE_LIMIT))
                .put("feed", DataObject.empty().put("limit", connection.feedLimit()).put("maxLimit", SiteConnectionStore.MAX_FEED_LIMIT)
                        .put("maxChannels", MAX_CHANNELS).put("chosen", chosen).put("channels", available))
                .put("events", DataObject.empty().put("enabled", connection.eventsEnabled()))
                .put("copiedFromBot", connection.copiedFromBot());
    }

    /** One channel's verdict; {@code privateOk} says whether the admin's yes to show it although it's private is stored. */
    private static DataObject channelJson(Guild guild, GuildChannel channel, long id, String label, boolean privateOk) {
        Refusal refusal = SiteDiscordRules.channelRefusal(guild, channel);
        boolean isPrivate = SiteDiscordRules.isPrivate(guild, channel);
        return DataObject.empty().put("channelId", Long.toString(id)).put("name", channel == null ? null : channel.getName()).put("label", label)
                .put("eligible", refusal == null).put("reason", refusal == null ? null : refusal.reason())
                .put("private", isPrivate).put("privateOk", isPrivate && privateOk);
    }

    /**
     * {@code PUT clan-site/connections}: saves what the body names ({@code online: {mode, limit, groupByRole}}, {@code feed: {limit,
     * channels: [{channelId, label, allowPrivate}]}}, {@code events: {enabled}}); a part left out is kept. A channel the rules forbid
     * (age-restricted, a thread) is a 403 with the reason; one the bot can't read yet is a 400. A newly chosen private channel ({@code @everyone}
     * can't read it) is a 403 unless the admin sends {@code allowPrivate: true}, which stores their yes. One already chosen is kept either way,
     * but stays off the site while it has no yes (it turned private later), so saving other settings never fails on it. Channels need the bot
     * in the server.
     */
    DataObject saveConnection(SiteCaller c, DataObject body) {
        Connection now = connections.of(c.guildId());
        OnlineMode mode = now.onlineMode();
        int onlineLimit = now.onlineLimit();
        boolean groupByRole = now.groupByRole();
        if (body.hasKey("online") && !body.isNull("online")) {
            DataObject online = body.getObject("online");
            if (online.hasKey("mode")) {
                mode = OnlineMode.parse(online.getString("mode", "")).orElseThrow(() -> new ApiError(400, "Pick off, count, names or names and avatars."));
            }
            onlineLimit = number(online, "limit", onlineLimit, SiteConnectionStore.MAX_ONLINE_LIMIT, "The online list shows 1 to " + SiteConnectionStore.MAX_ONLINE_LIMIT + " names.");
            groupByRole = online.getBoolean("groupByRole", groupByRole);
        }
        int feedLimit = now.feedLimit();
        List<Channel> channels = now.channels();
        Set<Long> privateOk = null; // null keeps the stored yeses of the channels still chosen
        if (body.hasKey("feed") && !body.isNull("feed")) {
            DataObject feed = body.getObject("feed");
            feedLimit = number(feed, "limit", feedLimit, SiteConnectionStore.MAX_FEED_LIMIT, "A feed shows 1 to " + SiteConnectionStore.MAX_FEED_LIMIT + " posts.");
            if (feed.hasKey("channels") && !feed.isNull("channels")) {
                DataArray input = feed.getArray("channels");
                if (c.botless() && input.length() > 0) throw new ApiError(409, BotlessAdminApi.NEEDS_BOT);
                if (c.botless()) {
                    channels = List.of();
                } else {
                    Picked picked = parseChannels(c.guild(), input, now.channels(), connections.privateOk(c.guildId()));
                    channels = picked.channels();
                    privateOk = picked.privateOk();
                }
            }
        }
        boolean events = now.eventsEnabled();
        if (body.hasKey("events") && !body.isNull("events")) events = body.getObject("events").getBoolean("enabled", events);

        connections.saveFeatures(now.withFeatures(mode, onlineLimit, groupByRole, feedLimit, events, channels), c.userId(), privateOk);
        invalidate();
        log.info("Site admin: {} saved the Discord features of site {} (online={}, channels={}, events={})", c.userId(), c.guildId(), mode.key(),
                channels.size(), events);
        return connectionState(c);
    }

    /**
     * {@code POST clan-site/connections/connect} {@code {guildId}} and {@code POST clan-site/connections/disconnect}. Only the site's own
     * server can be connected (a site is stored by its server), and only by someone Discord says is its owner or has Administrator there:
     * the bot's view where it is in the server, else the website's signed sign-in facts. Disconnecting needs only the site's admin role.
     */
    DataObject connect(SiteCaller c, boolean connect, DataObject body) {
        if (connect) {
            String raw = body.getString("guildId", "").strip();
            if (!raw.equals(Long.toString(c.guildId()))) throw new ApiError(400, "A site can only be connected to the Discord server it was made for.");
            boolean admin = c.botless() ? c.facts() != null && (c.facts().dashboardAdmin() || c.facts().guildOwner())
                    : c.member() != null && (c.member().isOwner() || c.member().hasPermission(Permission.ADMINISTRATOR));
            if (!admin) throw new ApiError(403, "Only the server's owner or someone with Administrator in it can connect it.");
        }
        connections.setConnected(c.guildId(), connect, c.userId());
        invalidate();
        log.info("Site admin: {} {} the server of site {}", c.userId(), connect ? "connected" : "disconnected", c.guildId());
        return connectionState(c);
    }

    /** Everything a site shows from Discord, so a change is on the site at once. */
    private void invalidate() {
        cache.invalidate(CACHE_PREFIX);
        cache.invalidate("events");
        cache.invalidate("notifications");
    }

    private static int number(DataObject o, String key, int fallback, int max, String problem) {
        if (!o.hasKey(key) || o.isNull(key)) return fallback;
        int n;
        try {
            n = o.getInt(key);
        } catch (RuntimeException e) {
            throw new ApiError(400, problem);
        }
        if (n < 1 || n > max) throw new ApiError(400, problem);
        return n;
    }

    /** The channels an admin's save picks, and the private ones among them that have the admin's yes. */
    record Picked(List<Channel> channels, Set<Long> privateOk) {}

    private static Picked parseChannels(Guild guild, DataArray input, List<Channel> before, Set<Long> storedOk) {
        Set<Long> shown = new HashSet<>();
        before.forEach(ch -> shown.add(ch.channelId()));
        Set<Long> privateOk = new HashSet<>();
        if (input.length() > MAX_CHANNELS) throw new ApiError(400, "Pick at most " + MAX_CHANNELS + " channels.");
        List<Channel> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            String raw = item.getString("channelId", "").strip();
            // digits that overflow a long are still a 400, never a 500
            if (!raw.matches("\\d{1,20}") || !CitadelAdminApi.fitsALong(raw)) throw new ApiError(400, "A chosen channel isn't valid.");
            long channelId = Long.parseLong(raw);
            if (!seen.add(channelId)) continue;
            GuildChannel channel = guild.getGuildChannelById(channelId);
            Refusal refusal = SiteDiscordRules.channelRefusal(guild, channel);
            if (refusal != null) throw new ApiError(refusal.forbidden() ? 403 : 400, refusal.reason());
            if (SiteDiscordRules.isPrivate(guild, channel)) {
                // a stored yes counts only for a channel still chosen; a new pick always needs the admin's allowPrivate
                boolean yes = item.getBoolean("allowPrivate", false) || (shown.contains(channelId) && storedOk.contains(channelId));
                if (!yes && !shown.contains(channelId)) {
                    throw new ApiError(403, "#" + channel.getName() + " is private (@everyone can't read it). Choose \"Show it anyway\" to put its posts on the website.");
                }
                if (yes) privateOk.add(channelId);
            }
            String label = item.getString("label", "").strip();
            if (label.length() > 40) throw new ApiError(400, "Labels are up to 40 characters.");
            chosen.add(new Channel(channelId, label.isEmpty() ? null : label, chosen.size()));
        }
        return new Picked(List.copyOf(chosen), Set.copyOf(privateOk));
    }
}
