package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.lockdown.LockdownRepository.Config;
import com.runeclans.bot.lockdown.LockdownRepository.Lock;
import com.runeclans.bot.lockdown.LockdownService;
import com.runeclans.bot.permission.DashboardAccess;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
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
 * The dashboard's Lockdown page: the saved lockdown set (channels, and the roles that can still talk in them), what is locked right now, and lock / unlock /
 * start / end. Changing who can talk in channels is an Admin-tier action, like the Hub's "who may use a command"; the page can be viewed by anyone who can
 * use the dashboard. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs
 * every write. Lock / unlock / start / end are checked here and run as Discord jobs ({@link LockdownJobs}, T-456): the answer is the page at once
 * with {@code job} for the page to follow.
 * <ul>
 *   <li>{@code GET lockdown}; {@code PUT lockdown} saves the set ({@code channelIds}, {@code keepTalkingRoleIds})</li>
 *   <li>{@code POST lockdown/lock} ({@code channelId}, {@code reason}), {@code POST lockdown/unlock} ({@code channelId})</li>
 *   <li>{@code POST lockdown/start} ({@code reason}), {@code POST lockdown/end}</li>
 * </ul>
 */
@BService
public class LockdownAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(LockdownAdminApi.class);

    private final LockdownService service;
    private final DashboardAccess access;
    private final DiscordOutbox outbox;

    public LockdownAdminApi(LockdownService service, DashboardAccess access, DiscordOutbox outbox) {
        this.service = service;
        this.access = access;
        this.outbox = outbox;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("lockdown")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            requireAdmin(guild, actor);
            return save(guild, actor, body.get());
        }
        if (parts.length != 2) return null;
        AdminRouteExtension.requireMethod(method, "POST");
        requireAdmin(guild, actor);
        return switch (parts[1]) {
            case "lock" -> lock(guild, actor, body.get());
            case "unlock" -> unlock(guild, actor, body.get());
            case "start" -> start(guild, actor, body.get());
            case "end" -> end(guild, actor);
            default -> null;
        };
    }

    private void requireAdmin(Guild guild, Member actor) {
        if (access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_lock_channels"));
    }

    DataObject get(Guild guild) {
        Config config = service.config(guild.getIdLong());
        DataArray channels = DataArray.empty();
        for (long id : config.channelIds()) channels.add(Long.toString(id));
        DataArray roles = DataArray.empty();
        for (long id : config.keepTalkingRoleIds()) roles.add(Long.toString(id));
        DataArray locks = DataArray.empty();
        for (Lock lock : service.locks(guild.getIdLong())) {
            StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, lock.channelId());
            Member by = guild.getMemberById(lock.lockedBy());
            locks.add(DataObject.empty().put("channelId", Long.toString(lock.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("lockedBy", by == null ? Long.toString(lock.lockedBy()) : by.getEffectiveName()).put("lockedAt", lock.lockedAt().toString())
                    .put("reason", lock.reason()).put("lockdown", lock.lockdown()).put("canUnlock", channel != null && LockdownService.canManage(guild, channel)));
        }
        return DataObject.empty().put("channelIds", channels).put("keepTalkingRoleIds", roles).put("locks", locks)
                .put("maxChannels", Config.MAX_CHANNELS).put("maxRoles", Config.MAX_ROLES);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        Config current = service.config(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        List<Long> channels = current.channelIds();
        if (body.hasKey("channelIds")) {
            Set<Long> ids = new LinkedHashSet<>();
            DataArray input = body.getArray("channelIds");
            for (int i = 0; i < input.length(); i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}")) {
                    problems.add("A channel in the lockdown set isn't valid.");
                    continue;
                }
                long id = Long.parseLong(raw);
                StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, id);
                if (channel == null) problems.add("A channel in the lockdown set doesn't exist or isn't a text channel.");
                else if (!LockdownService.canManage(guild, channel)) problems.add("The bot can't manage permissions in #" + channel.getName() + ", so it can't lock it.");
                else ids.add(id);
            }
            if (ids.size() > Config.MAX_CHANNELS) problems.add("At most " + Config.MAX_CHANNELS + " channels can be in the lockdown set.");
            channels = new ArrayList<>(ids);
        }
        List<Long> roles = current.keepTalkingRoleIds();
        if (body.hasKey("keepTalkingRoleIds")) {
            Set<Long> ids = new LinkedHashSet<>();
            DataArray input = body.getArray("keepTalkingRoleIds");
            for (int i = 0; i < input.length(); i++) {
                String raw = input.getString(i, "").strip();
                Role role = raw.matches("\\d{1,20}") ? guild.getRoleById(Long.parseLong(raw)) : null;
                if (role == null) problems.add("A role that keeps talking doesn't exist in this server.");
                else if (role.isPublicRole()) problems.add("@everyone can't be a role that keeps talking: that would stop the lock working.");
                else ids.add(role.getIdLong());
            }
            if (ids.size() > Config.MAX_ROLES) problems.add("At most " + Config.MAX_ROLES + " roles can keep talking.");
            roles = new ArrayList<>(ids);
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_lockdown_set_can_t_be"), problems);

        service.saveConfig(new Config(guild.getIdLong(), channels, roles));
        log.info("Dashboard: {} saved the lockdown set ({} channels, {} roles keep talking)", actor.getId(), channels.size(), roles.size());
        return get(guild);
    }

    private DataObject lock(Guild guild, Member actor, DataObject body) {
        StandardGuildMessageChannel channel = channel(guild, body);
        String reason = body.isNull("reason") ? "" : body.getString("reason", "").strip();
        if (reason.length() > 200) throw new ApiError(400, Text.of(guild, "dashboard.the_reason_can_be_at_most"));
        if (lockOf(guild, channel.getIdLong()) != null) refuse(LockdownService.Outcome.ALREADY, channel);
        if (!LockdownService.canManage(guild, channel)) refuse(LockdownService.Outcome.NO_PERMISSION, channel);
        DiscordJob job = outbox.enqueue(LockdownJobs.LOCK, guild.getIdLong(), actor.getIdLong(), "lockdown-lock:" + guild.getIdLong() + ":" + channel.getIdLong(),
                LockdownJobs.lockPayload(channel.getIdLong(), reason));
        log.info("Dashboard: {} queued locking #{} (job {})", actor.getId(), channel.getName(), job.id());
        return get(guild).put("job", job.ticket());
    }

    private DataObject unlock(Guild guild, Member actor, DataObject body) {
        long id = parse(body);
        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, id);
        if (channel == null) {
            // the channel is gone: there is nothing to put back, only the record to forget
            service.forget(guild.getIdLong(), id);
            return get(guild);
        }
        if (lockOf(guild, id) == null) refuse(LockdownService.Outcome.NOT_LOCKED, channel);
        if (!LockdownService.canManage(guild, channel)) refuse(LockdownService.Outcome.NO_PERMISSION, channel);
        DiscordJob job = outbox.enqueue(LockdownJobs.UNLOCK, guild.getIdLong(), actor.getIdLong(), "lockdown-unlock:" + guild.getIdLong() + ":" + id,
                LockdownJobs.unlockPayload(id));
        log.info("Dashboard: {} queued unlocking #{} (job {})", actor.getId(), channel.getName(), job.id());
        return get(guild).put("job", job.ticket());
    }

    private DataObject start(Guild guild, Member actor, DataObject body) {
        if (service.config(guild.getIdLong()).channelIds().isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.choose_the_channels_of_the_lockdown"));
        String reason = body.isNull("reason") ? "" : body.getString("reason", "").strip();
        if (reason.length() > 200) throw new ApiError(400, Text.of(guild, "dashboard.the_reason_can_be_at_most"));
        DiscordJob job = outbox.enqueue(LockdownJobs.START, guild.getIdLong(), actor.getIdLong(), "lockdown-start:" + guild.getIdLong(), LockdownJobs.startPayload(reason));
        log.info("Dashboard: {} queued a lockdown (job {})", actor.getId(), job.id());
        return get(guild).put("job", job.ticket());
    }

    private DataObject end(Guild guild, Member actor) {
        DiscordJob job = outbox.enqueue(LockdownJobs.END, guild.getIdLong(), actor.getIdLong(), "lockdown-end:" + guild.getIdLong(), DataObject.empty());
        log.info("Dashboard: {} queued ending the lockdown (job {})", actor.getId(), job.id());
        return get(guild).put("job", job.ticket());
    }

    private Lock lockOf(Guild guild, long channelId) {
        return service.locks(guild.getIdLong()).stream().filter(l -> l.channelId() == channelId).findFirst().orElse(null);
    }

    private static long parse(DataObject body) {
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.en("dashboard.choose_a_channel"));
        return Long.parseLong(raw);
    }

    private static StandardGuildMessageChannel channel(Guild guild, DataObject body) {
        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, parse(body));
        if (channel == null) throw new ApiError(404, Text.of(guild, "dashboard.that_channel_doesn_t_exist_or"));
        return channel;
    }

    /** Says why a lock or unlock can't be queued, as the page has always said it. */
    private static void refuse(LockdownService.Outcome outcome, StandardGuildMessageChannel channel) {
        switch (outcome) {
            case ALREADY -> throw new ApiError(409, Text.of(channel.getGuild(), "dashboard.is_already_locked", channel.getName()));
            case NOT_LOCKED -> throw new ApiError(409, Text.of(channel.getGuild(), "dashboard.isn_t_locked_by_jonnybot_so", channel.getName()));
            case NO_PERMISSION -> throw new ApiError(400, Text.of(channel.getGuild(), "dashboard.jonnybot_needs_to_see_send_messages", channel.getName()));
            default -> { }
        }
    }
}
