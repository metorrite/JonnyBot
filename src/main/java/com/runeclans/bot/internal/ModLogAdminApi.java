package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.modlog.ModLogConfig;
import com.runeclans.bot.modlog.ModLogRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's moderation log settings for the website. A save is checked whole before anything is stored. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 * {@code GET modlog} and {@code PUT modlog}; the reply also says whether JonnyBot can read the audit log, since without it entries
 * cannot say who acted or why.
 */
@BService
public class ModLogAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ModLogAdminApi.class);

    private final ModLogRepository repository;

    public ModLogAdminApi(ModLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("modlog")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        ModLogConfig c = repository.get(guild.getIdLong());
        Member self = guild.getSelfMember();
        return DataObject.empty().put("enabled", c.enabled()).put("channelId", c.channelId() == null ? null : Long.toString(c.channelId()))
                .put("logBans", c.logBans()).put("logTimeouts", c.logTimeouts())
                .put("canReadAuditLog", self.hasPermission(Permission.VIEW_AUDIT_LOGS));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        ModLogConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        boolean logBans = body.hasKey("logBans") ? body.getBoolean("logBans", true) : current.logBans();
        boolean logTimeouts = body.hasKey("logTimeouts") ? body.getBoolean("logTimeouts", true) : current.logTimeouts();

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
        if (enabled && channelId == null) problems.add("Choose the moderation log channel before switching it on.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_moderation_log_can_t_be"), problems);

        repository.save(new ModLogConfig(guild.getIdLong(), enabled, channelId, logBans, logTimeouts));
        log.info("Dashboard: {} saved the moderation log ({})", actor.getId(), enabled ? "on" : "off");
        return get(guild);
    }
}
