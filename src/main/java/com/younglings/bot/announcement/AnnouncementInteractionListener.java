package com.younglings.bot.announcement;

import com.younglings.bot.commands.configure.ConfigureInteractionListener;
import com.younglings.bot.commands.embed.EmbedHubPanel;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.DiscordLinks;
import com.younglings.bot.i18n.Text;
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.you_need_the_administrator_permission_to"));
                return;
            }

            String[] parts = id.split(":");
            switch (parts[0]) {
                case "configure_announce_main" -> event.editComponents(List.of(buildMainPanel(guild.getIdLong()))).useComponentsV2(true).queue();
                case "configure_announce_premade" -> event.editComponents(List.of(EmbedHubPanel.build("configure_announce_main:_"))).useComponentsV2(true).queue();
                case "configure_announce_preset", "configure_announce_clear_cancel" ->
                        event.editComponents(List.of(buildPresetPanel(guild.getIdLong(), AnnouncementPreset.valueOf(parts[1])))).useComponentsV2(true).queue();
                case "configure_announce_edit" -> doEditTextPrompt(event, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_preview" -> doPreview(event, guild, AnnouncementPreset.valueOf(parts[1]));
                case "configure_announce_legend" -> event.replyComponents(List.of(Containers.toast(Containers.INFO, PostMarkup.LEGEND)))
                        .useComponentsV2(true).setEphemeral(true).queue();
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.you_need_the_administrator_permission_to"));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.you_need_the_administrator_permission_to"));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.you_need_the_administrator_permission_to"));
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
                .setPlaceholder(Text.of(event, "announcement.your_text_tags_on_their_own"))
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

        PostMarkup.Parsed parsed = text == null ? null : PostMarkup.parse(text, false);
        if (parsed == null || parsed.problems().isEmpty()) {
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "announcement.text_saved_preview_it_then_click"));
        } else {
            Containers.replyEphemeral(event, parsed.hasErrors() ? Containers.WARNING : Containers.SUCCESS,
                    Text.of(event, parsed.hasErrors() ? "announcement.text_saved_cant_post" : "announcement.text_saved_heads_up"), parsed.problemsText());
        }
    }

    private void doAddLinkPrompt(ButtonInteractionEvent event, AnnouncementPreset preset) {
        TextInput linkInput = TextInput.create("link_value", TextInputStyle.SHORT)
                .setPlaceholder(Text.of(event, "announcement.paste_a_channel_thread_link_or"))
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
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.couldn_t_find_a_channel_thread"));
            return;
        }
        if (guild.getChannelById(GuildMessageChannel.class, channelId) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.that_doesn_t_look_like_a"));
            return;
        }

        announcementService.addDestination(guild.getIdLong(), preset, channelId);
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "announcement.added_as_a_destination_for", channelId, preset.displayName()));
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
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.set_both_the_text_and_at"));
            return;
        }

        PostMarkup.Parsed parsed = PostMarkup.parse(text, true);
        if (parsed.hasErrors()) {
            Containers.replyEphemeral(event, Containers.DANGER, Text.of(event, "announcement.nothing_was_posted_fix_this_first"), parsed.problemsText());
            return;
        }

        event.deferReply(true).queue();
        Container container = parsed.toContainer(Containers.PRIMARY);

        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (AnnouncementRepository.Destination destination : destinations) {
            pending.add(postOrUpdateOne(guild, guildId, destination, container));
        }

        int total = destinations.size();
        CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).handle((v, err) -> {
            String message = "Posted/updated **" + preset.displayName() + "** in " + total + " destination(s).";
            if (err != null) message += " Some may have failed — check the bot's logs.";
            if (!parsed.problems().isEmpty()) message += "\n" + parsed.problemsText();
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, message))).useComponentsV2(true).queue();
            return null;
        });
    }

    /** Shows the post as it will look — buttons disabled so nothing in the preview does anything — followed by any problems. */
    private void doPreview(ButtonInteractionEvent event, Guild guild, AnnouncementPreset preset) {
        String text = announcementService.getText(guild.getIdLong(), preset);
        if (text == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "announcement.there_no_text_yet_click_edit"));
            return;
        }

        PostMarkup.Parsed parsed = PostMarkup.parse(text, false);
        List<net.dv8tion.jda.api.components.MessageTopLevelComponent> components = new ArrayList<>();
        components.add(parsed.toContainer(Containers.PRIMARY));
        components.add(Containers.toast(parsed.hasErrors() ? Containers.DANGER : parsed.problems().isEmpty() ? Containers.SUCCESS : Containers.WARNING,
                parsed.problems().isEmpty() ? Text.of(event, "announcement.looks_good_post_update_will_publish") : parsed.problemsText()));
        event.replyComponents(components).useComponentsV2(true).setEphemeral(true).queue();
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
        children.add(TextDisplay.of(Text.of(guildId, "announcement.embedded_posts_paste_a_block_of")));

        List<Button> buttons = new ArrayList<>();
        for (AnnouncementPreset preset : AnnouncementPreset.values()) {
            int count = announcementService.getDestinations(guildId, preset).size();
            boolean configured = announcementService.getText(guildId, preset) != null;
            String label = preset.displayName() + " (" + count + ")";
            buttons.add(configured ? Button.success("configure_announce_preset:" + preset.name(), label)
                                    : Button.secondary("configure_announce_preset:" + preset.name(), label));
        }
        children.add(ActionRow.of(buttons));
        children.add(TextDisplay.of(Text.of(guildId, "announcement.pre_made_embeds_are_the_ones")));
        children.add(ActionRow.of(Button.secondary("configure_announce_premade:_", Text.of(guildId, "announcement.pre_made_embeds"))));
        children.add(ActionRow.of(Button.primary("configure_back:_", Text.of(guildId, "announcement.back"))));
        children.add(Containers.autoCloseNote());

        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildPresetPanel(long guildId, AnnouncementPreset preset) {
        String text = announcementService.getText(guildId, preset);
        List<AnnouncementRepository.Destination> destinations = announcementService.getDestinations(guildId, preset);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + preset.displayName()));
        children.add(TextDisplay.of(Text.of(guildId, "announcement.post_update_posts_this_text_fresh")));
        children.add(ActionRow.of(
                Button.primary("configure_announce_edit:" + preset.name(), Text.of(guildId, "announcement.edit_text")),
                Button.secondary("configure_announce_preview:" + preset.name(), Text.of(guildId, "announcement.preview")),
                Button.secondary("configure_announce_legend:_", Text.of(guildId, "announcement.legend")),
                Button.success("configure_announce_post:" + preset.name(), Text.of(guildId, "announcement.post_update"))));

        String preview = text == null ? "*not set*" : text.length() > 300 ? text.substring(0, 300) + "…" : text;
        children.add(TextDisplay.of(Text.of(guildId, "announcement.text", preview)));

        if (destinations.isEmpty()) {
            children.add(TextDisplay.of(Text.of(guildId, "announcement.destinations_none_yet")));
        } else {
            StringBuilder list = new StringBuilder("**Destinations:**\n");
            for (AnnouncementRepository.Destination destination : destinations) {
                list.append("• <#").append(destination.channelId()).append(">\n");
            }
            children.add(TextDisplay.of(list.toString().trim()));

            StringSelectMenu.Builder removeMenu = StringSelectMenu.create("configure_announce_remove_dest:" + preset.name())
                    .setPlaceholder(Text.of(guildId, "announcement.remove_a_destination"));
            for (AnnouncementRepository.Destination destination : destinations) {
                removeMenu.addOption("Remove #" + destination.channelId(), String.valueOf(destination.id()));
            }
            children.add(ActionRow.of(removeMenu.build()));
        }

        children.add(TextDisplay.of(Text.of(guildId, "announcement.add_a_destination_regular_channel_forum")));
        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_announce_add_channel:" + preset.name(), EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD, ChannelType.FORUM)
                        .setPlaceholder(Text.of(guildId, "announcement.select_a_channel_or_thread"))
                        .setRequiredRange(0, 1)
                        .build()));
        children.add(Containers.linkButtonRow("configure_announce_add_link:" + preset.name()));

        if (!destinations.isEmpty()) {
            children.add(TextDisplay.of(Text.of(guildId, "announcement.clear_all_destinations_removes_every_destination")));
            children.add(ActionRow.of(Button.danger("configure_announce_clear_confirm:" + preset.name(), Text.of(guildId, "announcement.clear_all_destinations"))));
        }

        children.add(ActionRow.of(Button.primary("configure_announce_main:_", Text.of(guildId, "announcement.back"))));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    private Container buildClearConfirmPanel(long guildId, AnnouncementPreset preset) {
        int count = announcementService.getDestinations(guildId, preset).size();
        return Containers.card(Containers.DANGER,
                TextDisplay.of(Text.of(guildId, "announcement.clear_all_destinations_2", preset.displayName())),
                TextDisplay.of(Text.of(guildId, "announcement.remove_all_destination_this_can_t", count)),
                ActionRow.of(
                        Button.danger("configure_announce_clear_go:" + preset.name(), Text.of(guildId, "announcement.yes_clear_all")),
                        Button.secondary("configure_announce_clear_cancel:" + preset.name(), Text.of(guildId, "announcement.cancel"))));
    }
}
