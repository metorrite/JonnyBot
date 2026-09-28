package com.younglings.bot.announcement;

import com.younglings.bot.commands.configure.ConfigureInteractionListener;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.DiscordLinks;
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
 * The Announcements panel — {@code /configure}'s UI for {@link AnnouncementPreset}: paste a block of
 * text once, pick as many destinations as you like, and Post / Update posts it fresh (or edits the
 * previous copy in place) in every one of them. Generalizes what used to be a single-channel,
 * Rules-only feature ({@code ConfigureInteractionListener}'s old Rules panel) into 4 named slots.
 * <p>
 * A separate listener from {@link ConfigureInteractionListener}, same reasoning as
 * {@link com.younglings.bot.tracking.TrackingConfigInteractionListener} — only the root panel's entry
 * button (and the shared {@code configure_back} id) live over there.
 */
@BService
public class AnnouncementInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementInteractionListener.class);

    private final AnnouncementService announcementService;

    public AnnouncementInteractionListener(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_announce")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            String[] parts = id.split(":");
            switch (parts[0]) {
                case "configure_announce_main" -> event.editComponents(List.of(buildMainPanel(guild.getIdLong()))).useComponentsV2(true).queue();
                case "configure_announce_preset", "configure_announce_clear_cancel" ->
                        event.editComponents(List.of(buildPresetPanel(guild.getIdLong(), AnnouncementPreset.valueOf(parts[1])))).useComponentsV2(true).queue();
                case "configure_announce_edit" -> doEditTextPrompt(event, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_post" -> doPostAnnouncement(event, guild, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_add_link" -> doAddLinkPrompt(event, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_clear_confirm" -> event.editComponents(List.of(buildClearConfirmPanel(guild.getIdLong(), AnnouncementPreset.valueOf(parts[1])))).useComponentsV2(true).queue();
                case "configure_announce_clear_go" -> {
                    AnnouncementPreset preset = AnnouncementPreset.valueOf(parts[1]);
                    announcementService.clearDestinations(guild.getIdLong(), preset);
                    event.editComponents(List.of(buildPresetPanel(guild.getIdLong(), preset))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in announcement button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("configure_announce")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            String[] parts = id.split(":");
            switch (parts[0]) {
                case "configure_announce_text_modal" -> handleTextModal(event, guild, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_link_modal" -> handleLinkModal(event, guild, AnnouncementPreset.valueOf(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in announcement modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_announce")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            String[] parts = id.split(":");
            if (parts[0].equals("configure_announce_remove_dest")) {
                AnnouncementPreset preset = AnnouncementPreset.valueOf(parts[1]);
                long destinationId = Long.parseLong(event.getValues().getFirst());
                announcementService.removeDestination(guild.getIdLong(), destinationId);
                event.editComponents(List.of(buildPresetPanel(guild.getIdLong(), preset))).useComponentsV2(true).queue();
            }
        } catch (Exception e) {
            log.error("Unhandled exception in announcement select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_announce")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            String[] parts = id.split(":");
            if (parts[0].equals("configure_announce_add_channel") && !event.getValues().isEmpty()) {
                AnnouncementPreset preset = AnnouncementPreset.valueOf(parts[1]);
                announcementService.addDestination(guild.getIdLong(), preset, event.getValues().getFirst().getIdLong());
                event.editComponents(List.of(buildPresetPanel(guild.getIdLong(), preset))).useComponentsV2(true).queue();
            }
        } catch (Exception e) {
            log.error("Unhandled exception in announcement entity select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void doEditTextPrompt(ButtonInteractionEvent event, AnnouncementPreset preset) {
        String current = announcementService.getText(event.getGuild().getIdLong(), preset);

        TextInput.Builder textInput = TextInput.create("announce_text", TextInputStyle.PARAGRAPH)
                .setPlaceholder("The text, exactly as you want it posted")
                .setRequired(false)
                .setMaxLength(4000);
        if (current != null) textInput.setValue(current);

        Modal modal = Modal.create("configure_announce_text_modal:" + preset.name(), preset.displayName() + " Text")
                .addComponents(Label.of("Text", textInput.build()))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleTextModal(ModalInteractionEvent event, Guild guild, AnnouncementPreset preset) {
        String text = blankToNull(event.getValue("announce_text").getAsString());
        announcementService.setText(guild.getIdLong(), preset, text);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Text saved — click **Post / Update** to publish it.");
    }

    private void doAddLinkPrompt(ButtonInteractionEvent event, AnnouncementPreset preset) {
        TextInput linkInput = TextInput.create("link_value", TextInputStyle.SHORT)
                .setPlaceholder("Paste a channel/thread link, or just its ID")
                .setRequired(true)
                .build();

        Modal modal = Modal.create("configure_announce_link_modal:" + preset.name(), "Add a Destination")
                .addComponents(Label.of("Channel Link or ID", linkInput))
                .build();
        event.replyModal(modal).queue();
    }

    private void handleLinkModal(ModalInteractionEvent event, Guild guild, AnnouncementPreset preset) {
        String raw = event.getValue("link_value").getAsString();
        Long channelId = DiscordLinks.parseChannelId(raw);

        if (channelId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "Couldn't find a channel/thread ID in that — paste the full link, or just the ID by itself.");
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That doesn't look like a channel or thread in this server. Double-check the link and try again.");
            return;
        }

        announcementService.addDestination(guild.getIdLong(), preset, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Added <#" + channelId + "> as a destination for **" + preset.displayName() + "**.");
    }

    /**
     * Posts the preset's text fresh, or edits the previously-posted message in place per destination
     * that already has one — so editing the text and clicking this again updates every copy instead
     * of leaving duplicates behind. Falls through to posting fresh wherever the stored message id no
     * longer resolves (deleted, or never successfully posted).
     */
    private void doPostAnnouncement(ButtonInteractionEvent event, Guild guild, AnnouncementPreset preset) {
        long guildId = guild.getIdLong();
        String text = announcementService.getText(guildId, preset);
        List<AnnouncementRepository.Destination> destinations = announcementService.getDestinations(guildId, preset);

        if (text == null || destinations.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "Set both the text and at least one destination first.");
            return;
        }

        event.deferReply(true).queue();
        Container container = Containers.card(Containers.PRIMARY, TextDisplay.of(text));

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (AnnouncementRepository.Destination destination : destinations) {
            pending.add(postOrUpdateOne(guild, guildId, destination, container));
        }

        int total = destinations.size();
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, err) -> {
            String message = "Posted/updated **" + preset.displayName() + "** in " + total + " destination(s).";
            if (err != null) message += " Some may have failed — check the bot's logs.";
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, message))).useComponentsV2(true).queue();
            return null;
        });
    }

    private CompletableFuture<Void> postOrUpdateOne(Guild guild, long guildId, AnnouncementRepository.Destination destination, Container container) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, destination.channelId());
        if (channel == null) return CompletableFuture.completedFuture(null);

        if (destination.messageId() == null) {
            return sendFresh(channel, guildId, destination.id(), container);
        }

        return channel.retrieveMessageById(destination.messageId()).submit()
                .thenCompose(message -> message.editMessageComponents(List.of(container)).useComponentsV2(true).submit())
                .thenApply(message -> (Void) null)
                .exceptionallyCompose(err -> sendFresh(channel, guildId, destination.id(), container));
    }

    private CompletableFuture<Void> sendFresh(GuildMessageChannel channel, long guildId, long destinationId, Container container) {
        return channel.sendMessageComponents(List.of(container)).useComponentsV2(true).submit()
                .thenAccept(sent -> announcementService.setMessageId(guildId, destinationId, sent.getIdLong()));
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    Container buildMainPanel(long guildId) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Announcements\n" +
                "-# Paste a block of text once, post it as the bot in as many channels as you like, and hit **Post / Update** again any time the text changes to update every copy in place."));

        List<Button> buttons = new ArrayList<>();
        buttons.add(Button.secondary("configure_back:_", "Back"));
        for (AnnouncementPreset preset : AnnouncementPreset.values()) {
            int count = announcementService.getDestinations(guildId, preset).size();
            boolean configured = announcementService.getText(guildId, preset) != null;
            String label = preset.displayName() + " (" + count + ")";
            buttons.add(configured ? Button.success("configure_announce_preset:" + preset.name(), label)
                                    : Button.secondary("configure_announce_preset:" + preset.name(), label));
        }
        children.add(ActionRow.of(buttons));

        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildPresetPanel(long guildId, AnnouncementPreset preset) {
        String text = announcementService.getText(guildId, preset);
        List<AnnouncementRepository.Destination> destinations = announcementService.getDestinations(guildId, preset);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + preset.displayName()));
        children.add(TextDisplay.of("-# Post / Update posts this text fresh, or edits the previous copy in place, in every destination below."));
        children.add(ActionRow.of(
                Button.secondary("configure_announce_main:_", "All Announcements"),
                Button.primary("configure_announce_edit:" + preset.name(), "Edit Text"),
                Button.success("configure_announce_post:" + preset.name(), "Post / Update")));

        String preview = text == null ? "*not set*" : text.length() > 300 ? text.substring(0, 300) + "…" : text;
        children.add(TextDisplay.of("**Text**\n" + preview));

        if (destinations.isEmpty()) {
            children.add(TextDisplay.of("**Destinations:** *none yet*"));
        } else {
            StringBuilder list = new StringBuilder("**Destinations:**\n");
            for (AnnouncementRepository.Destination destination : destinations) {
                list.append("• <#").append(destination.channelId()).append(">\n");
            }
            children.add(TextDisplay.of(list.toString().trim()));

            StringSelectMenu.Builder removeMenu = StringSelectMenu.create("configure_announce_remove_dest:" + preset.name())
                    .setPlaceholder("Remove a destination");
            for (AnnouncementRepository.Destination destination : destinations) {
                removeMenu.addOption("Remove #" + destination.channelId(), String.valueOf(destination.id()));
            }
            children.add(ActionRow.of(removeMenu.build()));
        }

        children.add(TextDisplay.of("**Add a destination** (regular channel, forum, or an active thread):"));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_announce_add_channel:" + preset.name(), EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder("Select a channel or thread")
                        .setRequiredRange(0, 1)
                        .build()));
        children.add(Containers.linkButtonRow("configure_announce_add_link:" + preset.name()));

        if (!destinations.isEmpty()) {
            children.add(TextDisplay.of("-# **Clear All Destinations** removes every destination for this preset — there's no undo, so it asks you to confirm first."));
            children.add(ActionRow.of(Button.danger("configure_announce_clear_confirm:" + preset.name(), "Clear All Destinations")));
        }

        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildClearConfirmPanel(long guildId, AnnouncementPreset preset) {
        int count = announcementService.getDestinations(guildId, preset).size();
        return Containers.card(Containers.DANGER,
                TextDisplay.of("### Clear All Destinations — " + preset.displayName()),
                TextDisplay.of("Remove all " + count + " destination(s)? This can't be undone — the text itself is kept."),
                ActionRow.of(
                        Button.danger("configure_announce_clear_go:" + preset.name(), "Yes, Clear All"),
                        Button.secondary("configure_announce_clear_cancel:" + preset.name(), "Cancel")));
    }
}
