package com.younglings.bot.hubsites;

import com.younglings.bot.BotListener;
import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.runeclans.HubWriteQueue;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * What the bot tells the hub under {@code SITES_BACKEND=hub} (T-505), on its own lane so no hub call holds up JDA's event thread:
 * <ul>
 *   <li>on start, RuneClan Bot's capabilities for all servers and for each server it is in ({@link CapabilityReports}: the per-server list is
 *   what makes the hub count the bot as connected there), and in every server the holders of each role the hub's {@code role-mappings} name</li>
 *   <li>on joining a server, its capabilities there; on leaving one, an empty list</li>
 *   <li>on a member's role change, the holders of the mapped roles that changed ({@link RoleReports}: gathered for a few seconds, one
 *   report per role)</li>
 * </ul>
 * Writes go through the hub client's queue, so a hub that is briefly down gets them a little later. Off: nothing here runs.
 */
@BService
public class HubSiteReporter extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(HubSiteReporter.class);

    private final HubSites sites;
    private final RuneClansHubClient hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("hub-sites");
    private volatile JDA jda;
    private final RoleReports reports;
    private final CapabilityReports capabilities;

    public HubSiteReporter(HubSites sites, RuneClansHubClient hub) {
        this.sites = sites;
        this.hub = hub;
        this.reports = new RoleReports(sites::mappedRoles, this::holders, this::send,
                (delay, task) -> lane.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS));
        this.capabilities = new CapabilityReports((path, body) -> hub.write("PUT", path, body, HubSiteReporter::done));
    }

    @Override
    public void onReady(ReadyEvent event) {
        jda = event.getJDA();
        if (!sites.readsHub()) return;
        lane.execute(() -> {
            capabilities.start(event.getJDA().getGuilds().stream().map(Guild::getIdLong).toList(), PrivilegedIntents.presence(), PrivilegedIntents.messageContent());
            int roles = 0;
            for (Guild guild : event.getJDA().getGuilds()) {
                try {
                    roles += reports.all(guild.getIdLong());
                } catch (RuntimeException e) {
                    log.warn("Hub role report for a server failed: {}", e.getClass().getSimpleName());
                }
            }
            log.info("RuneClans hub: declared capabilities and reported {} mapped role(s)", roles);
        });
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        if (!sites.readsHub()) return;
        long guildId = event.getGuild().getIdLong();
        lane.execute(() -> capabilities.joined(guildId, PrivilegedIntents.presence(), PrivilegedIntents.messageContent()));
    }

    @Override
    public void onGuildLeave(GuildLeaveEvent event) {
        if (!sites.readsHub()) return;
        long guildId = event.getGuild().getIdLong();
        lane.execute(() -> capabilities.left(guildId));
    }

    @Override
    public void onGuildMemberRoleAdd(GuildMemberRoleAddEvent event) {
        changed(event.getGuild(), event.getRoles());
    }

    @Override
    public void onGuildMemberRoleRemove(GuildMemberRoleRemoveEvent event) {
        changed(event.getGuild(), event.getRoles());
    }

    private void changed(Guild guild, List<Role> roles) {
        if (!sites.readsHub()) return;
        List<Long> ids = roles.stream().map(Role::getIdLong).toList();
        long guildId = guild.getIdLong();
        lane.execute(() -> reports.changed(guildId, ids));
    }

    /** Every holder of the role, loaded from Discord (the member cache holds only online members); null when Discord couldn't say. */
    private List<Long> holders(long guildId, long roleId) {
        JDA current = jda;
        Guild guild = current == null ? null : current.getGuildById(guildId);
        Role role = guild == null ? null : guild.getRoleById(roleId);
        if (role == null) return guild == null ? null : List.of(); // a mapped role deleted in Discord has no holders
        try {
            List<Member> members = guild.findMembersWithRoles(role).setTimeout(30, TimeUnit.SECONDS).get();
            List<Long> ids = new ArrayList<>(members.size());
            for (Member m : members) ids.add(m.getIdLong());
            return ids;
        } catch (RuntimeException e) {
            log.warn("Couldn't load a mapped role's holders: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void send(long guildId, long roleId, List<Long> holders) {
        hub.write("PUT", "/v1/guilds/" + guildId + "/role-members/" + roleId, body(holders).toString(), HubSiteReporter::done);
    }

    /** {@code {"userIds": ["..."]}}: ids as strings, as everywhere in the hub's API. */
    static DataObject body(List<Long> holders) {
        DataArray ids = DataArray.empty();
        for (long id : holders) ids.add(Long.toString(id));
        return DataObject.empty().put("userIds", ids);
    }

    private static void done(HubWriteQueue.Result result) {
        if (result.outcome() != HubWriteQueue.Outcome.SENT) log.warn("A hub site report ended {}", result.outcome());
    }
}
