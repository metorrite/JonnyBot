package com.younglings.bot.internal;

import com.younglings.bot.accountgate.AccountGateConfig;
import com.younglings.bot.accountgate.AccountGateConfig.Action;
import com.younglings.bot.accountgate.AccountGateRepository;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
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
 * A server's new-account settings for the website. A save is checked whole before anything is stored. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 * {@code GET accountgate} and {@code PUT accountgate}; the reply also says whether JonnyBot may kick, which the kick action needs.
 */
@BService
public class AccountGateAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AccountGateAdminApi.class);

    private final AccountGateRepository repository;

    public AccountGateAdminApi(AccountGateRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("accountgate")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        AccountGateConfig c = repository.get(guild.getIdLong());
        return DataObject.empty().put("enabled", c.enabled()).put("minAgeDays", c.minAgeDays()).put("action", c.action().name())
                .put("alertChannelId", c.alertChannelId() == null ? null : Long.toString(c.alertChannelId()))
                .put("maxAgeDays", AccountGateConfig.MAX_AGE_DAYS).put("canKick", guild.getSelfMember().hasPermission(Permission.KICK_MEMBERS));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        AccountGateConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        int days = body.hasKey("minAgeDays") ? body.getInt("minAgeDays", -1) : current.minAgeDays();
        if (days < 1 || days > AccountGateConfig.MAX_AGE_DAYS) problems.add("The minimum account age must be between 1 and " + AccountGateConfig.MAX_AGE_DAYS + " days.");

        Action action = current.action();
        if (body.hasKey("action")) {
            try {
                action = Action.valueOf(body.getString("action", "").strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Choose alert or kick.");
            }
        }
        if (action == Action.KICK && !guild.getSelfMember().hasPermission(Permission.KICK_MEMBERS)) {
            problems.add("JonnyBot needs the Kick Members permission to remove new accounts. Give it that in Discord, or choose to alert only.");
        }

        Long channelId = current.alertChannelId();
        if (body.hasKey("alertChannelId")) {
            String raw = body.isNull("alertChannelId") ? "" : body.getString("alertChannelId", "").strip();
            if (raw.isEmpty()) {
                channelId = null;
            } else if (!raw.matches("\\d{1,20}")) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
            }
        }
        if (enabled && channelId == null && action == Action.ALERT) problems.add("Choose a channel for the alerts, or nothing will be reported.");
        if (!problems.isEmpty()) throw new ApiError(400, "The new-account settings can't be saved yet.", problems);

        repository.save(new AccountGateConfig(guild.getIdLong(), enabled, days, action, channelId));
        log.info("Dashboard: {} saved the new-account settings ({}, {} days, {})", actor.getId(), enabled ? "on" : "off", days, action);
        return get(guild);
    }
}
