package com.younglings.bot.member;

import com.younglings.bot.member.MemberProfileRepository.Goal;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeXpTable;
import com.younglings.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Sends the DMs members opted into on the website: a congratulation when a skill goal is reached, and a
 * reminder shortly before a Discord event starts. Everything it needs to remember (goals, who was already
 * reminded) is in the database, so a restart never repeats or drops a message — each tick just asks the
 * database what's due.
 */
@BService
public class MemberNotifier {
    private static final Logger log = LoggerFactory.getLogger(MemberNotifier.class);
    private static final Duration REMINDER_LEAD = Duration.ofMinutes(30);

    private final MemberProfileRepository repository;
    private final PlayerLinkRepository links;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor((ThreadFactory) r -> {
        Thread t = new Thread(r, "member-notifier");
        t.setDaemon(true);
        return t;
    });

    public MemberNotifier(MemberProfileRepository repository, PlayerLinkRepository links) {
        this.repository = repository;
        this.links = links;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        JDA jda = event.getJda();
        executor.scheduleWithFixedDelay(() -> run("goals", () -> checkGoals(jda)), 90, 15 * 60, TimeUnit.SECONDS);
        executor.scheduleWithFixedDelay(() -> run("event reminders", () -> sendEventReminders(jda)), 120, 5 * 60, TimeUnit.SECONDS);
        log.info("Member notifier started: goals every 15 minutes, event reminders every 5.");
    }

    private void run(String what, Runnable task) {
        try {
            task.run();
        } catch (Exception e) {
            log.error("Member notifier ({}) failed", what, e);
        }
    }

    // ---------- goals ----------

    void checkGoals(JDA jda) {
        for (Goal goal : repository.pendingGoals()) {
            var snapshot = links.getLatestSnapshot(goal.rsn());
            if (snapshot == null) continue;
            SkillValue skill = links.getSkillsForSnapshot(snapshot.snapshotId()).stream().filter(s -> s.skillId() == goal.skillId()).findFirst().orElse(null);
            if (skill == null || skill.xp() < RuneScapeXpTable.xpForLevel(goal.skillId(), goal.targetLevel())) continue;

            if (!repository.markAchieved(goal.id())) continue; // someone else got there first
            if (!repository.wantsGoalDms(goal.guildId(), goal.userId())) continue;

            String message = "🎉 **Goal reached!** " + goal.rsn() + " is now level **" + goal.targetLevel() + " " + RuneScapeSkillCatalog.nameFor(goal.skillId())
                    + "**. Set your next goal on the Younglings website.";
            jda.retrieveUserById(goal.userId()).flatMap(user -> user.openPrivateChannel()).flatMap(channel -> channel.sendMessage(message))
                    .queue(ok -> {}, error -> log.debug("Couldn't DM goal notice to {}: {}", goal.userId(), error.getMessage()));
        }
    }

    // ---------- event reminders ----------

    void sendEventReminders(JDA jda) {
        for (Guild guild : jda.getGuilds()) sendEventReminders(jda, guild);
    }

    private void sendEventReminders(JDA jda, Guild guild) {
        long guildId = guild.getIdLong();
        Set<Long> subscribers = new HashSet<>(repository.usersWantingEventReminders(guildId));
        if (subscribers.isEmpty()) return;

        OffsetDateTime now = OffsetDateTime.now();
        for (ScheduledEvent event : guild.getScheduledEvents()) {
            if (event.getStatus() != ScheduledEvent.Status.SCHEDULED) continue;
            Duration until = Duration.between(now, event.getStartTime());
            if (until.isNegative() || until.compareTo(REMINDER_LEAD.plusMinutes(5)) > 0) continue;

            String url = "https://discord.com/events/" + guild.getId() + "/" + event.getId();
            String message = "⏰ **" + event.getName() + "** starts in about " + Math.max(1, until.toMinutes()) + " minutes. " + url;
            for (long userId : subscribers) {
                if (!repository.claimReminder(event.getIdLong(), userId)) continue;
                jda.retrieveUserById(userId).flatMap(user -> user.openPrivateChannel()).flatMap(channel -> channel.sendMessage(message))
                        .queue(ok -> {}, error -> log.debug("Couldn't DM event reminder to {}: {}", userId, error.getMessage()));
            }
        }
    }
}
