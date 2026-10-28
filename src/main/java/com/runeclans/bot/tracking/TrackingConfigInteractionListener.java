package com.runeclans.bot.tracking;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.DiscordLinks;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runescape.SkillEmojiCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The Tracking panel — {@code /configure}'s UI for the announcement system approved in the event
 * taxonomy: enable/disable each of {@link TrackingGroup}'s 18 groups and pick where each one posts.
 * A completely separate listener from {@link com.runeclans.bot.commands.configure.ConfigureInteractionListener}
 * (still all {@code configure_tracking_}-prefixed, still gated the same way) purely because that
 * class was already sizeable before this — {@code buildPanel()}'s entry button is the only thing that
 * lives over there.
 * <p>
 * Three screens: the main panel lists only the 4 {@link TrackingGroup#source()} sections (each its own
 * button, with an "enabled/total" count) — it used to also inline every individual group's button here,
 * but at 18 groups that blew past Discord's 40-component-per-message cap outright (crashed in
 * production at 42). Clicking a section opens that source's screen, which has the bulk actions (enable
 * all / disable all / add a destination to everything here / clear everything here) that already lived
 * there, plus — moved down from the main panel — one button per group in that section (green = enabled,
 * gray = disabled) that opens straight to that group's own screen: an enable toggle, current
 * destinations (removable via a dropdown), a native channel picker to add a regular channel or thread,
 * and an "Add by link" modal fallback for a forum thread Discord's channel picker doesn't surface
 * (older/archived threads in particular — untested at the time this was built, hence the fallback
 * existing at all rather than being added later).
 */
@BService
public class TrackingConfigInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackingConfigInteractionListener.class);

    private final TrackingService trackingService;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final TrackingIconCatalog trackingIconCatalog;
    private final TrackingSendNowService sendNowService;

    // Send Now can take minutes (a section's version polls the whole roster), so it runs off the event
    // thread and edits the deferred reply when done. The in-flight set is what stops a second click
    // from starting a duplicate run — two overlapping roster polls would just double the request rate.
    private final ExecutorService sendNowExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "tracking-send-now");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<String> sendNowInFlight = ConcurrentHashMap.newKeySet();

    public TrackingConfigInteractionListener(TrackingService trackingService, SkillEmojiCatalog skillEmojiCatalog,
                                              TrackingIconCatalog trackingIconCatalog, TrackingSendNowService sendNowService) {
        this.trackingService = trackingService;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
        this.sendNowService = sendNowService;
    }

    /** Just the two weekly digests — real windowed data with no meaningful fake sample, so {@link #doSendTestPosts} skips them entirely. {@link TrackingGroup#CLAN_REPORT} is a computed report too, but easy enough to fake convincingly, so it gets a sample like everything else. */
    private static boolean isWeeklyDigestGroup(TrackingGroup group) {
        return group == TrackingGroup.WEEKLY_JOINS_LEAVES || group == TrackingGroup.WEEKLY_CITADEL_REPORT;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_tracking")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.you_need_the_administrator_permission_to"));
                return;
            }

            String[] parts = id.split(":", 3);
            switch (parts[0]) {
                case "configure_tracking_main" -> event.editComponents(List.of(buildMainPanel(guild.getIdLong()))).useComponentsV2(true).queue();
                case "configure_tracking_view_group" -> {
                    TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                    event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
                }
                case "configure_tracking_source" -> event.editComponents(List.of(buildSourceEditorPanel(guild.getIdLong(), decodeSource(parts[1])))).useComponentsV2(true).queue();
                case "configure_tracking_source_enable_all" -> {
                    String source = decodeSource(parts[1]);
                    trackingService.setEnabledForSource(guild.getIdLong(), source, true);
                    event.editComponents(List.of(buildSourceEditorPanel(guild.getIdLong(), source))).useComponentsV2(true).queue();
                }
                case "configure_tracking_source_disable_all" -> {
                    String source = decodeSource(parts[1]);
                    trackingService.setEnabledForSource(guild.getIdLong(), source, false);
                    event.editComponents(List.of(buildSourceEditorPanel(guild.getIdLong(), source))).useComponentsV2(true).queue();
                }
                case "configure_tracking_source_clear_confirm" -> event.editComponents(List.of(buildSourceClearConfirmPanel(guild.getIdLong(), decodeSource(parts[1])))).useComponentsV2(true).queue();
                case "configure_tracking_source_clear_go" -> {
                    String source = decodeSource(parts[1]);
                    trackingService.clearAllDestinationsInSource(guild.getIdLong(), source);
                    event.editComponents(List.of(buildSourceEditorPanel(guild.getIdLong(), source))).useComponentsV2(true).queue();
                }
                case "configure_tracking_toggle" -> {
                    TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                    boolean currentlyEnabled = trackingService.isEnabled(guild.getIdLong(), group);
                    trackingService.setEnabled(guild.getIdLong(), group, !currentlyEnabled);
                    event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
                }
                case "configure_tracking_add_thread" -> doAddThreadPrompt(event, parts[1]);
                case "configure_tracking_source_add_thread" -> doAddSourceThreadPrompt(event, parts[1]);
                case "configure_tracking_test_send" -> doSendTestPosts(event, guild);
                case "configure_tracking_test_clear" -> doClearTestPosts(event, guild);
                case "configure_tracking_report_send" -> doSendGroupNow(event, guild, TrackingGroup.valueOf(parts[1]));
                case "configure_tracking_source_send" -> doSendSectionNow(event, guild, decodeSource(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in tracking config button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_tracking")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.you_need_the_administrator_permission_to"));
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "configure_tracking_remove_dest" -> {
                    TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                    long destinationId = Long.parseLong(event.getValues().getFirst());
                    trackingService.removeDestination(guild.getIdLong(), destinationId);
                    event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in tracking config select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_tracking")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.you_need_the_administrator_permission_to"));
                return;
            }
            if (event.getValues().isEmpty()) return;

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "configure_tracking_add_channel" -> {
                    TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                    trackingService.addDestination(guild.getIdLong(), group, event.getValues().getFirst().getIdLong());
                    event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
                }
                case "configure_tracking_source_add_channel" -> {
                    String source = decodeSource(parts[1]);
                    trackingService.addDestinationToSource(guild.getIdLong(), source, event.getValues().getFirst().getIdLong());
                    event.editComponents(List.of(buildSourceEditorPanel(guild.getIdLong(), source))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in tracking config entity select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("configure_tracking")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.you_need_the_administrator_permission_to"));
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "configure_tracking_thread_modal" -> handleAddThreadModal(event, guild, TrackingGroup.valueOf(parts[1]));
                case "configure_tracking_source_thread_modal" -> handleAddSourceThreadModal(event, guild, decodeSource(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in tracking config modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void doAddThreadPrompt(ButtonInteractionEvent event, String groupName) {
        TextInput linkInput = TextInput.create("thread_link", TextInputStyle.SHORT)
                .setPlaceholder(Text.of(event, "tracking.paste_a_thread_channel_link_or"))
                .setRequired(true)
                .build();

        Modal modal = Modal.create("configure_tracking_thread_modal:" + groupName, "Add Forum Thread")
                .addComponents(Label.of("Thread Link or ID", linkInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleAddThreadModal(ModalInteractionEvent event, Guild guild, TrackingGroup group) {
        String raw = event.getValue("thread_link").getAsString();
        Long channelId = DiscordLinks.parseChannelId(raw);

        if (channelId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.couldn_t_find_a_channel_thread"));
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.that_doesn_t_look_like_a"));
            return;
        }

        trackingService.addDestination(guild.getIdLong(), group, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "tracking.added_as_a_destination_for", channelId, group.displayName()));
    }

    private void doAddSourceThreadPrompt(ButtonInteractionEvent event, String encodedSource) {
        TextInput linkInput = TextInput.create("thread_link", TextInputStyle.SHORT)
                .setPlaceholder(Text.of(event, "tracking.paste_a_thread_channel_link_or"))
                .setRequired(true)
                .build();

        Modal modal = Modal.create("configure_tracking_source_thread_modal:" + encodedSource, "Add Forum Thread")
                .addComponents(Label.of("Thread Link or ID", linkInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleAddSourceThreadModal(ModalInteractionEvent event, Guild guild, String source) {
        String raw = event.getValue("thread_link").getAsString();
        Long channelId = DiscordLinks.parseChannelId(raw);

        if (channelId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.couldn_t_find_a_channel_thread"));
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.that_doesn_t_look_like_a"));
            return;
        }

        trackingService.addDestinationToSource(guild.getIdLong(), source, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "tracking.added_as_a_destination_for_every", channelId, source));
    }

    /**
     * One group's "Send Now" — only the three computed reports have one. The cheap version: no
     * polling, just recompute from what's already stored (Clan Report) or rebuild the most recently
     * completed week's digest, and send it now instead of waiting for the schedule. Unlike
     * {@link #doSendTestPosts}, this is real data, not sample text — see {@link TrackingSendNowService}.
     */
    private void doSendGroupNow(ButtonInteractionEvent event, Guild guild, TrackingGroup group) {
        if (!sendNowService.supportsGroup(group)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.there_nothing_to_send_for", group.displayName()));
            return;
        }
        // The Clan Report group and the Points & Promotions section run the same recompute.
        String lockKey = guild.getIdLong() + ":" + (group == TrackingGroup.CLAN_REPORT ? "points" : group.name());
        runSendNow(event, lockKey, () -> sendNowService.sendGroupNow(guild, group));
    }

    /**
     * A whole section's "Send Now" — the full refresh: polls the roster (posting each new activity as
     * its own entry), refreshes the roster, or recomputes points, depending on the section, then sends
     * that section's report where it has one. Not available for the Discord Admin Log, whose entries are
     * real-time events with nothing to refresh.
     */
    private void doSendSectionNow(ButtonInteractionEvent event, Guild guild, String source) {
        if (!sendNowService.supportsSection(source)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.there_nothing_to_send_for", source));
            return;
        }
        // Every roster-polling section shares one lock, so two clicks can't overlap their polls.
        String lockKey = guild.getIdLong() + ":" + (sendNowService.pollsRoster(source) ? "poll" : "points");
        runSendNow(event, lockKey, () -> sendNowService.sendSectionNow(guild, source));
    }

    private void runSendNow(ButtonInteractionEvent event, String lockKey, Supplier<TrackingSendNowService.Result> work) {
        if (!sendNowInFlight.add(lockKey)) {
            Containers.replyEphemeral(event, Containers.INFO,
                    Text.of(event, "tracking.that_refresh_is_already_running_its"));
            return;
        }

        event.deferReply(true).queue();
        sendNowExecutor.submit(() -> {
            try {
                TrackingSendNowService.Result result = work.get();
                event.getHook().editOriginalComponents(List.of(Containers.toast(
                        result.problem() ? Containers.WARNING : Containers.SUCCESS, result.lines().toArray(new String[0]))))
                        .useComponentsV2(true).queue();
            } catch (Exception e) {
                log.error("Send Now failed ({})", lockKey, e);
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                        Text.of(event, "tracking.send_now_failed_check_the_bot")))).useComponentsV2(true).queue();
            } finally {
                sendNowInFlight.remove(lockKey);
            }
        });
    }

    /**
     * Sends one sample line per group so an admin can see real formatting/icons without waiting for
     * an actual drop/kick/whatever — any group with no destination configured yet gets the invoking
     * channel added as one first (see the panel's own note about this). Every send is recorded in
     * {@code tracking_test_message} so {@link #doClearTestPosts} can find and delete exactly these
     * later, never a real event's post. Skips the two weekly-digest groups entirely ({@link #isWeeklyDigestGroup})
     * — those are a real windowed report with no meaningful fake sample, and have their own "Send Now"
     * button. Discord Admin Log groups and {@link TrackingGroup#CLAN_REPORT} send a
     * sample {@link Container} instead of a plain line (see {@link #buildSampleAuditContainer}/
     * {@link #buildSampleClanReportContainer}).
     */
    private void doSendTestPosts(ButtonInteractionEvent event, Guild guild) {
        event.deferReply(true).queue();
        long guildId = guild.getIdLong();
        long invokingChannelId = event.getChannel().getIdLong();
        String actorMention = event.getUser().getAsMention();

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        int destinationCount = 0;

        for (TrackingGroup group : TrackingGroup.values()) {
            if (isWeeklyDigestGroup(group)) continue;

            List<TrackingRepository.Destination> destinations = trackingService.getDestinations(guildId, group);
            if (destinations.isEmpty()) {
                trackingService.addDestination(guildId, group, invokingChannelId);
                destinations = trackingService.getDestinations(guildId, group);
            }

            boolean adminLog = isAdminLogGroup(group);
            boolean clanReport = group == TrackingGroup.CLAN_REPORT;
            boolean container = adminLog || clanReport;
            String line = container ? null : sampleLineFor(group, actorMention);
            Container sampleContainer = adminLog ? buildSampleAuditContainer(guild, group, actorMention)
                    : clanReport ? buildSampleClanReportContainer(guild) : null;

            for (TrackingRepository.Destination destination : destinations) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
                if (channel == null) continue;

                destinationCount++;
                CompletableFuture<Message> sendFuture = container
                        ? channel.sendMessageComponents(List.of(sampleContainer)).useComponentsV2(true).setSuppressedNotifications(true).submit()
                        : channel.sendMessage(line).setSuppressedNotifications(true).submit();
                pending.add(sendFuture.thenAccept(sent -> trackingService.recordTestMessage(guildId, channel.getIdLong(), sent.getIdLong())));
            }
        }

        int totalSent = destinationCount;
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, err) -> {
            long testableGroups = java.util.Arrays.stream(TrackingGroup.values()).filter(g -> !isWeeklyDigestGroup(g)).count();
            String message = "Sent " + totalSent + " test post(s) across " + testableGroups + " group(s).";
            if (err != null) message += " Some may have failed to send — check the bot's logs.";
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, message))).useComponentsV2(true).queue();
            return null;
        });
    }

    /** Deletes every message {@link #doSendTestPosts} has ever sent in this guild, then clears the record of them. Silently skips a message that's already gone (manually deleted) rather than failing the whole cleanup. */
    private void doClearTestPosts(ButtonInteractionEvent event, Guild guild) {
        event.deferReply(true).queue();
        long guildId = guild.getIdLong();
        List<TrackingRepository.TestMessage> messages = trackingService.getTestMessages(guildId);

        if (messages.isEmpty()) {
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.INFO, Text.of(event, "tracking.no_test_posts_to_clear")))).useComponentsV2(true).queue();
            return;
        }

        List<CompletableFuture<Void>> deletes = new ArrayList<>();
        for (TrackingRepository.TestMessage message : messages) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, message.channelId());
            if (channel == null) continue;
            deletes.add(channel.deleteMessageById(message.messageId()).submit().handle((v, err) -> null));
        }

        int count = messages.size();
        CompletableFuture.allOf(deletes.toArray(new CompletableFuture[0])).thenRun(() -> {
            trackingService.clearTestMessages(guildId);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, Text.of(event, "tracking.cleared_test_post", count)))).useComponentsV2(true).queue();
        });
    }

    /** One representative sample line per group, using the real icon catalogs so a test post looks exactly like the real thing would. */
    private String sampleLineFor(TrackingGroup group, String actorMention) {
        String prefix = "🧪 **[TEST]** ";
        String body = switch (group) {
            case SKILL_MILESTONES -> withIcon(skillEmojiCatalog.mentionFor(0), "TestPlayer levelled up **Attack**.");
            case QUESTS -> withIcon(trackingIconCatalog.mentionForCategory("quest"), "TestPlayer completed the quest **Missing, Presumed Death**.");
            case NOTABLE_DROPS -> withIcon(trackingIconCatalog.mentionForDrop("dragon_helm"), "TestPlayer found **Dragon helm**.");
            case CLUE_SCROLLS -> withIcon(trackingIconCatalog.mentionForCategory("clue"), "TestPlayer completed a hard treasure trail.");
            case PETS -> withIcon(skillEmojiMentionFor("Woodcutting"), "TestPlayer found **Ranis**, the Woodcutting pet.");
            case BOSS_KILLS -> withIcon(trackingIconCatalog.mentionForBoss("telos"), "TestPlayer defeated **Telos**.");
            // Archaeology reuses the skill's own icon; Minigame/Misc has no icon system of its own so
            // falls back to the generic RuneScore (personal achievement) icon; Citadel Activity and
            // Clan Joins/Leaves both use the Citadel icon as the general "clan" icon — matches
            // TrackingEventClassifier#entry/ClanSyncService exactly.
            case MINIGAME_MISC -> withIcon(trackingIconCatalog.mentionForCategory("runescore"), "TestPlayer reached floor 60 in Daemonheim.");
            case ARCHAEOLOGY -> withIcon(skillEmojiMentionFor("Archaeology"), "TestPlayer solved an archaeological mystery.");
            case CITADEL_ACTIVITY -> withIcon(trackingIconCatalog.mentionForCategory("citadel"), "TestPlayer visited the Clan Citadel.");
            case CLAN_JOINS_LEAVES -> withIcon(trackingIconCatalog.mentionForCategory("citadel"), "**TestPlayer** joined the clan.");
            // Never actually reached — doSendTestPosts skips both weekly-digest groups (their own
            // "Send Now" button) and sends every admin-log group and CLAN_REPORT as
            // a sample Container instead (see buildSampleAuditContainer/buildSampleClanReportContainer)
            // — but a switch expression over an enum still has to be exhaustive.
            case WEEKLY_JOINS_LEAVES, WEEKLY_CITADEL_REPORT, CLAN_REPORT, SERVER_SETTINGS, CHANNELS_THREADS,
                 ROLES_PERMISSIONS, MEMBERS_MODERATION, MESSAGES, SERVER_EXTRAS -> "";
        };
        return prefix + body;
    }

    private static String withIcon(String iconMention, String text) {
        return iconMention != null ? iconMention + " " + text : text;
    }

    private String skillEmojiMentionFor(String skillName) {
        for (int skillId = 0; skillId < com.runeclans.bot.runescape.RuneScapeSkillCatalog.skillCount(); skillId++) {
            if (com.runeclans.bot.runescape.RuneScapeSkillCatalog.nameFor(skillId).equalsIgnoreCase(skillName)) {
                return skillEmojiCatalog.mentionFor(skillId);
            }
        }
        return null;
    }

    private static boolean isAdminLogGroup(TrackingGroup group) {
        return group.source().equals("Discord Admin Log");
    }

    /** Mirrors {@link TrackingEventClassifier}'s real admin-log Container layout, so the preview looks exactly like a real entry would. */
    private Container buildSampleAuditContainer(Guild guild, TrackingGroup group, String actorMention) {
        String action = switch (group) {
            case SERVER_SETTINGS -> "Guild Update";
            case CHANNELS_THREADS -> "Channel Create";
            case ROLES_PERMISSIONS -> "Role Create";
            case MEMBERS_MODERATION -> "Kick";
            case MESSAGES -> "Message Bulk Delete";
            case SERVER_EXTRAS -> "Webhook Create";
            default -> "Unknown Action"; // unreachable — only called for the 6 admin-log groups above
        };

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(Text.of(guild, "tracking.test", action)));

        StringBuilder details = new StringBuilder("**Performed By:** ").append(actorMention);
        if (group == TrackingGroup.CHANNELS_THREADS) details.append("\n**Target:** **test-channel** (`0`)");
        if (group == TrackingGroup.ROLES_PERMISSIONS) details.append("\n**Target:** **Test Role** (`0`)");
        if (group == TrackingGroup.MEMBERS_MODERATION) details.append("\n**Target:** <@0>\n**Reason:** Testing the Tracking panel");

        long epochSeconds = java.time.Instant.now().getEpochSecond();
        details.append("\n**Time:** <t:").append(epochSeconds).append(":f> (<t:").append(epochSeconds).append(":R>)");

        children.add(TextDisplay.of(details.toString()));
        return Containers.card(Containers.PRIMARY, children);
    }

    /** Mirrors {@link ClanPointsService}'s real Clan Report layout with fake data (a made-up "TestPlayer" one tier below "eligible") — rank badges come from the real {@link TrackingIconCatalog#mentionForRank}. */
    private Container buildSampleClanReportContainer(Guild guild) {
        String currentLine = withIcon(trackingIconCatalog.mentionForRank(0), "**TestPlayer**");
        String earnedRank = withIcon(trackingIconCatalog.mentionForRank(1), "**Corporal**");

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(Text.of(guild, "tracking.test_clan_report_promotions_needed")));
        children.add(TextDisplay.of(Text.of(guild, "tracking.eligible_for_150_pts_waiting_since", currentLine, earnedRank)));
        return Containers.card(Containers.WARNING, children);
    }

    // TrackingGroup.source() values are plain English ("Discord Admin Log") — fine as data, but kept
    // out of raw custom ids on principle (Discord doesn't document a restriction on spaces there, and
    // this codebase would rather not be the one to find out). None of the four sources contain an
    // underscore, so this round-trips exactly.
    private static String encodeSource(String source) {
        return source.replace(' ', '_');
    }

    private static String decodeSource(String encoded) {
        return encoded.replace('_', ' ');
    }

    /** One button per source, not per group — see the class javadoc for why the old per-group layout doesn't fit anymore. Still 18+4 lightweight service calls per render (enabled + destination count, each group, plus a count per source) — an admin panel, not a hot path. */
    Container buildMainPanel(long guildId) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(Text.of(guildId, "tracking.tracker_channels_clan_drops_levels_citadel")));

        Set<String> sources = new LinkedHashSet<>();
        for (TrackingGroup group : TrackingGroup.values()) sources.add(group.source());

        for (String source : sources) {
            List<TrackingGroup> groups = trackingService.groupsInSource(source);
            long enabledCount = groups.stream().filter(group -> trackingService.isEnabled(guildId, group)).count();
            // Secondary/gray here, same as any other plain list item — blurple is reserved for Back,
            // the one button on this screen that's actually a navigation action.
            children.add(ActionRow.of(Button.secondary("configure_tracking_source:" + encodeSource(source),
                    "⚙️ " + source + " (" + enabledCount + "/" + groups.size() + ")")));
        }

        children.add(TextDisplay.of(Text.of(guildId, "tracking.send_test_posts_sends_one_sample")));
        children.add(ActionRow.of(
                Button.secondary("configure_tracking_test_send:_", Text.of(guildId, "tracking.send_test_posts")),
                Button.danger("configure_tracking_test_clear:_", Text.of(guildId, "tracking.clear_test_posts"))));

        // Back always lives alone at the very bottom, left-aligned, in blurple — the one consistent
        // spot every panel in this bot puts its "go to the previous screen" button.
        children.add(ActionRow.of(Button.primary("configure_back:_", Text.of(guildId, "tracking.back"))));
        children.add(Containers.autoCloseNote());

        return Containers.card(Containers.PRIMARY, children);
    }

    private static void flushGroupButtonRow(List<ContainerChildComponent> children, List<Button> rowButtons) {
        if (rowButtons.isEmpty()) return;
        children.add(ActionRow.of(new ArrayList<>(rowButtons)));
        rowButtons.clear();
    }

    private Container buildGroupPanel(long guildId, TrackingGroup group) {
        boolean enabled = trackingService.isEnabled(guildId, group);
        List<TrackingRepository.Destination> destinations = trackingService.getDestinations(guildId, group);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + group.displayName()));
        children.add(TextDisplay.of("-# " + group.source()));
        children.add(TextDisplay.of(Text.of(guildId, "tracking.status", (enabled ? Text.of(guildId, "tracking.enabled") : Text.of(guildId, "tracking.disabled")))));
        children.add(ActionRow.of(enabled ? Button.danger("configure_tracking_toggle:" + group.name(), Text.of(guildId, "tracking.disable"))
                : Button.success("configure_tracking_toggle:" + group.name(), Text.of(guildId, "tracking.enable"))));

        if (sendNowService.supportsGroup(group)) {
            children.add(TextDisplay.of(group == TrackingGroup.CLAN_REPORT ? Text.of(guildId, "tracking.this_posts_on_its_own_every") : Text.of(guildId, "tracking.this_posts_on_its_own_every_2")));
        }

        if (destinations.isEmpty()) {
            children.add(TextDisplay.of(Text.of(guildId, "tracking.destinations_none_yet")));
        } else {
            StringBuilder list = new StringBuilder("**Destinations:**\n");
            for (TrackingRepository.Destination destination : destinations) {
                list.append("• <#").append(destination.channelId()).append(">\n");
            }
            children.add(TextDisplay.of(list.toString().trim()));

            StringSelectMenu.Builder removeMenu = StringSelectMenu.create("configure_tracking_remove_dest:" + group.name())
                    .setPlaceholder(Text.of(guildId, "tracking.remove_a_destination"));
            for (TrackingRepository.Destination destination : destinations) {
                removeMenu.addOption("Remove #" + destination.channelId(), String.valueOf(destination.id()));
            }
            children.add(ActionRow.of(removeMenu.build()));
        }

        children.add(TextDisplay.of(Text.of(guildId, "tracking.add_a_channel_regular_channel_or")));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_tracking_add_channel:" + group.name(), EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder(Text.of(guildId, "tracking.select_a_channel_or_thread"))
                        .setRequiredRange(0, 1)
                        .build()));

        children.add(Containers.linkButtonRow("configure_tracking_add_thread:" + group.name()));

        // Back stays leftmost (the rule every panel here follows); Send Now sits right beside it on
        // the groups that have one.
        Button back = Button.primary("configure_tracking_source:" + encodeSource(group.source()), Text.of(guildId, "tracking.back"));
        children.add(sendNowService.supportsGroup(group)
                ? ActionRow.of(back, Button.success("configure_tracking_report_send:" + group.name(), Text.of(guildId, "tracking.send_now")))
                : ActionRow.of(back));

        return Containers.card(Containers.PRIMARY, children);
    }

    /** This section's groups (drill into any one) plus bulk actions for all of them at once — reached via that source's header button on the main panel. */
    private Container buildSourceEditorPanel(long guildId, String source) {
        List<TrackingGroup> groups = trackingService.groupsInSource(source).stream()
                .sorted(Comparator.comparing(TrackingGroup::displayName))
                .toList();

        String encodedSource = encodeSource(source);
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + source));

        children.add(TextDisplay.of(Text.of(guildId, "tracking.enable_or_disable_every_group_here")));
        children.add(ActionRow.of(
                Button.success("configure_tracking_source_enable_all:" + encodedSource, Text.of(guildId, "tracking.enable_all")),
                Button.secondary("configure_tracking_source_disable_all:" + encodedSource, Text.of(guildId, "tracking.disable_all"))));

        children.add(TextDisplay.of(Text.of(guildId, "tracking.add_a_destination_to_every_group")));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_tracking_source_add_channel:" + encodedSource, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder(Text.of(guildId, "tracking.select_a_channel_or_thread"))
                        .setRequiredRange(0, 1)
                        .build()));
        children.add(ActionRow.of(
                Containers.linkButton("configure_tracking_source_add_thread:" + encodedSource),
                Button.danger("configure_tracking_source_clear_confirm:" + encodedSource, Text.of(guildId, "tracking.clear_all_destinations"))));
        children.add(TextDisplay.of(Text.of(guildId, "tracking.link_by_id_adds_a_destination")));

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(Text.of(guildId, "tracking.groups_click_one_to_open_it")));
        List<Button> rowButtons = new ArrayList<>();
        for (TrackingGroup group : groups) {
            boolean enabled = trackingService.isEnabled(guildId, group);
            int destinationCount = trackingService.getDestinations(guildId, group).size();
            String label = group.displayName() + " (" + destinationCount + ")";
            String buttonId = "configure_tracking_view_group:" + group.name();
            rowButtons.add(enabled ? Button.success(buttonId, label) : Button.secondary(buttonId, label));

            // Discord caps an ActionRow at 5 components — start a new row once this one's full.
            if (rowButtons.size() == 5) flushGroupButtonRow(children, rowButtons);
        }
        flushGroupButtonRow(children, rowButtons);

        // Back stays leftmost (the rule every panel here follows); Send Now sits right beside it on
        // every section that has something to refresh — everything except the Discord Admin Log.
        Button back = Button.primary("configure_tracking_main:_", Text.of(guildId, "tracking.back"));
        if (sendNowService.supportsSection(source)) {
            children.add(TextDisplay.of(sendNowService.describeSection(source)));
            children.add(ActionRow.of(back, Button.success("configure_tracking_source_send:" + encodedSource, Text.of(guildId, "tracking.send_now"))));
        } else {
            children.add(ActionRow.of(back));
        }

        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildSourceClearConfirmPanel(long guildId, String source) {
        List<TrackingGroup> groups = trackingService.groupsInSource(source);
        int totalDestinations = groups.stream().mapToInt(group -> trackingService.getDestinations(guildId, group).size()).sum();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(Text.of(guildId, "tracking.clear_all_destinations_2", source)));
        children.add(TextDisplay.of(Text.of(guildId, "tracking.this_removes_destination_across_all_group", totalDestinations, groups.size(), source)));
        String encodedSource = encodeSource(source);
        children.add(ActionRow.of(
                Button.danger("configure_tracking_source_clear_go:" + encodedSource, Text.of(guildId, "tracking.yes_clear_all")),
                Button.secondary("configure_tracking_source:" + encodedSource, Text.of(guildId, "tracking.cancel"))));

        return Containers.card(Containers.DANGER, children);
    }
}
