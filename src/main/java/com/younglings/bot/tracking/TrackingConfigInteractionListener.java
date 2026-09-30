package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.DiscordLinks;
import com.younglings.bot.runescape.SkillEmojiCatalog;
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

import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The Tracking panel — {@code /configure}'s UI for the announcement system approved in the event
 * taxonomy: enable/disable each of {@link TrackingGroup}'s 18 groups and pick where each one posts.
 * A completely separate listener from {@link com.younglings.bot.commands.configure.ConfigureInteractionListener}
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
    private final WeeklyDigestService weeklyDigestService;
    private final ClanPointsService clanPointsService;

    public TrackingConfigInteractionListener(TrackingService trackingService, SkillEmojiCatalog skillEmojiCatalog,
                                              TrackingIconCatalog trackingIconCatalog, WeeklyDigestService weeklyDigestService,
                                              ClanPointsService clanPointsService) {
        this.trackingService = trackingService;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
        this.weeklyDigestService = weeklyDigestService;
        this.clanPointsService = clanPointsService;
    }

    /** These 3 groups post a real computed report on their own schedule rather than a per-event line — {@link #doSendTestPosts} skips them (nothing to sample) and their own screen gets a "send it now" button instead. */
    private static boolean isComputedReportGroup(TrackingGroup group) {
        return group == TrackingGroup.WEEKLY_JOINS_LEAVES || group == TrackingGroup.WEEKLY_CITADEL_REPORT || group == TrackingGroup.CLAN_REPORT;
    }

    /** The same Wed-00:01-to-Wed-00:00 UTC window {@code WeeklyDigestScheduler} computes for its real weekly run — "this week's report" always means the week that most recently completed. */
    private static OffsetDateTime[] currentDigestWindow() {
        OffsetDateTime windowEnd = OffsetDateTime.now(ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        return new OffsetDateTime[]{windowEnd.minusDays(7).plusMinutes(1), windowEnd};
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_tracking")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
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
                case "configure_tracking_report_send" -> doSendReportNow(event, guild, TrackingGroup.valueOf(parts[1]));
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
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
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
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
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
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
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
                .setPlaceholder("Paste a thread/channel link, or just its ID")
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
            Containers.replyEphemeral(event, Containers.WARNING, "Couldn't find a channel/thread ID in that — paste the full link, or just the ID by itself.");
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That doesn't look like a channel or thread in this server. Double-check the link and try again.");
            return;
        }

        trackingService.addDestination(guild.getIdLong(), group, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Added <#" + channelId + "> as a destination for **" + group.displayName() + "**.");
    }

    private void doAddSourceThreadPrompt(ButtonInteractionEvent event, String encodedSource) {
        TextInput linkInput = TextInput.create("thread_link", TextInputStyle.SHORT)
                .setPlaceholder("Paste a thread/channel link, or just its ID")
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
            Containers.replyEphemeral(event, Containers.WARNING, "Couldn't find a channel/thread ID in that — paste the full link, or just the ID by itself.");
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That doesn't look like a channel or thread in this server. Double-check the link and try again.");
            return;
        }

        trackingService.addDestinationToSource(guild.getIdLong(), source, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Added <#" + channelId + "> as a destination for every group under **" + source + "**.");
    }

    /**
     * Runs the real computed report for one of the 3 {@link #isComputedReportGroup} groups right now,
     * instead of waiting for its own scheduler — unlike {@link #doSendTestPosts}, this sends the
     * actual computed report (real data), not sample text, so there's nothing to record for later
     * clearing. For the two weekly groups this uses the most recently completed Wed-to-Wed window,
     * exactly what {@code WeeklyDigestScheduler} would have sent; for {@code CLAN_REPORT} it just
     * re-sends the currently-flagged members — see {@link ClanPointsService#sendClanReportNow}, which
     * deliberately doesn't re-run the award/promotion check itself (that's the daily scheduler's job).
     */
    private void doSendReportNow(ButtonInteractionEvent event, Guild guild, TrackingGroup group) {
        event.deferReply(true).queue();

        try {
            switch (group) {
                case WEEKLY_JOINS_LEAVES -> {
                    OffsetDateTime[] window = currentDigestWindow();
                    weeklyDigestService.sendJoinsLeaves(guild, window[0], window[1]);
                }
                case WEEKLY_CITADEL_REPORT -> {
                    OffsetDateTime[] window = currentDigestWindow();
                    weeklyDigestService.sendCitadelReport(guild, window[0], window[1]);
                }
                case CLAN_REPORT -> clanPointsService.sendClanReportNow(guild);
                default -> {}
            }
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                    "Sent — if there was nothing to report, nothing was posted."))).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Failed to send report now for group {} guild {}", group, guild.getIdLong(), e);
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                    "Failed to send — check the bot's logs."))).useComponentsV2(true).queue();
        }
    }

    /**
     * Sends one sample line per group so an admin can see real formatting/icons without waiting for
     * an actual drop/kick/whatever — any group with no destination configured yet gets the invoking
     * channel added as one first (see the panel's own note about this). Every send is recorded in
     * {@code tracking_test_message} so {@link #doClearTestPosts} can find and delete exactly these
     * later, never a real event's post. Skips the two weekly-digest groups entirely — those are a real
     * computed report, not sample text, and have their own "Send This Week's Report Now" button.
     */
    private void doSendTestPosts(ButtonInteractionEvent event, Guild guild) {
        event.deferReply(true).queue();
        long guildId = guild.getIdLong();
        long invokingChannelId = event.getChannel().getIdLong();
        String actorMention = event.getUser().getAsMention();

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        int destinationCount = 0;

        for (TrackingGroup group : TrackingGroup.values()) {
            if (isComputedReportGroup(group)) continue;

            List<TrackingRepository.Destination> destinations = trackingService.getDestinations(guildId, group);
            if (destinations.isEmpty()) {
                trackingService.addDestination(guildId, group, invokingChannelId);
                destinations = trackingService.getDestinations(guildId, group);
            }

            boolean adminLog = isAdminLogGroup(group);
            String line = adminLog ? null : sampleLineFor(group, actorMention);
            Container sampleContainer = adminLog ? buildSampleAuditContainer(group, actorMention) : null;

            for (TrackingRepository.Destination destination : destinations) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
                if (channel == null) continue;

                destinationCount++;
                CompletableFuture<Message> sendFuture = adminLog
                        ? channel.sendMessageComponents(List.of(sampleContainer)).useComponentsV2(true).setSuppressedNotifications(true).submit()
                        : channel.sendMessage(line).setSuppressedNotifications(true).submit();
                pending.add(sendFuture.thenAccept(sent -> trackingService.recordTestMessage(guildId, channel.getIdLong(), sent.getIdLong())));
            }
        }

        int totalSent = destinationCount;
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, err) -> {
            long testableGroups = java.util.Arrays.stream(TrackingGroup.values()).filter(g -> !isComputedReportGroup(g)).count();
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
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.INFO, "No test posts to clear."))).useComponentsV2(true).queue();
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
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, "Cleared " + count + " test post(s)."))).useComponentsV2(true).queue();
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
            case MINIGAME_MISC -> "TestPlayer reached floor 60 in Daemonheim.";
            case ARCHAEOLOGY -> withIcon(trackingIconCatalog.mentionForCategory("archaeology"), "TestPlayer solved an archaeological mystery.");
            case CITADEL_ACTIVITY -> "TestPlayer visited the Clan Citadel.";
            case CLAN_JOINS_LEAVES -> "**TestPlayer** joined the clan.";
            // Never actually reached — doSendTestPosts skips both weekly-digest groups (their own
            // "Send This Week's Report Now" button) and every admin-log group (sent as a sample
            // Container instead, see buildSampleAuditContainer) — but a switch expression over an enum
            // still has to be exhaustive.
            case WEEKLY_JOINS_LEAVES, WEEKLY_CITADEL_REPORT, CLAN_REPORT, SERVER_SETTINGS, CHANNELS_THREADS,
                 ROLES_PERMISSIONS, MEMBERS_MODERATION, MESSAGES, SERVER_EXTRAS -> "";
        };
        return prefix + body;
    }

    private static String withIcon(String iconMention, String text) {
        return iconMention != null ? iconMention + " " + text : text;
    }

    private String skillEmojiMentionFor(String skillName) {
        for (int skillId = 0; skillId < com.younglings.bot.runescape.RuneScapeSkillCatalog.skillCount(); skillId++) {
            if (com.younglings.bot.runescape.RuneScapeSkillCatalog.nameFor(skillId).equalsIgnoreCase(skillName)) {
                return skillEmojiCatalog.mentionFor(skillId);
            }
        }
        return null;
    }

    private static boolean isAdminLogGroup(TrackingGroup group) {
        return group.source().equals("Discord Admin Log");
    }

    /** Mirrors {@link TrackingEventClassifier}'s real admin-log Container layout, so the preview looks exactly like a real entry would. */
    private Container buildSampleAuditContainer(TrackingGroup group, String actorMention) {
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
        children.add(TextDisplay.of("### 🧪 [TEST] " + action));

        StringBuilder details = new StringBuilder("**Performed By:** ").append(actorMention);
        if (group == TrackingGroup.CHANNELS_THREADS) details.append("\n**Target:** **test-channel** (`0`)");
        if (group == TrackingGroup.ROLES_PERMISSIONS) details.append("\n**Target:** **Test Role** (`0`)");
        if (group == TrackingGroup.MEMBERS_MODERATION) details.append("\n**Target:** <@0>\n**Reason:** Testing the Tracking panel");

        long epochSeconds = java.time.Instant.now().getEpochSecond();
        details.append("\n**Time:** <t:").append(epochSeconds).append(":f> (<t:").append(epochSeconds).append(":R>)");

        children.add(TextDisplay.of(details.toString()));
        return Containers.card(Containers.PRIMARY, children);
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
        children.add(TextDisplay.of("### Tracking\n" +
                "-# Clan drops, levels, Citadel activity, joins/leaves, and Discord's own admin log — click a section below, then a group inside it."));

        Set<String> sources = new LinkedHashSet<>();
        for (TrackingGroup group : TrackingGroup.values()) sources.add(group.source());

        boolean firstSource = true;
        for (String source : sources) {
            List<TrackingGroup> groups = trackingService.groupsInSource(source);
            long enabledCount = groups.stream().filter(group -> trackingService.isEnabled(guildId, group)).count();
            // Blurple + a gear icon, vs. the group buttons' green/gray on the section's own screen, is
            // what marks this as "open a section" rather than a single group's toggle.
            Button sourceButton = Button.primary("configure_tracking_source:" + encodeSource(source),
                    "⚙️ " + source + " (" + enabledCount + "/" + groups.size() + ")");
            if (firstSource) {
                // Sharing a row with Back keeps the top of the panel to one row instead of two.
                children.add(ActionRow.of(Button.secondary("configure_back:_", "Back"), sourceButton));
            } else {
                children.add(ActionRow.of(sourceButton));
            }
            firstSource = false;
        }

        children.add(TextDisplay.of("-# **Send Test Posts** sends one sample line per group so you can see the real formatting/icons — any group with no destination yet gets this channel added as one. **Clear Test Posts** deletes everything a test send has ever posted in this server."));
        children.add(ActionRow.of(
                Button.secondary("configure_tracking_test_send:_", "Send Test Posts"),
                Button.danger("configure_tracking_test_clear:_", "Clear Test Posts")));

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
        children.add(ActionRow.of(
                Button.secondary("configure_tracking_source:" + encodeSource(group.source()), "Back to " + group.source()),
                enabled ? Button.danger("configure_tracking_toggle:" + group.name(), "Disable")
                        : Button.success("configure_tracking_toggle:" + group.name(), "Enable")));

        children.add(TextDisplay.of("**Status:** " + (enabled ? "Enabled" : "Disabled")));

        if (isComputedReportGroup(group)) {
            boolean daily = group == TrackingGroup.CLAN_REPORT;
            children.add(TextDisplay.of(daily
                    ? "-# This posts on its own every day shortly after the daily clan sync (00:00 UTC) — send today's report right now instead of waiting."
                    : "-# This posts on its own every Wednesday at 01:00 UTC — send this week's report right now instead of waiting."));
            children.add(ActionRow.of(Button.secondary("configure_tracking_report_send:" + group.name(),
                    daily ? "Send Today's Report Now" : "Send This Week's Report Now")));
        }

        if (destinations.isEmpty()) {
            children.add(TextDisplay.of("**Destinations:** *none yet*"));
        } else {
            StringBuilder list = new StringBuilder("**Destinations:**\n");
            for (TrackingRepository.Destination destination : destinations) {
                list.append("• <#").append(destination.channelId()).append(">\n");
            }
            children.add(TextDisplay.of(list.toString().trim()));

            StringSelectMenu.Builder removeMenu = StringSelectMenu.create("configure_tracking_remove_dest:" + group.name())
                    .setPlaceholder("Remove a destination");
            for (TrackingRepository.Destination destination : destinations) {
                removeMenu.addOption("Remove #" + destination.channelId(), String.valueOf(destination.id()));
            }
            children.add(ActionRow.of(removeMenu.build()));
        }

        children.add(TextDisplay.of("**Add a channel** (regular channel or an active thread):"));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_tracking_add_channel:" + group.name(), EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder("Select a channel or thread")
                        .setRequiredRange(0, 1)
                        .build()));

        children.add(Containers.linkButtonRow("configure_tracking_add_thread:" + group.name()));

        return Containers.card(Containers.PRIMARY, children);
    }

    /** This section's groups (drill into any one) plus bulk actions for all of them at once — reached via that source's header button on the main panel. */
    private Container buildSourceEditorPanel(long guildId, String source) {
        List<TrackingGroup> groups = trackingService.groupsInSource(source).stream()
                .sorted(Comparator.comparing(TrackingGroup::displayName))
                .toList();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + source));
        children.add(ActionRow.of(Button.secondary("configure_tracking_main:_", "All Sections")));

        children.add(TextDisplay.of("**Groups** — click one to open it. Green = enabled, gray = disabled:"));
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

        String encodedSource = encodeSource(source);
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("**Enable or disable every group here at once:**"));
        children.add(ActionRow.of(
                Button.success("configure_tracking_source_enable_all:" + encodedSource, "Enable All"),
                Button.secondary("configure_tracking_source_disable_all:" + encodedSource, "Disable All")));

        children.add(TextDisplay.of("**Add a destination to every group here at once** (regular channel or an active thread):"));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_tracking_source_add_channel:" + encodedSource, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder("Select a channel or thread")
                        .setRequiredRange(0, 1)
                        .build()));
        children.add(Containers.linkButtonRow("configure_tracking_source_add_thread:" + encodedSource));

        children.add(TextDisplay.of("-# **Clear All Destinations** removes every destination from every group in this section — there's no undo, so it asks you to confirm first."));
        children.add(ActionRow.of(Button.danger("configure_tracking_source_clear_confirm:" + encodedSource, "Clear All Destinations")));

        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildSourceClearConfirmPanel(long guildId, String source) {
        List<TrackingGroup> groups = trackingService.groupsInSource(source);
        int totalDestinations = groups.stream().mapToInt(group -> trackingService.getDestinations(guildId, group).size()).sum();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clear All Destinations — " + source));
        children.add(TextDisplay.of("This removes " + totalDestinations + " destination(s) across all " + groups.size() +
                " group(s) under " + source + ". Groups stay enabled — they'll just have nowhere to post until you add destinations again. This can't be undone."));
        String encodedSource = encodeSource(source);
        children.add(ActionRow.of(
                Button.danger("configure_tracking_source_clear_go:" + encodedSource, "Yes, Clear All"),
                Button.secondary("configure_tracking_source:" + encodedSource, "Cancel")));

        return Containers.card(Containers.DANGER, children);
    }
}
