package com.younglings.bot.commands.embed;

import com.younglings.bot.embed.PostedEmbed;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@BService
public class EmbedInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(EmbedInteractionListener.class);

    private final EmbedService embedService;

    public EmbedInteractionListener(EmbedService embedService) {
        this.embedService = embedService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getComponentId();
        if (!id.startsWith("embed_") || !id.contains(":")) return;

        try {
            handleButton(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in embed button interaction '{}'", id, e);
            replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getGuild() == null) return;
        String id = event.getModalId();
        if (!id.startsWith("embed_") || !id.contains(":")) return;

        try {
            handleModal(event, id);
        } catch (Exception e) {
            log.error("Unhandled exception in embed modal interaction '{}'", id, e);
            replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, String id) {
        switch (id) {
            case "embed_post:_" -> event.replyModal(buildPostModal()).queue();

            case "embed_remove:_" -> {
                Modal modal = buildRemoveModal(event.getGuild());
                if (modal == null) {
                    event.reply("Nothing is currently tracked as posted.").setEphemeral(true).queue();
                    return;
                }
                event.replyModal(modal).queue();
            }

            case "embed_remove_all:_" -> event.reply(
                            "Are you sure? This removes **every** tracked posted embed in this server.")
                    .setEphemeral(true)
                    .addComponents(ActionRow.of(
                            Button.danger("embed_remove_all_confirm:_", "Yes, remove everything"),
                            Button.secondary("embed_remove_all_cancel:_", "Cancel")
                    ))
                    .queue();

            case "embed_remove_all_confirm:_" -> {
                int count = embedService.removeAll(event.getGuild());
                event.editMessage("Removed " + count + " posted embed(s).")
                        .setComponents()
                        .queue();
            }

            case "embed_remove_all_cancel:_" -> event.editMessage("Cancelled.")
                    .setComponents()
                    .delay(Duration.ofSeconds(3))
                    .flatMap(InteractionHook::deleteOriginal)
                    .queue();
        }
    }

    private void handleModal(ModalInteractionEvent event, String id) {
        switch (id) {
            case "embed_post_modal:_" -> {
                long channelId = event.getValue("embed_post_channel").getAsLongList().getFirst();
                EmbedType type = EmbedType.valueOf(event.getValue("embed_post_type").getAsStringList().getFirst());

                Guild guild = event.getGuild();
                TextChannel channel = guild.getTextChannelById(channelId);
                if (channel == null) {
                    event.reply("That channel isn't a usable text channel.").setEphemeral(true).queue();
                    return;
                }

                event.deferReply(true).queue();
                embedService.postEmbed(guild, channel, type, createdRoles -> {
                    if (createdRoles == null) {
                        event.getHook().editOriginal(
                                "Failed to post the embed — check the bot can send messages in that channel.").queue();
                        return;
                    }

                    String summary = createdRoles.isEmpty()
                            ? ""
                            : "\nCreated " + createdRoles.size() + " new role(s): " + String.join(", ", createdRoles) + ".";
                    event.getHook().editOriginal(
                            "Posted **" + type.displayName() + "** in " + channel.getAsMention() + "." + summary
                    ).queue();
                });
            }

            case "embed_remove_modal:_" -> {
                long channelId = Long.parseLong(event.getValue("embed_remove_channel").getAsStringList().getFirst());
                EmbedType type = EmbedType.valueOf(event.getValue("embed_remove_type").getAsStringList().getFirst());

                boolean removed = embedService.removeOne(event.getGuild(), channelId, type);
                event.reply(removed
                                ? "Removed the **" + type.displayName() + "** embed from that channel."
                                : "No **" + type.displayName() + "** embed is tracked in that channel.")
                        .setEphemeral(true)
                        .delay(Duration.ofSeconds(5))
                        .flatMap(InteractionHook::deleteOriginal)
                        .queue();
            }
        }
    }

    private Modal buildPostModal() {
        EntitySelectMenu channelSelect = EntitySelectMenu.create("embed_post_channel", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Where to post it")
                .setRequiredRange(1, 1)
                .build();

        StringSelectMenu.Builder typeSelectBuilder = StringSelectMenu.create("embed_post_type")
                .setPlaceholder("Which embed?")
                .setRequiredRange(1, 1);
        for (EmbedType type : EmbedType.values()) {
            typeSelectBuilder.addOption(type.displayName(), type.name());
        }

        return Modal.create("embed_post_modal:_", "Post an Embed")
                .addComponents(
                        Label.of("Channel", channelSelect),
                        Label.of("Embed", typeSelectBuilder.build())
                )
                .build();
    }

    /**
     * Both dropdowns are custom-built from tracked data rather than Discord's native channel
     * picker, since a channel select can only be restricted by {@link ChannelType}, never to an
     * arbitrary curated list — so "only channels/types that actually have something posted" has to
     * be built by hand from {@link EmbedService#getPostedInGuild}. Returns {@code null} if nothing
     * is tracked at all. The two selects aren't mutually filtered (a modal can't do that — nothing
     * in it can react to another field's live value), so an invalid combination is just rejected
     * with a clear message on submit rather than being impossible to pick in the first place.
     */
    private Modal buildRemoveModal(Guild guild) {
        List<PostedEmbed> posted = embedService.getPostedInGuild(guild.getIdLong());
        if (posted.isEmpty()) return null;

        Set<Long> channelIds = new LinkedHashSet<>();
        Set<String> types = new LinkedHashSet<>();
        for (PostedEmbed p : posted) {
            channelIds.add(p.channelId());
            types.add(p.embedType());
        }

        StringSelectMenu.Builder channelSelectBuilder = StringSelectMenu.create("embed_remove_channel")
                .setPlaceholder("Which channel?")
                .setRequiredRange(1, 1);
        for (long channelId : channelIds) {
            TextChannel channel = guild.getTextChannelById(channelId);
            String label = channel != null ? "#" + channel.getName() : "Unknown channel (" + channelId + ")";
            channelSelectBuilder.addOption(label, String.valueOf(channelId));
        }

        StringSelectMenu.Builder typeSelectBuilder = StringSelectMenu.create("embed_remove_type")
                .setPlaceholder("Which embed?")
                .setRequiredRange(1, 1);
        for (String typeName : types) {
            EmbedType type = EmbedType.valueOf(typeName);
            typeSelectBuilder.addOption(type.displayName(), type.name());
        }

        return Modal.create("embed_remove_modal:_", "Remove an Embed")
                .addComponents(
                        Label.of("Channel", channelSelectBuilder.build()),
                        Label.of("Embed", typeSelectBuilder.build())
                )
                .build();
    }

    private void replyError(ButtonInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.reply("An unexpected error occurred. Please try again or contact an admin.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }

    private void replyError(ModalInteractionEvent event) {
        try {
            if (!event.isAcknowledged()) {
                event.reply("An unexpected error occurred. Please try again or contact an admin.")
                        .setEphemeral(true).queue();
            }
        } catch (Exception ignored) {}
    }
}
