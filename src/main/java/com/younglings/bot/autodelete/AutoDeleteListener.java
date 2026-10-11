package com.younglings.bot.autodelete;

import com.younglings.bot.BotListener;
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
import java.util.concurrent.TimeUnit;

/**
 * Deletes messages that break their channel's auto-delete rule and tells the writer why in a note that removes itself after a few
 * seconds. Bots, webhooks and anyone who can manage messages in the channel are never touched. Registered with JDA by {@code Bot}
 * because it is a {@link BotListener}.
 */
@BService
public class AutoDeleteListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(AutoDeleteListener.class);
    private static final int NOTICE_SECONDS = 8;

    private final AutoDeleteRepository repository;

    public AutoDeleteListener(AutoDeleteRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        try {
            AutoDeleteRule rule = repository.forChannel(event.getGuild().getIdLong(), event.getChannel().getIdLong());
            if (rule == null) return;
            Message message = event.getMessage();
            AutoDeleteRule.Filter broken = rule.violation(message.getContentRaw(), message.getAttachments().size());
            if (broken == null) return;
            if (!(event.getChannel() instanceof GuildMessageChannel channel)) return;
            if (!event.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) return;
            Member member = event.getMember();
            if (member != null && member.hasPermission(channel, Permission.MESSAGE_MANAGE)) return; // moderators may post anything

            message.delete().queue(ok -> {}, error -> log.debug("Couldn't delete a message in {}: {}", channel.getId(), error.getMessage()));
            if (channel.canTalk()) {
                channel.sendMessage(AutoDeleteRule.notice(event.getAuthor().getAsMention(), broken))
                        .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                        .queue(note -> note.delete().queueAfter(NOTICE_SECONDS, TimeUnit.SECONDS, ok -> {}, error -> {}), error -> {});
            }
        } catch (Exception e) {
            log.error("Auto-delete failed for a message in guild {}", event.getGuild().getId(), e);
        }
    }
}
