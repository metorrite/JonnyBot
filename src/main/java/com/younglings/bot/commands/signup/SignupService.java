package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;

import java.util.*;
import java.util.function.Consumer;

@BService
public class SignupService {
    private final Map<Long, SignupSession> sessions = new HashMap<>();

    public void createSession(
            Guild guild,
            TextChannel signupChannel,
            TextChannel adminChannel,
            String title,
            String notificationMessage,
            Integer maxSignupNumber,
            Consumer<Void> afterCreate
    ) {
        SignupSession session = new SignupSession(
                title,
                notificationMessage,
                maxSignupNumber == null || maxSignupNumber <= 0 ? null : maxSignupNumber,
                signupChannel.getIdLong(),
                adminChannel.getIdLong()
        );

        sessions.put(guild.getIdLong(), session);

        signupChannel.sendMessageEmbeds(buildPublicEmbed(session).build())
                .addComponents(ActionRow.of(Button.primary("signup_join", "Sign up")))
                .queue(publicMessage -> {
                    session.setPublicMessageId(publicMessage.getIdLong());

                    adminChannel.sendMessageEmbeds(buildAdminEmbed(session).build())
                            .addComponents(ActionRow.of(
                                    Button.success("signup_next", "Next"),
                                    Button.primary("signup_notify", "Notify"),
                                    Button.secondary("signup_skip", "Skip"),
                                    Button.danger("signup_remove", "Remove"),
                                    Button.danger("signup_clear", "Clear all")
                            ))
                            .queue(adminMessage -> {
                                session.setAdminMessageId(adminMessage.getIdLong());
                                afterCreate.accept(null);
                            });
                });
    }

    public SignupSession getSession(long guildId) {
        return sessions.get(guildId);
    }

    public boolean addUser(long guildId, long userId, String username) {
        SignupSession session = sessions.get(guildId);
        if (session == null) return false;

        if (session.containsUser(userId) || session.containsUsername(username)) {
            return false;
        }

        if (session.getMaxSignups() != null && session.getEntries().size() >= session.getMaxSignups()) {
            return false;
        }

        session.getEntries().add(new SignupEntry(userId, username));
        return true;
    }

    public SignupEntry next(long guildId) {
        SignupSession session = sessions.get(guildId);
        if (session == null || session.getEntries().isEmpty()) return null;

        SignupEntry removed = session.getEntries().removeFirst();
        return session.getEntries().peekFirst();
    }

    public SignupEntry skip(long guildId) {
        SignupSession session = sessions.get(guildId);
        if (session == null || session.getEntries().size() < 2) return null;

        SignupEntry first = session.getEntries().removeFirst();
        SignupEntry second = session.getEntries().removeFirst();

        session.getEntries().addFirst(first);
        session.getEntries().addFirst(second);

        return second;
    }

    public SignupEntry remove(long guildId) {
        SignupSession session = sessions.get(guildId);
        if (session == null || session.getEntries().isEmpty()) return null;

        session.getEntries().removeFirst();
        return session.getEntries().peekFirst();
    }

    public void clear(long guildId) {
        SignupSession session = sessions.get(guildId);
        if (session != null) {
            session.getEntries().clear();
        }
    }

    public void updateMessages(Guild guild) {
        SignupSession session = sessions.get(guild.getIdLong());
        if (session == null) return;

        TextChannel publicChannel = guild.getTextChannelById(session.getPublicChannelId());
        TextChannel adminChannel = guild.getTextChannelById(session.getAdminChannelId());

        if (publicChannel != null) {
            publicChannel.retrieveMessageById(session.getPublicMessageId())
                    .queue(message -> message.editMessageEmbeds(buildPublicEmbed(session).build()).queue());
        }

        if (adminChannel != null) {
            adminChannel.retrieveMessageById(session.getAdminMessageId())
                    .queue(message -> message.editMessageEmbeds(buildAdminEmbed(session).build()).queue());
        }
    }

    public EmbedBuilder buildPublicEmbed(SignupSession session) {
        return new EmbedBuilder()
                .setTitle(session.getTitle())
                .setDescription(buildQueueText(session))
                .setFooter(session.getMaxSignups() == null
                        ? "No signup limit"
                        : "Limit: " + session.getMaxSignups());
    }

    public EmbedBuilder buildAdminEmbed(SignupSession session) {
        return new EmbedBuilder()
                .setTitle(session.getTitle() + " - Admin Controls")
                .setDescription(buildQueueText(session));
    }

    public SignupEntry getFirst(long guildId) {
        SignupSession session = sessions.get(guildId);

        if (session == null || session.getEntries().isEmpty()) {
            return null;
        }

        return session.getEntries().peekFirst();
    }

    private String buildQueueText(SignupSession session) {
        if (session.getEntries().isEmpty()) {
            return "*Nobody is signed up yet.*";
        }

        StringBuilder builder = new StringBuilder();

        int index = 1;
        for (SignupEntry entry : session.getEntries()) {
            builder.append("**")
                    .append(index++)
                    .append(".** ")
                    .append(entry.username())
                    .append(" — <@")
                    .append(entry.userId())
                    .append(">\n");
        }

        return builder.toString();
    }
}
