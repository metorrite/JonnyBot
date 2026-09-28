package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The Tracking panel — {@code /configure}'s UI for the announcement system approved in the event
 * taxonomy: enable/disable each of {@link TrackingGroup}'s 17 groups and pick where each one posts.
 * A completely separate listener from {@link com.younglings.bot.commands.configure.ConfigureInteractionListener}
 * (still all {@code configure_tracking_}-prefixed, still gated the same way) purely because that
 * class was already sizeable before this — {@code buildPanel()}'s entry button is the only thing that
 * lives over there.
 * <p>
 * Two screens: a list of all 17 groups, each its own button (green = enabled, gray = disabled) that
 * opens straight to that group's screen — clicking beats a dropdown when you already know which one
 * you want (switched from a dropdown to this after exactly that feedback). That group's own screen has
 * an enable toggle, current destinations (removable via a dropdown), a native channel picker to
 * add a regular channel or thread, and an "Add by link" modal fallback for a forum thread Discord's
 * channel picker doesn't surface (older/archived threads in particular — untested at the time this
 * was built, hence the fallback existing at all rather than being added later).
 */
@BService
public class TrackingConfigInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TrackingConfigInteractionListener.class);

    private final TrackingService trackingService;
    private final SkillEmojiCatalog skillEmojiCatalog;
    private final TrackingIconCatalog trackingIconCatalog;

    public TrackingConfigInteractionListener(TrackingService trackingService, SkillEmojiCatalog skillEmojiCatalog,
                                              TrackingIconCatalog trackingIconCatalog) {
        this.trackingService = trackingService;
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
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
                case "configure_tracking_toggle" -> {
                    TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                    boolean currentlyEnabled = trackingService.isEnabled(guild.getIdLong(), group);
                    trackingService.setEnabled(guild.getIdLong(), group, !currentlyEnabled);
                    event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
                }
                case "configure_tracking_add_thread" -> doAddThreadPrompt(event, parts[1]);
                case "configure_tracking_test_send" -> doSendTestPosts(event, guild);
                case "configure_tracking_test_clear" -> doClearTestPosts(event, guild);
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
            if (parts[0].equals("configure_tracking_add_channel")) {
                TrackingGroup group = TrackingGroup.valueOf(parts[1]);
                trackingService.addDestination(guild.getIdLong(), group, event.getValues().getFirst().getIdLong());
                event.editComponents(List.of(buildGroupPanel(guild.getIdLong(), group))).useComponentsV2(true).queue();
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
            if (parts[0].equals("configure_tracking_thread_modal")) {
                handleAddThreadModal(event, guild, TrackingGroup.valueOf(parts[1]));
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
        Long channelId = parseChannelIdFromLinkOrId(raw);

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

    /**
     * Sends one sample line per group so an admin can see real formatting/icons without waiting for
     * an actual drop/kick/whatever — any group with no destination configured yet gets the invoking
     * channel added as one first (see the panel's own note about this). Every send is recorded in
     * {@code tracking_test_message} so {@link #doClearTestPosts} can find and delete exactly these
     * later, never a real event's post.
     */
    private void doSendTestPosts(ButtonInteractionEvent event, Guild guild) {
        event.deferReply(true).queue();
        long guildId = guild.getIdLong();
        long invokingChannelId = event.getChannel().getIdLong();
        String actorMention = event.getUser().getAsMention();

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        int destinationCount = 0;

        for (TrackingGroup group : TrackingGroup.values()) {
            List<TrackingRepository.Destination> destinations = trackingService.getDestinations(guildId, group);
            if (destinations.isEmpty()) {
                trackingService.addDestination(guildId, group, invokingChannelId);
                destinations = trackingService.getDestinations(guildId, group);
            }

            String line = sampleLineFor(group, actorMention);
            for (TrackingRepository.Destination destination : destinations) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
                if (channel == null) continue;

                destinationCount++;
                CompletableFuture<Message> sendFuture = channel.sendMessage(line).submit();
                pending.add(sendFuture.thenAccept(sent -> trackingService.recordTestMessage(guildId, channel.getIdLong(), sent.getIdLong())));
            }
        }

        int totalSent = destinationCount;
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, err) -> {
            String message = "Sent " + totalSent + " test post(s) across " + TrackingGroup.values().length + " group(s).";
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
            case PETS -> withIcon(trackingIconCatalog.mentionForCategory("pet"), "TestPlayer found **Ranis**, the Woodcutting pet.");
            case BOSS_KILLS -> "TestPlayer defeated **Telos**.";
            case MINIGAME_MISC -> "TestPlayer reached floor 60 in Daemonheim.";
            case ARCHAEOLOGY -> withIcon(trackingIconCatalog.mentionForCategory("archaeology"), "TestPlayer solved an archaeological mystery.");
            case CITADEL_ACTIVITY -> "TestPlayer visited the Clan Citadel.";
            case CLAN_JOINS_LEAVES -> "**TestPlayer** joined the clan.";
            case SERVER_SETTINGS -> actorMention + " — Guild Update";
            case CHANNELS_THREADS -> actorMention + " — Channel Create";
            case ROLES_PERMISSIONS -> actorMention + " — Role Create";
            case MEMBERS_MODERATION -> actorMention + " — Kick";
            case MESSAGES -> actorMention + " — Message Bulk Delete";
            case SERVER_EXTRAS -> actorMention + " — Webhook Create";
        };
        return prefix + body;
    }

    private static String withIcon(String iconMention, String text) {
        return iconMention != null ? iconMention + " " + text : text;
    }

    /** A pasted message/channel link ({@code .../channels/<guild>/<channel>[/<message>]}) or a bare numeric ID; {@code null} if neither parses. */
    private static Long parseChannelIdFromLinkOrId(String input) {
        String trimmed = input.trim();
        if (trimmed.contains("/channels/")) {
            String[] afterMarker = trimmed.split("/channels/", 2)[1].split("/");
            if (afterMarker.length < 2) return null;
            try {
                return Long.parseLong(afterMarker[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Long.parseLong(trimmed.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 17 lightweight service calls per render (enabled + destination count, each group) — an admin panel, not a hot path. */
    Container buildMainPanel(long guildId) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Tracking"));
        children.add(TextDisplay.of("-# Clan drops, levels, Citadel activity, joins/leaves, and Discord's own admin log — click a group below to open it. Green = enabled, gray = disabled."));
        children.add(ActionRow.of(Button.secondary("configure_back:_", "Back")));

        String currentSource = null;
        List<Button> rowButtons = new ArrayList<>();

        for (TrackingGroup group : TrackingGroup.values()) {
            if (!group.source().equals(currentSource)) {
                flushGroupButtonRow(children, rowButtons);
                currentSource = group.source();
                children.add(TextDisplay.of("**" + currentSource + "**"));
            }

            boolean enabled = trackingService.isEnabled(guildId, group);
            int destinationCount = trackingService.getDestinations(guildId, group).size();
            String label = group.displayName() + " (" + destinationCount + ")";
            String buttonId = "configure_tracking_view_group:" + group.name();
            rowButtons.add(enabled ? Button.success(buttonId, label) : Button.secondary(buttonId, label));

            // Discord caps an ActionRow at 5 components — start a new row once this one's full,
            // same as a new source section starting also flushes the row above.
            if (rowButtons.size() == 5) flushGroupButtonRow(children, rowButtons);
        }
        flushGroupButtonRow(children, rowButtons);

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
                Button.secondary("configure_tracking_main:_", "All Groups"),
                enabled ? Button.danger("configure_tracking_toggle:" + group.name(), "Disable")
                        : Button.success("configure_tracking_toggle:" + group.name(), "Enable")));

        children.add(TextDisplay.of("**Status:** " + (enabled ? "Enabled" : "Disabled")));

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

        children.add(TextDisplay.of("-# Forum thread not showing up above? Paste its link instead."));
        children.add(ActionRow.of(Button.secondary("configure_tracking_add_thread:" + group.name(), "Add by Link")));

        return Containers.card(Containers.PRIMARY, children);
    }
}
