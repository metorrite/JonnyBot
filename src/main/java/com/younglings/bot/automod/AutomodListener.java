package com.younglings.bot.automod;

import com.younglings.bot.moderation.ModAction;
import com.younglings.bot.moderation.ModCase;
import com.younglings.bot.moderation.ModService;
import com.younglings.bot.BotListener;
import com.younglings.bot.automod.AutomodRepository.Config;
import com.younglings.bot.automod.AutomodRules.Rule;
import com.younglings.bot.warning.AutopunishService;
import com.younglings.bot.warning.WarningRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Checks every message against its server's automod rules. A message that breaks one is deleted and the writer is told why in a note that
 * removes itself; if the rule is set to warn, the writer also gets a recorded warning (which counts toward Autopunish). Bots, webhooks,
 * anyone who can manage messages in the channel, and the server's exempt roles and channels are never checked. Registered with JDA by
 * {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class AutomodListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AutomodListener.class);
    private static final int NOTICE_SECONDS = 8;
    private static final int SWEEP_EVERY = 2000;

    private final AutomodRepository repository;
    private final WarningRepository warnings;
    private final AutopunishService autopunish;
    private final ModService mod;
    private final Map<String, AutomodRules.History> histories = new ConcurrentHashMap<>();
    private int seen;

    public AutomodListener(AutomodRepository repository, WarningRepository warnings, AutopunishService autopunish, ModService mod) {
        this.mod = mod;
        this.repository = repository;
        this.warnings = warnings;
        this.autopunish = autopunish;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            Config config = repository.get(event.getGuild().getIdLong());
            if (!config.enabled() || config.exemptChannels().contains(event.getChannel().getIdLong())) return;
            if (!(event.getChannel() instanceof GuildMessageChannel channel)) return;
            Member member = event.getMember();
            if (member == null || member.hasPermission(channel, Permission.MESSAGE_MANAGE) || member.hasPermission(Permission.ADMINISTRATOR)) return;
            if (member.getRoles().stream().anyMatch(r -> config.exemptRoles().contains(r.getIdLong()))) return;
            if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) return;

            Message message = event.getMessage();
            long now = System.currentTimeMillis();
            AutomodRules.History history = histories.computeIfAbsent(event.getGuild().getId() + ":" + event.getAuthor().getId(), k -> new AutomodRules.History());
            var mentions = message.getMentions();
            int pinged = mentions.getUsers().size() + mentions.getRoles().size() + (mentions.mentionsEveryone() ? 1 : 0);
            Rule broken = AutomodRules.firstViolation(config.rules(), message.getContentRaw(), pinged, history, now);
            sweep(now);
            if (broken == null) return;

            message.delete().queue(ok -> {}, error -> log.debug("Couldn't delete a message in {}: {}", channel.getId(), error.getMessage()));
            String note = "Please stop " + broken.reason() + ", " + event.getAuthor().getAsMention() + ".";
            if (config.rules().get(broken).warn()) note = warn(event, member, broken) + " " + note;
            if (channel.canTalk()) {
                channel.sendMessage(note).setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                        .queue(sent -> sent.delete().queueAfter(NOTICE_SECONDS, TimeUnit.SECONDS, ok -> {}, error -> {}), error -> {});
            }
        } catch (Exception e) {
            log.error("Automod failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }

    /** Records a warning for the break and applies any Autopunish rule it reaches. Returns a short note for the channel (empty when nothing more happened). */
    private String warn(MessageReceivedEvent event, Member member, Rule rule) {
        long guildId = event.getGuild().getIdLong();
        warnings.add(guildId, member.getIdLong(), event.getJDA().getSelfUser().getIdLong(), "Automod: " + rule.label());
        int total = warnings.countFor(guildId, member.getIdLong());
        mod.recordOnly(event.getGuild(), member.getIdLong(), member.getUser().getName(), event.getJDA().getSelfUser().getIdLong(), event.getGuild().getSelfMember().getEffectiveName(),
                ModAction.WARN, "Automod: " + rule.label(), null, ModCase.AUTOMOD);
        member.getUser().openPrivateChannel()
                .flatMap(dm -> dm.sendMessage("You have been warned in **" + event.getGuild().getName() + "** by automod for " + rule.reason() + "."))
                .queue(ok -> {}, error -> {});
        String punished = autopunish.applyFor(event.getGuild(), member, total);
        return punished == null ? "" : punished;
    }

    private void sweep(long now) {
        if (++seen < SWEEP_EVERY) return;
        seen = 0;
        histories.values().removeIf(h -> h.idleFor(now, 300));
    }
}
