package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.starboard.StarboardConfig;
import com.runeclans.bot.starboard.StarboardRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's starboard for the website. A save is checked whole before anything is stored. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class StarboardAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(StarboardAdminApi.class);

    private final StarboardRepository repository;

    public StarboardAdminApi(StarboardRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("starboard")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        StarboardConfig c = repository.get(guild.getIdLong());
        return DataObject.empty().put("enabled", c.enabled()).put("channelId", c.channelId() == null ? null : Long.toString(c.channelId()))
                .put("emoji", c.emoji()).put("threshold", c.threshold()).put("selfStar", c.selfStar()).put("maxThreshold", StarboardConfig.MAX_THRESHOLD)
                .put("channelThresholds", rulesJson(c)).put("skipHiddenChannels", c.skipHiddenChannels())
                .put("onlyChannels", DataArray.fromCollection(c.onlyChannels().stream().map(String::valueOf).toList())).put("maxChannelRules", StarboardConfig.MAX_CHANNEL_RULES);
    }

    private static Long existingChannel(Guild guild, String raw, String label, List<String> problems) {
        String id = raw == null ? "" : raw.strip();
        if (!id.matches("\\d{1,20}")) {
            problems.add(label + " isn't valid.");
            return null;
        }
        if (guild.getGuildChannelById(Long.parseLong(id)) == null) {
            problems.add(label + " doesn't exist in this server.");
            return null;
        }
        return Long.parseLong(id);
    }

    private static DataArray rulesJson(StarboardConfig c) {
        DataArray rules = DataArray.empty();
        c.channelThresholds().forEach((channel, n) -> rules.add(DataObject.empty().put("channelId", Long.toString(channel)).put("threshold", n)));
        return rules;
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        StarboardConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        boolean selfStar = body.hasKey("selfStar") ? body.getBoolean("selfStar", false) : current.selfStar();
        int threshold = body.hasKey("threshold") ? body.getInt("threshold", -1) : current.threshold();
        if (threshold < 1 || threshold > StarboardConfig.MAX_THRESHOLD) problems.add("The number of reactions needed must be between 1 and " + StarboardConfig.MAX_THRESHOLD + ".");

        String emoji = body.hasKey("emoji") ? body.getString("emoji", "").strip() : current.emoji();
        if (emoji.isEmpty()) emoji = StarboardConfig.DEFAULT_EMOJI;
        try {
            Emoji.fromFormatted(emoji);
        } catch (RuntimeException e) {
            problems.add("That isn't an emoji Discord can use as a reaction.");
        }

        Long channelId = current.channelId();
        if (body.hasKey("channelId")) {
            String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
            if (raw.isEmpty()) {
                channelId = null;
            } else if (!raw.matches("\\d{1,20}")) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
            }
        }
        if (enabled && channelId == null) problems.add("Choose the starboard channel before switching it on.");

        Map<Long, Integer> rules = new LinkedHashMap<>(current.channelThresholds());
        if (body.hasKey("channelThresholds")) {
            rules.clear();
            DataArray input = body.getArray("channelThresholds");
            if (input.length() > StarboardConfig.MAX_CHANNEL_RULES) problems.add("At most " + StarboardConfig.MAX_CHANNEL_RULES + " channels can have a number of their own.");
            for (int i = 0; i < input.length() && i < StarboardConfig.MAX_CHANNEL_RULES; i++) {
                DataObject rule = input.getObject(i);
                Long source = existingChannel(guild, rule.getString("channelId", ""), "A channel with its own number", problems);
                int n = rule.getInt("threshold", -1);
                if (n < 1 || n > StarboardConfig.MAX_THRESHOLD) problems.add("A channel's own number must be between 1 and " + StarboardConfig.MAX_THRESHOLD + ".");
                else if (source != null) rules.put(source, n);
            }
        }
        boolean skipHidden = body.hasKey("skipHiddenChannels") ? body.getBoolean("skipHiddenChannels", false) : current.skipHiddenChannels();
        Set<Long> only = new LinkedHashSet<>(current.onlyChannels());
        if (body.hasKey("onlyChannels")) {
            only.clear();
            DataArray input = body.getArray("onlyChannels");
            if (input.length() > StarboardConfig.MAX_CHANNEL_RULES) problems.add("At most " + StarboardConfig.MAX_CHANNEL_RULES + " channels can be listed to copy from.");
            for (int i = 0; i < input.length() && i < StarboardConfig.MAX_CHANNEL_RULES; i++) {
                Long source = existingChannel(guild, input.getString(i, ""), "A channel to copy from", problems);
                if (source != null) only.add(source);
            }
        }

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_starboard_can_t_be_saved"), problems);

        repository.save(new StarboardConfig(guild.getIdLong(), enabled, channelId, emoji, threshold, selfStar, rules, skipHidden, only));
        log.info("Dashboard: {} saved the starboard ({}, {} x {})", actor.getId(), enabled ? "on" : "off", threshold, emoji);
        return get(guild);
    }
}
