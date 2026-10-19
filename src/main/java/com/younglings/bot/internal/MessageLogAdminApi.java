package com.younglings.bot.internal;

import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.messagelog.MessageLogConfig;
import com.younglings.bot.messagelog.MessageLogRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's message and voice logs for the website: settings, and clearing the remembered message text. A save is checked whole before anything is stored.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class MessageLogAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(MessageLogAdminApi.class);

    private final MessageLogRepository repository;

    public MessageLogAdminApi(MessageLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("message-logs")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("clear")) {
            AdminRouteExtension.requireMethod(method, "POST");
            int removed = repository.clearGuild(guild.getIdLong());
            log.info("Dashboard: {} cleared {} remembered message(s)", actor.getId(), removed);
            return get(guild);
        }
        return null;
    }

    DataObject get(Guild guild) {
        MessageLogConfig c = repository.config(guild.getIdLong());
        return DataObject.empty().put("enabled", c.enabled()).put("retentionDays", c.retentionDays())
                .put("excludedChannels", DataArray.fromCollection(c.excludedChannels().stream().map(String::valueOf).toList()))
                .put("editChannelId", id(c.editChannelId())).put("deleteChannelId", id(c.deleteChannelId())).put("voiceChannelId", id(c.voiceChannelId()))
                .put("remembered", repository.countFor(guild.getIdLong())).put("messageContent", PrivilegedIntents.messageContent())
                .put("limits", DataObject.empty().put("minDays", MessageLogConfig.MIN_RETENTION).put("maxDays", MessageLogConfig.MAX_RETENTION).put("maxExcluded", MessageLogConfig.MAX_EXCLUDED));
    }

    private static String id(Long value) {
        return value == null ? null : Long.toString(value);
    }

    private static Long logChannel(Guild guild, DataObject body, String key, Long current, String label, List<String> problems) {
        if (!body.hasKey(key)) return current;
        String raw = body.isNull(key) ? "" : body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) {
            problems.add("The " + label + " channel isn't valid.");
            return current;
        }
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw));
        if (channel == null) {
            problems.add("The " + label + " channel doesn't exist in this server.");
            return current;
        }
        if (!channel.canTalk()) {
            problems.add("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages (and embed links) there.");
            return current;
        }
        return channel.getIdLong();
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        MessageLogConfig current = repository.config(guild.getIdLong());
        List<String> problems = new ArrayList<>();

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        int days = body.hasKey("retentionDays") ? body.getInt("retentionDays", -1) : current.retentionDays();
        if (days < MessageLogConfig.MIN_RETENTION || days > MessageLogConfig.MAX_RETENTION) problems.add("Messages can be remembered for " + MessageLogConfig.MIN_RETENTION + " to " + MessageLogConfig.MAX_RETENTION + " days.");
        Long edit = logChannel(guild, body, "editChannelId", current.editChannelId(), "edits", problems);
        Long deleted = logChannel(guild, body, "deleteChannelId", current.deleteChannelId(), "deletes", problems);
        Long voice = logChannel(guild, body, "voiceChannelId", current.voiceChannelId(), "voice", problems);

        Set<Long> excluded = new LinkedHashSet<>(current.excludedChannels());
        if (body.hasKey("excludedChannels")) {
            excluded.clear();
            DataArray input = body.getArray("excludedChannels");
            if (input.length() > MessageLogConfig.MAX_EXCLUDED) problems.add("At most " + MessageLogConfig.MAX_EXCLUDED + " channels can be left out.");
            for (int i = 0; i < input.length() && i < MessageLogConfig.MAX_EXCLUDED; i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getGuildChannelById(Long.parseLong(raw)) == null) problems.add("A channel to leave out doesn't exist in this server.");
                else excluded.add(Long.parseLong(raw));
            }
        }
        if (enabled && edit == null && deleted == null && voice == null) problems.add("Choose at least one log channel before switching the logs on.");
        if (!problems.isEmpty()) throw new ApiError(400, "The logs can't be saved yet.", problems);

        MessageLogConfig saved = new MessageLogConfig(guild.getIdLong(), enabled, days, excluded, edit, deleted, voice);
        repository.saveConfig(saved);
        // text that must not be kept any more goes now, not at the next purge
        if (!saved.cachesMessages()) repository.clearGuild(guild.getIdLong());
        else if (!excluded.isEmpty()) repository.clearChannels(guild.getIdLong(), excluded);
        log.info("Dashboard: {} saved the message logs ({}, {} days)", actor.getId(), enabled ? "on" : "off", days);
        return get(guild);
    }
}
