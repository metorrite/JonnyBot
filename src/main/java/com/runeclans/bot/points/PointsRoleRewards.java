package com.runeclans.bot.points;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.points.PointsExtrasRules.RolePlan;
import com.runeclans.bot.points.PointsExtrasRules.RoleReward;
import com.runeclans.bot.rolepanel.RolePanelService;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Hands out role rewards after a member's points go up. It asks {@link PointsService} to call {@link #evaluate} after every entry that adds points; the work
 * is done on a background thread and never throws, so a points entry never fails because a role could not be given. Each reward is given once to
 * each member (recorded in the database), only roles the bot may safely hand out are given, and a role is never taken back because the balance fell.
 */
@BService
public class PointsRoleRewards {
    private static final Logger log = LoggerFactory.getLogger(PointsRoleRewards.class);

    private final PointsExtrasRepository extras;
    private final PointsRepository points;
    private volatile JDA jda;
    private final ExecutorService executor = Executors.newSingleThreadExecutor((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "points-role-rewards");
        thread.setDaemon(true);
        return thread;
    });

    public PointsRoleRewards(PointsExtrasRepository extras, PointsRepository points, PointsService service) {
        this.extras = extras;
        this.points = points;
        service.onPointsAdded(this::evaluate);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
    }

    /** Looks at a member's balance and gives the role rewards it has reached, in the background. */
    public void evaluate(long guildId, long userId) {
        if (jda == null) return;
        try {
            executor.execute(() -> run(guildId, userId));
        } catch (RuntimeException e) {
            log.debug("Role rewards for {} not scheduled", userId, e);
        }
    }

    void run(long guildId, long userId) {
        try {
            List<RoleReward> rewards = extras.roleRewards(guildId);
            if (rewards.isEmpty()) return;
            Guild guild = Shards.guild(jda, guildId);
            if (guild == null) return;
            guild.retrieveMemberById(userId).queue(member -> {
                try {
                    Set<Long> held = new HashSet<>();
                    member.getRoles().forEach(r -> held.add(r.getIdLong()));
                    RolePlan plan = PointsExtrasRules.planRoles(rewards, points.balance(guildId, userId), extras.grantedTo(guildId, userId), held);
                    if (plan.none()) return;
                    // claim first: only the call that wins the claim gives the role, so a race can't give it twice
                    List<RoleReward> mine = plan.granted().stream().filter(r -> extras.claimGrant(guildId, userId, r.id())).toList();
                    if (mine.isEmpty()) return;
                    List<Role> add = safe(guild, plan.addRoles());
                    List<Role> remove = safe(guild, plan.removeRoles());
                    if (add.isEmpty() && remove.isEmpty()) return;
                    guild.modifyMemberRoles(member, add, remove).reason("Points role reward").queue(done -> {}, error -> {
                        log.warn("Points role reward could not be given to {} in guild {}: {}", userId, guildId, error.getMessage());
                        mine.forEach(r -> extras.releaseGrant(userId, r.id()));
                    });
                } catch (RuntimeException e) {
                    log.warn("Points role rewards failed for {} in guild {}", userId, guildId, e);
                }
            }, error -> log.debug("Points role rewards: member {} is not in guild {}", userId, guildId));
        } catch (RuntimeException e) {
            log.warn("Points role rewards failed for {} in guild {}", userId, guildId, e);
        }
    }

    private static List<Role> safe(Guild guild, Set<Long> ids) {
        return ids.stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
    }
}
