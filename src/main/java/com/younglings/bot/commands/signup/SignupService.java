package com.younglings.bot.commands.signup;

import com.younglings.bot.signup.SignupRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
            log.info("Loaded {} signup {} '{}' for guild {} from database.",
                    signup.type(), signup.signupId(), signup.title(), signup.guildId());
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

    // --- Creation ---

    public void createQueueSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                    String title, String notificationMessage, Integer maxSignups,
                                    long createdByUserId) {
        long signupId = signupRepository.createSignup(
                guild.getIdLong(), title, SignupType.QUEUE,
                notificationMessage, null, maxSignups, createdByUserId, null
        );

        SignupSession session = new SignupSession(
                signupId, guild.getIdLong(), title, notificationMessage,
                maxSignups, SignupType.QUEUE, null, null
        );

        activeSignupsById.put(signupId, session);
        postNewPanels(guild, signupChannel, adminChannel, session);
        log.info("Created QUEUE signup {} '{}' in guild {}", signupId, title, guild.getIdLong());
    }

    public void createGroupSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                    String title, long createdByUserId) {
        String roleName = title.length() > 100 ? title.substring(0, 100) : title;

        guild.createRole()
                .setName(roleName)
                .setMentionable(true)
                .queue(role -> {
                    long roleId = role.getIdLong();

                    long signupId = signupRepository.createSignup(
                            guild.getIdLong(), title, SignupType.GROUP,
                            null, null, null, createdByUserId, roleId
                    );

                    SignupSession session = new SignupSession(
                            signupId, guild.getIdLong(), title, null,
                            null, SignupType.GROUP, null, roleId
                    );

                    activeSignupsById.put(signupId, session);
                    postNewPanels(guild, signupChannel, adminChannel, session);
                    log.info("Created GROUP signup {} '{}' with role {} in guild {}",
                            signupId, title, roleId, guild.getIdLong());

                }, failure -> log.error("Failed to create Discord role for GROUP signup '{}'", title, failure));
    }

    public void createSubmissionSession(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                         String title, List<SubmissionField> fields, Integer maxEntries,
                                         long createdByUserId) {
        String serializedFields = SubmissionField.serialize(fields);

        long signupId = signupRepository.createSignup(
                guild.getIdLong(), title, SignupType.SUBMISSION,
                null, serializedFields, maxEntries, createdByUserId, null
        );

        SignupSession session = new SignupSession(
                signupId, guild.getIdLong(), title, null,
                maxEntries, SignupType.SUBMISSION, serializedFields, null
        );

        activeSignupsById.put(signupId, session);
        postNewPanels(guild, signupChannel, adminChannel, session);
        log.info("Created SUBMISSION signup {} '{}' with {} field(s) in guild {}",
                signupId, title, fields.size(), guild.getIdLong());
    }

    private void postNewPanels(Guild guild, TextChannel signupChannel, TextChannel adminChannel,
                                SignupSession session) {
        long signupId = session.signupId();

        signupChannel.sendMessageEmbeds(buildPublicEmbed(session).build())
                .addComponents(buildPublicActionRow(session))
                .queue(publicMessage -> {
                    signupRepository.saveMessage(signupId, guild.getIdLong(),
                            signupChannel.getIdLong(), publicMessage.getIdLong(), "PUBLIC");

                    adminChannel.sendMessageEmbeds(buildAdminEmbed(session).build())
                            .addComponents(buildAdminActionRows(session))
                            .queue(adminMessage -> signupRepository.saveMessage(
                                    signupId, guild.getIdLong(),
                                    adminChannel.getIdLong(), adminMessage.getIdLong(), "ADMIN"));
                });
    }

    // --- Status ---

    public boolean isSignupActive(long signupId) {
        String status = signupRepository.getSignupStatus(signupId);
        return "ACTIVE".equalsIgnoreCase(status);
    }

    public String togglePause(long signupId) {
        String currentStatus = signupRepository.getSignupStatus(signupId);
        if (currentStatus == null) return null;

        String newStatus = "ACTIVE".equalsIgnoreCase(currentStatus) ? "PAUSED" : "ACTIVE";
        signupRepository.setSignupStatus(signupId, newStatus);

        SignupSession session = activeSignupsById.get(signupId);
        if (session != null) {
            log.info("Toggled signup {} '{}' to {}", signupId, session.title(), newStatus);
        }

        return newStatus;
    }

    // --- Entry management ---

    public boolean addUser(Guild guild, long signupId, long userId, String rsn, String submissionValue) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return false;
        if (!isSignupActive(signupId)) return false;

        if (session.maxSignups() != null
                && signupRepository.getEntryCount(signupId) >= session.maxSignups()) {
            return false;
        }

        boolean added = signupRepository.addEntry(signupId, userId, rsn, submissionValue, userId, session.type());

        if (added && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            assignGroupRole(guild, session.groupRoleId(), userId);
        }

        return added;
    }

    public boolean addManualUser(Guild guild, long signupId, long userId, String rsn,
                                  String submissionValue, long addedByUserId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return false;

        if (session.maxSignups() != null
                && signupRepository.getEntryCount(signupId) >= session.maxSignups()) {
            return false;
        }

        boolean added = signupRepository.addEntry(signupId, userId, rsn, submissionValue, addedByUserId, session.type());

        if (added && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            assignGroupRole(guild, session.groupRoleId(), userId);
        }

        return added;
    }

    public boolean removeUser(Guild guild, long signupId, long userId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return false;

        boolean removed = signupRepository.removeEntryByUserId(signupId, userId);

        if (removed && session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            removeGroupRole(guild, session.groupRoleId(), userId);
        }

        return removed;
    }

    public List<SignupEntry> getEntries(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return List.of();
        return signupRepository.getEntries(signupId);
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

    // --- Queue-only operations ---

    public SignupEntry getFirst(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;
        List<SignupEntry> entries = signupRepository.getEntries(signupId);
        return entries.isEmpty() ? null : entries.getFirst();
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

    public void clear(Guild guild, long signupId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return;

        if (session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            Role role = guild.getRoleById(session.groupRoleId());
            if (role != null) {
                for (SignupEntry entry : signupRepository.getEntries(signupId)) {
                    guild.removeRoleFromMember(UserSnowflake.fromId(entry.userId()), role).queue(
                            null, failure -> log.warn("Could not remove group role from user {}", entry.userId()));
                }
            }
        }

        signupRepository.clearEntries(signupId);
    }

    // --- Random pick ---

    /** Picks a random entry without removing it or closing the signup. */
    public SignupEntry pickRandom(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;

        List<SignupEntry> entries = signupRepository.getEntries(signupId);
        if (entries.isEmpty()) return null;

        return entries.get(ThreadLocalRandom.current().nextInt(entries.size()));
    }

    /** Builds the winner announcement embed posted in the public channel. */
    public EmbedBuilder buildWinnerEmbed(SignupSession session, SignupEntry winner) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("🎉 " + session.title() + " — Winner!")
                .setColor(new Color(255, 215, 0));

        switch (session.type()) {
            case GROUP -> eb.setDescription(
                    "**<@" + winner.userId() + ">** has been selected!\n\nCongratulations! 🎊");

            case SUBMISSION -> {
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                List<String> values = SubmissionField.parseValues(winner.submissionValue());

                StringBuilder desc = new StringBuilder(
                        "**<@" + winner.userId() + ">** was randomly selected!\n\n");

                String firstImageUrl = null;

                for (int i = 0; i < fields.size(); i++) {
                    SubmissionField field = fields.get(i);
                    String value = i < values.size() ? values.get(i) : "";

                    if (value.isBlank() && !field.required()) continue;

                    if (SubmissionField.TYPE_IMAGE.equals(field.type())) {
                        desc.append("**").append(field.label()).append(":** ")
                                .append("[View Image](").append(value).append(")\n");
                        if (firstImageUrl == null && !value.isBlank()) firstImageUrl = value;
                    } else if (SubmissionField.TYPE_LINK.equals(field.type())) {
                        desc.append("**").append(field.label()).append(":** ")
                                .append("[Link](").append(value).append(")\n");
                    } else {
                        desc.append("**").append(field.label()).append(":** ")
                                .append(value.isBlank() ? "*(not provided)*" : value).append("\n");
                    }
                }

                desc.append("\nCongratulations! 🎊");
                eb.setDescription(desc.toString());

                // Show the first image field inline in the winner embed
                if (firstImageUrl != null) eb.setImage(firstImageUrl);
            }

            default -> eb.setDescription("**<@" + winner.userId() + ">** was selected! 🎊");
        }

        return eb;
    }

    // --- Delete ---

    public void deleteSignup(Guild guild, long signupId) {
        SignupSession session = activeSignupsById.get(signupId);
        if (session == null) return;

        if (session.type() == SignupType.GROUP && session.groupRoleId() != null) {
            Role role = guild.getRoleById(session.groupRoleId());
            if (role != null) {
                for (SignupEntry entry : signupRepository.getEntries(signupId)) {
                    guild.removeRoleFromMember(UserSnowflake.fromId(entry.userId()), role).queue(
                            null, failure -> log.warn("Could not remove group role from user {} on delete", entry.userId()));
                }
                role.delete().queue(
                        success -> log.info("Deleted group role {} for signup {}", session.groupRoleId(), signupId),
                        failure -> log.warn("Could not delete group role {} for signup {}", session.groupRoleId(), signupId));
            }
        }

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

    private void updateDeletedAdminPanels(Guild guild, long signupId, SignupSession session) {
        List<SignupMessage> messages = signupRepository.getActiveMessages(signupId);

        EmbedBuilder deletedEmbed = new EmbedBuilder()
                .setTitle(session.title() + " - Admin Controls")
                .setDescription("""
                        **SIGNUP FORM DELETED**

                        This signup has been closed.
                        Public signup panels were removed.
                        The entries were cleared.
                        """)
                .setFooter("Status: DELETED")
                .setColor(Color.DARK_GRAY);

        for (SignupMessage signupMessage : messages) {
            if (!"ADMIN".equalsIgnoreCase(signupMessage.messageType())) continue;

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

    // --- Panel posting ---

    public SignupMessage getFirstActivePublicMessage(long signupId) {
        if (!activeSignupsById.containsKey(signupId)) return null;
        return signupRepository.getFirstActivePublicMessage(signupId);
    }

    public void postSignupEmbed(Guild guild, TextChannel channel, long signupId, SignupPanelType panelType) {
        SignupSession session = activeSignupsById.get(signupId);

        if (session == null) {
            throw new IllegalArgumentException("No active signup found for ID: " + signupId);
        }

        if (panelType == SignupPanelType.ADMIN) {
            channel.sendMessageEmbeds(buildAdminEmbed(session).build())
                    .addComponents(buildAdminActionRows(session))
                    .queue(message -> {
                        signupRepository.saveMessage(signupId, guild.getIdLong(),
                                channel.getIdLong(), message.getIdLong(), "ADMIN");
                        log.info("Posted additional ADMIN panel {} for signup {} in channel {}",
                                message.getIdLong(), signupId, channel.getIdLong());
                    });
            return;
        }

        channel.sendMessageEmbeds(buildPublicEmbed(session).build())
                .addComponents(buildPublicActionRow(session))
                .queue(message -> {
                    signupRepository.saveMessage(signupId, guild.getIdLong(),
                            channel.getIdLong(), message.getIdLong(), "PUBLIC");
                    log.info("Posted additional PUBLIC panel {} for signup {} in channel {}",
                            message.getIdLong(), signupId, channel.getIdLong());
                });
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
                                            .setComponents(buildAdminActionRows(session))
                                            .queue();
                                } else {
                                    message.editMessageEmbeds(buildPublicEmbed(session).build())
                                            .setComponents(buildPublicActionRow(session))
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

    // --- Embed builders ---

    public EmbedBuilder buildPublicEmbed(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;
        return new EmbedBuilder()
                .setTitle(session.title())
                .setDescription(buildEntriesText(session))
                .setFooter(buildFooterText(session, status))
                .setColor(color);
    }

    public EmbedBuilder buildAdminEmbed(SignupSession session) {
        String status = signupRepository.getSignupStatus(session.signupId());
        Color color = "PAUSED".equalsIgnoreCase(status) ? Color.RED : Color.GREEN;
        return new EmbedBuilder()
                .setTitle(session.title() + " - Admin Controls")
                .setDescription(buildEntriesText(session))
                .setFooter(buildFooterText(session, status))
                .setColor(color);
    }

    private String buildEntriesText(SignupSession session) {
        return switch (session.type()) {
            case QUEUE      -> buildQueueText(session);
            case GROUP      -> buildGroupText(session);
            case SUBMISSION -> buildSubmissionText(session);
        };
    }

    private String buildQueueText(SignupSession session) {
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());
        StringBuilder sb = new StringBuilder("────────────────────\n");

        if (entries.isEmpty()) {
            sb.append("*Nobody is signed up yet.*\n");
        } else {
            int position = 1;
            for (SignupEntry entry : entries) {
                sb.append("**").append(position++).append(".** ")
                        .append(entry.username())
                        .append(" — <@").append(entry.userId()).append(">\n");
            }
        }

        sb.append("────────────────────\n");
        return sb.toString();
    }

    private String buildGroupText(SignupSession session) {
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());
        StringBuilder sb = new StringBuilder("────────────────────\n");

        if (entries.isEmpty()) {
            sb.append("*No members yet.*\n");
        } else {
            for (SignupEntry entry : entries) {
                sb.append("• <@").append(entry.userId()).append(">\n");
            }
        }

        sb.append("────────────────────\n");
        return sb.toString();
    }

    private String buildSubmissionText(SignupSession session) {
        List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
        List<SignupEntry> entries = signupRepository.getEntries(session.signupId());

        StringBuilder sb = new StringBuilder();
        if (!fields.isEmpty()) {
            sb.append("**Fields:** ");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(" • ");
                sb.append(fields.get(i).label());
                String meta = buildFieldMeta(fields.get(i));
                if (!meta.isEmpty()) sb.append(" *(").append(meta).append(")*");
            }
            sb.append("\n");
        }

        sb.append("────────────────────\n");

        if (entries.isEmpty()) {
            sb.append("*No submissions yet.*\n");
        } else {
            int position = 1;
            for (SignupEntry entry : entries) {
                List<String> values = SubmissionField.parseValues(entry.submissionValue());

                sb.append("**").append(position++).append(".** <@").append(entry.userId()).append(">\n");

                for (int i = 0; i < fields.size(); i++) {
                    SubmissionField field = fields.get(i);
                    String value = i < values.size() ? values.get(i) : "";

                    if (value.isBlank() && !field.required()) continue;

                    sb.append("   **").append(field.label()).append(":** ");

                    if (SubmissionField.TYPE_IMAGE.equals(field.type())) {
                        sb.append("[View Image](").append(value).append(")");
                    } else if (SubmissionField.TYPE_LINK.equals(field.type())) {
                        sb.append("[Link](").append(value).append(")");
                    } else {
                        sb.append(value.isBlank() ? "*(not provided)*" : value);
                    }
                    sb.append("\n");
                }
            }
        }

        sb.append("────────────────────\n");
        return sb.toString();
    }

    private String buildFieldMeta(SubmissionField field) {
        boolean nonText = !SubmissionField.TYPE_TEXT.equals(field.type());
        if (nonText && !field.required()) return field.type().toLowerCase() + ", optional";
        if (nonText) return field.type().toLowerCase();
        if (!field.required()) return "optional";
        return "";
    }

    private String buildFooterText(SignupSession session, String status) {
        String limitPart = session.maxSignups() == null ? "No limit" : "Limit: " + session.maxSignups();
        return limitPart + " • Status: " + status + " • Type: " + session.type().name();
    }

    // --- Action row builders ---

    private ActionRow buildPublicActionRow(SignupSession session) {
        long id = session.signupId();
        return switch (session.type()) {
            case QUEUE -> ActionRow.of(
                    Button.primary("signup_join:" + id, "Sign up"),
                    Button.danger("signup_leave:" + id, "Leave Queue")
            );
            case GROUP -> ActionRow.of(
                    Button.success("signup_join:" + id, "Join Group"),
                    Button.danger("signup_leave:" + id, "Leave Group")
            );
            case SUBMISSION -> ActionRow.of(
                    Button.primary("signup_join:" + id, "Submit"),
                    Button.danger("signup_leave:" + id, "Remove Submission")
            );
        };
    }

    private List<ActionRow> buildAdminActionRows(SignupSession session) {
        long id = session.signupId();
        Button pauseResume = isSignupActive(id)
                ? Button.secondary("signup_pause:" + id, "Pause")
                : Button.success("signup_pause:" + id, "Resume");

        return switch (session.type()) {
            case QUEUE -> List.of(
                    ActionRow.of(
                            Button.success("signup_next:" + id, "Next"),
                            Button.primary("signup_notify:" + id, "Notify"),
                            Button.secondary("signup_admin_add:" + id, "Add"),
                            pauseResume
                    ),
                    ActionRow.of(
                            Button.secondary("signup_skip:" + id, "Skip"),
                            Button.secondary("signup_remove:" + id, "Remove"),
                            Button.danger("signup_clear:" + id, "Clear all"),
                            Button.danger("signup_delete:" + id, "Delete")
                    )
            );
            case GROUP -> List.of(
                    ActionRow.of(
                            Button.primary("signup_notify_all:" + id, "Notify All"),
                            Button.success("signup_pick_winner:" + id, "Pick Winner"),
                            Button.secondary("signup_admin_add:" + id, "Add Member"),
                            pauseResume
                    ),
                    ActionRow.of(
                            Button.secondary("signup_admin_remove:" + id, "Remove Member"),
                            Button.danger("signup_clear:" + id, "Clear all"),
                            Button.danger("signup_delete:" + id, "Delete")
                    )
            );
            case SUBMISSION -> List.of(
                    ActionRow.of(
                            Button.secondary("signup_admin_add:" + id, "Add Entry"),
                            Button.secondary("signup_admin_remove:" + id, "Remove Entry"),
                            pauseResume,
                            Button.danger("signup_delete:" + id, "Delete")
                    ),
                    ActionRow.of(
                            Button.success("signup_pick_random:" + id, "Pick Random"),
                            Button.danger("signup_clear:" + id, "Clear all")
                    )
            );
        };
    }

    // --- Role helpers ---

    private void assignGroupRole(Guild guild, long roleId, long userId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) {
            log.warn("Group role {} not found in guild {}", roleId, guild.getIdLong());
            return;
        }
        guild.addRoleToMember(UserSnowflake.fromId(userId), role).queue(
                null,
                failure -> log.warn("Could not assign group role {} to user {}", roleId, userId, failure));
    }

    private void removeGroupRole(Guild guild, long roleId, long userId) {
        Role role = guild.getRoleById(roleId);
        if (role == null) return;
        guild.removeRoleFromMember(UserSnowflake.fromId(userId), role).queue(
                null,
                failure -> log.warn("Could not remove group role {} from user {}", roleId, userId, failure));
    }
}
