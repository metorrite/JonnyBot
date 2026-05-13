package com.younglings.bot.commands.signup;

import com.younglings.bot.signup.SignupRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@BService
public class SignupService {
    private static final Logger log = LoggerFactory.getLogger(SignupService.class);

    private final Map<Long, SignupSession> activeSignupsById = new HashMap<>();
    private final SignupRepository signupRepository;

    public SignupService(SignupRepository signupRepository) {
        this.signupRepository = signupRepository;
        loadOpenSignupsFromDatabase();
    }

    private void loadOpenSignupsFromDatabase() {
        List<SignupSession> signups = signupRepository.getOpenSignups();

        for (SignupSession signup : signups) {
            activeSignupsById.put(signup.signupId(), signup);

            log.info("Loaded signup {} '{}' for guild {} from database.",
                    signup.signupId(),
                    signup.title(),
                    signup.guildId());
        }
    }

    public SignupSession getSessionById(long signupId) {
        return activeSignupsById.get(signupId);
    }

    public SignupSession getSessionFromComponentId(String componentId) {
        long signupId = parseSignupId(componentId);
        return getSessionById(signupId);
    }

    public long parseSignupId(String componentId) {
        String[] parts = componentId.split(":");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid signup component ID: " + componentId);
        }

        return Long.parseLong(parts[1]);
    }

    public void createSession(
            Guild guild,
            TextChannel signupChannel,
            TextChannel adminChannel,
            String title,
            String notificationMessage,
            Integer maxSignupNumber,
            long createdByUserId
    ) {
        long signupId = signupRepository.createSignup(
                guild.getIdLong(),
                title,
                notificationMessage,
                maxSignupNumber,
                createdByUserId
        );

        SignupSession session = new SignupSession(
                signupId,
                guild.getIdLong(),
                title,
                notificationMessage,
                maxSignupNumber
        );

        activeSignupsById.put(signupId, session);

        signupChannel.sendMessageEmbeds(buildPublicEmbed(session).build())
                .addComponents(buildPublicActionRow(signupId))
                .queue(publicMessage -> {
                    signupRepository.saveMessage(
                            signupId,
                            guild.getIdLong(),
                            signupChannel.getIdLong(),
                            publicMessage.getIdLong(),
                            "PUBLIC"
                    );

                    adminChannel.sendMessageEmbeds(buildAdminEmbed(session).build())
                            .addComponents(
                                    buildAdminActionRowOne(signupId),
                                    buildAdminActionRowTwo(signupId)
                            )
                            .queue(adminMessage -> {
                                signupRepository.saveMessage(
                                        signupId,
                                        guild.getIdLong(),
                                        adminChannel.getIdLong(),
                                        adminMessage.getIdLong(),
                                        "ADMIN"
                                );

                                log.info("Created signup {} '{}' in guild {}", signupId, title, guild.getIdLong());
                            });
                });
    }

    public boolean isSignupActive(long signupId) {
        String status = signupRepository.getSignupStatus(signupId);
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public String togglePause(long signupId) {
        String currentStatus = signupRepository.getSignupStatus(signupId);

        if (currentStatus == null) {
            return null;
        }

        String newStatus = "ACTIVE".equalsIgnoreCase(currentStatus) ? "PAUSED" : "ACTIVE";

        signupRepository.setSignupStatus(signupId, newStatus);

        SignupSession session = activeSignupsById.get(signupId);
        if (session != null) {
            log.info("Toggled signup {} '{}' to {}", signupId, session.title(), newStatus);
        }

        return newStatus;
    }

    private void updateDeletedAdminPanels(Guild guild, long signupId, SignupSession session) {
        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        EmbedBuilder deletedEmbed = new EmbedBuilder()
                .setTitle(session.title() + " - Admin Controls")
                .setDescription("""
                        **SIGNUP FORM DELETED**

                        This signup has been closed.
                        Public signup panels were removed.
                        The queue was cleared.
                        """)
                .setFooter("Status: DELETED")
                .setColor(Color.DARK_GRAY);

        for (SignupMessage signupMessage : messages) {
            if (!"ADMIN".equalsIgnoreCase(signupMessage.messageType())) {
                continue;
            }

            TextChannel channel = guild.getTextChannelById(signupMessage.channelId());
            if (channel == null) {
                signupRepository.markMessageInactive(signupMessage.messageId());
                continue;
            }

            channel.retrieveMessageById(signupMessage.messageId())
                    .queue(
                            message -> message.editMessageEmbeds(deletedEmbed.build())
                                    .setComponents()
                                    .queue(),
                            failure -> signupRepository.markMessageInactive(signupMessage.messageId())
                    );
        }
    }

    public void deleteSignup(Guild guild, long signupId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return;

        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        for (SignupMessage signupMessage : messages) {
            TextChannel channel = guild.getTextChannelById(signupMessage.channelId());

            if (channel == null) {
                signupRepository.markMessageInactive(signupMessage.messageId());
                continue;
            }

            if ("PUBLIC".equalsIgnoreCase(signupMessage.messageType())) {
                channel.retrieveMessageById(signupMessage.messageId())
                        .queue(
                                message -> {
                                    EmbedBuilder closedEmbed = new EmbedBuilder()
                                            .setTitle(session.title())
                                            .setDescription("""
                                                    ***This signup form has been closed.***

                                                    Thanks to everyone who participated.
                                                    """)
                                            .setFooter("Status: CLOSED")
                                            .setColor(Color.DARK_GRAY);

                                    message.editMessageEmbeds(closedEmbed.build())
                                            .setComponents()
                                            .queue();

                                    signupRepository.markMessageInactive(signupMessage.messageId());
                                },
                                failure -> signupRepository.markMessageInactive(signupMessage.messageId())
                        );
            }
        }

        signupRepository.deleteSignupForAdminArchive(signupId);

        updateDeletedAdminPanels(guild, signupId, session);

        activeSignupsById.remove(signupId);

        log.info("Deleted signup {} '{}'", signupId, session.title());
    }

    public SignupMessage getFirstActivePublicMessage(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;

        return signupRepository.getFirstActivePublicMessage(signupId);
    }

    public boolean addUser(long signupId, long userId, String username) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return false;

        if (!isSignupActive(signupId)) {
            return false;
        }

        return signupRepository.addEntry(signupId, userId, username, userId);
    }

    public boolean addManualUser(long signupId, long userId, String username, long addedByUserId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return false;

        return signupRepository.addEntry(signupId, userId, username, addedByUserId);
    }

    public List<SignupSession> getVisibleSignups(long guildId) {
        return signupRepository.getVisibleSignups(guildId);
    }

    public List<SignupSession> searchVisibleSignups(long guildId, String query) {
        return signupRepository.searchVisibleSignups(guildId, query);
    }

    public String getSignupStatus(long signupId) {
        return signupRepository.getSignupStatus(signupId);
    }

    public void postSignupEmbed(Guild guild, TextChannel channel, long signupId, SignupPanelType panelType) {
        SignupSession session = activeSignupsById.get(signupId);

        if (session == null) {
            throw new IllegalArgumentException("No active signup found for ID: " + signupId);
        }

        if (panelType == SignupPanelType.ADMIN) {
            channel.sendMessageEmbeds(buildAdminEmbed(session).build())
                    .addComponents(
                            buildAdminActionRowOne(signupId),
                            buildAdminActionRowTwo(signupId)
                    )
                    .queue(message -> {
                        signupRepository.saveMessage(
                                signupId,
                                guild.getIdLong(),
                                channel.getIdLong(),
                                message.getIdLong(),
                                "ADMIN"
                        );

                        log.info("Posted additional ADMIN signup panel {} for signup {} in channel {}",
                                message.getIdLong(), signupId, channel.getIdLong());
                    });

            return;
        }

        channel.sendMessageEmbeds(buildPublicEmbed(session).build())
                .addComponents(buildPublicActionRow(signupId))
                .queue(message -> {
                    signupRepository.saveMessage(
                            signupId,
                            guild.getIdLong(),
                            channel.getIdLong(),
                            message.getIdLong(),
                            "PUBLIC"
                    );

                    log.info("Posted additional PUBLIC signup panel {} for signup {} in channel {}",
                            message.getIdLong(), signupId, channel.getIdLong());
                });
    }

    private ActionRow buildAdminActionRowOne(long signupId) {
        return ActionRow.of(
                Button.success("signup_next:" + signupId, "Next"),
                Button.primary("signup_notify:" + signupId, "Notify"),
                Button.secondary("signup_admin_add:" + signupId, "Add"),
                isSignupActive(signupId)
                        ? Button.secondary("signup_pause:" + signupId, "Pause")
                        : Button.success("signup_pause:" + signupId, "Resume")
        );
    }

    private ActionRow buildAdminActionRowTwo(long signupId) {
        return ActionRow.of(
                Button.secondary("signup_skip:" + signupId, "Skip"),
                Button.secondary("signup_remove:" + signupId, "Remove"),
                Button.danger("signup_clear:" + signupId, "Clear all"),
                Button.danger("signup_delete:" + signupId, "Delete")
        );
    }

    public SignupEntry getFirst(long signupId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return null;

        List<SignupEntry> entries = signupRepository.getEntries(signupId);
        if (entries.isEmpty()) return null;

        return entries.getFirst();
    }

    public SignupEntry next(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;

        return signupRepository.next(signupId);
    }

    public SignupEntry skip(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;

        return signupRepository.skipFirst(signupId);
    }

    public SignupEntry remove(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;

        return signupRepository.removeFirst(signupId);
    }

    public void clear(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return;

        signupRepository.clearEntries(signupId);
    }

    public void updateMessages(Guild guild, long signupId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return;

        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        for (SignupMessage signupMessage : messages) {
            TextChannel channel = guild.getTextChannelById(signupMessage.channelId());

            if (channel == null) {
                signupRepository.markMessageInactive(signupMessage.messageId());
                continue;
            }

            channel.retrieveMessageById(signupMessage.messageId())
                    .queue(
                            message -> {
                                if ("ADMIN".equalsIgnoreCase(signupMessage.messageType())) {
                                    message.editMessageEmbeds(buildAdminEmbed(session).build())
                                            .setComponents(
                                                    buildAdminActionRowOne(signupId),
                                                    buildAdminActionRowTwo(signupId)
                                            )
                                            .queue();
                                } else {
                                    message.editMessageEmbeds(buildPublicEmbed(session).build())
                                            .setComponents(buildPublicActionRow(signupId))
                                            .queue();
                                }
                            },
                            failure -> {
                                signupRepository.markMessageInactive(signupMessage.messageId());
                                log.warn("Signup message {} could not be retrieved and was marked inactive.",
                                        signupMessage.messageId());
                            }
                    );
        }
    }

    public boolean removeUser(long signupId, long userId) {
        if (!activeSignupsById.containsKey(signupId)) return false;

        return signupRepository.removeEntryByUserId(signupId, userId);
    }

    private ActionRow buildPublicActionRow(long signupId) {
        return ActionRow.of(
                Button.primary("signup_join:" + signupId, "Sign up"),
                Button.danger("signup_leave:" + signupId, "Leave Queue")
        );
    }

    public EmbedBuilder buildPublicEmbed(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;
        return new EmbedBuilder()
                .setTitle(session.title())
                .setDescription(buildQueueText(session))
                .setFooter(buildFooterText(session, status))
                .setColor(color);
    }

    public EmbedBuilder buildAdminEmbed(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;
        return new EmbedBuilder()
                .setTitle(session.title() + " - Admin Controls")
                .setDescription(buildQueueText(session))
                .setFooter(buildFooterText(session, status))
                .setColor(color);
    }

    private String buildFooterText(SignupSession session, String status) {
        return (session.maxSignups() == null
                ? "No signup limit"
                : "Limit: " + session.maxSignups())
                + " • Status: " + status;
    }

    private String buildQueueText(SignupSession session) {
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());

        StringBuilder description = new StringBuilder();
        description.append("────────────────────\n");

        if (entries.isEmpty()) {
            description.append("*Nobody is signed up yet.*\n");
        } else {
            int position = 1;
            for (SignupEntry entry : entries) {
                description.append("**")
                        .append(position++)
                        .append(".** ")
                        .append(entry.username())
                        .append(" — <@")
                        .append(entry.userId())
                        .append(">\n");
            }
        }

        description.append("────────────────────\n");
        return description.toString();
    }
}
