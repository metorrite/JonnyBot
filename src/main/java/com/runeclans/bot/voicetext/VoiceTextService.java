package com.runeclans.bot.voicetext;

import com.runeclans.bot.voicetext.VoiceTextRepository.Config;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * Pairs a voice channel with a text channel: while a person is in the voice channel they can see and write in its text channel, and when they leave that
 * access is taken away again. Only access JonnyBot added itself is ever removed (a person who already had an overwrite of their own there is left alone).
 * Optionally it says who joined and left, and clears the text channel's recent messages when the voice channel empties. Everything is off until the
 * server turns it on, and the pairs are chosen on the dashboard.
 */
@BService
public class VoiceTextService {
    private static final Logger log = LoggerFactory.getLogger(VoiceTextService.class);

    /** What a person in the voice channel gets in the text channel. */
    public static final EnumSet<Permission> ACCESS = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY);
    /** A purge looks at this many of the newest messages, none older than two weeks (Discord's bulk-delete window), and never a pinned one. */
    static final int PURGE_LOOKBACK = 100;

    private final VoiceTextRepository repository;

    public VoiceTextService(VoiceTextRepository repository) {
        this.repository = repository;
    }

    /** Whether JonnyBot can change who sees this text channel (and talk in it). */
    public static boolean canManage(Guild guild, TextChannel channel) {
        return guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.MESSAGE_SEND);
    }

    public void joined(Guild guild, Member member, AudioChannel voice) {
        Config config = repository.config(guild.getIdLong());
        if (!config.enabled()) return;
        TextChannel text = textFor(guild, voice);
        if (text == null || !canManage(guild, text)) return;

        PermissionOverride existing = text.getPermissionOverride(member);
        if (existing == null) {
            repository.addGrant(guild.getIdLong(), text.getIdLong(), member.getIdLong());
            text.upsertPermissionOverride(member).grant(ACCESS).reason("Joined " + voice.getName()).queue(ok -> {}, error -> {
                repository.removeGrant(guild.getIdLong(), text.getIdLong(), member.getIdLong());
                log.warn("Couldn't give {} access to #{} in guild {}: {}", member.getId(), text.getName(), guild.getId(), error.getMessage());
            });
        }
        // an overwrite someone set by hand is left exactly as it is, so there is nothing of ours to remove later
        if (config.announce()) say(text, "➡️ " + member.getEffectiveName() + " joined " + voice.getName() + ".");
    }

    public void left(Guild guild, Member member, AudioChannel voice) {
        Config config = repository.config(guild.getIdLong());
        TextChannel text = textFor(guild, voice);
        if (text == null) return;

        // taking access away is done even when the feature was switched off since, so nobody keeps a view they only had for being in voice
        if (repository.removeGrant(guild.getIdLong(), text.getIdLong(), member.getIdLong())) {
            PermissionOverride override = text.getPermissionOverride(member);
            if (override != null) override.delete().reason("Left " + voice.getName()).queue(ok -> {}, error -> log.warn("Couldn't remove {}'s access to #{}: {}", member.getId(), text.getName(), error.getMessage()));
        }
        if (!config.enabled()) return;
        if (config.announce()) say(text, "⬅️ " + member.getEffectiveName() + " left " + voice.getName() + ".");
        if (config.purgeWhenEmpty() && voice.getMembers().stream().noneMatch(m -> !m.getUser().isBot())) purge(text);
    }

    /** Takes the access JonnyBot added in a text channel away from everyone (a pair was removed, or the text channel is being unlinked). */
    public void releaseAll(Guild guild, long textChannelId) {
        TextChannel text = guild.getTextChannelById(textChannelId);
        for (long userId : repository.grantsIn(guild.getIdLong(), textChannelId)) {
            repository.removeGrant(guild.getIdLong(), textChannelId, userId);
            Member member = guild.getMemberById(userId);
            if (text == null || member == null) continue;
            PermissionOverride override = text.getPermissionOverride(member);
            if (override != null) override.delete().reason("Voice text link removed").queue(ok -> {}, error -> {});
        }
    }

    private TextChannel textFor(Guild guild, AudioChannel voice) {
        return repository.textFor(guild.getIdLong(), voice.getIdLong()).map(guild::getTextChannelById).orElse(null);
    }

    private static void say(TextChannel text, String line) {
        if (text.canTalk()) text.sendMessage(line).setAllowedMentions(List.of()).queue(ok -> {}, error -> {});
    }

    /** Deletes the linked text channel's newest unpinned messages from the last two weeks. The channel itself is never touched. */
    private static void purge(TextChannel text) {
        if (!text.getGuild().getSelfMember().hasPermission(text, Permission.MESSAGE_MANAGE, Permission.MESSAGE_HISTORY)) return;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(14);
        text.getHistory().retrievePast(PURGE_LOOKBACK).queue(messages -> {
            List<Message> doomed = messages.stream().filter(m -> !m.isPinned() && m.getTimeCreated().isAfter(cutoff)).toList();
            if (doomed.isEmpty()) return;
            text.purgeMessages(doomed);
        }, error -> log.warn("Couldn't read #{} to clear it: {}", text.getName(), error.getMessage()));
    }
}
