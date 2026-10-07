package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The message where members read the helper guidelines and take the PVM Helper role. It is posted by an admin (from /configure or the
 * website), edited in place whenever the guidelines change, and its buttons are handled by {@link HelpListener}. Nothing about it is held
 * in memory: where it was posted lives in the database, so it keeps working across a restart.
 */
@BService
public class HelpOnboarding {
    private static final Logger log = LoggerFactory.getLogger(HelpOnboarding.class);

    static final String ACCEPT_ID = "pvmhelp_accept";
    static final String LEAVE_ID = "pvmhelp_leave";

    private final TicketRepository repository;

    public HelpOnboarding(TicketRepository repository) {
        this.repository = repository;
    }

    /** The guidelines, a line saying what pressing the button means, then the two buttons. */
    static Container message(HelpSettings settings) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Become a PVM Helper"));
        children.add(TextDisplay.of(truncate(settings.guidelinesOrDefault(), HelpSettings.MAX_GUIDELINES)));
        children.add(TextDisplay.of("-# Pressing the green button means you have read these guidelines and agree to them. You can hand the role back at any time."));
        children.add(ActionRow.of(
                Button.success(ACCEPT_ID, "I agree — make me a PVM Helper"),
                Button.secondary(LEAVE_ID, "Stop being a PVM Helper")));
        return Containers.card(Containers.PRIMARY, children);
    }

    /** Posts the guidelines message in {@code channel}, or edits the one already posted there, and remembers where it is. */
    public CompletableFuture<Void> post(Guild guild, GuildMessageChannel channel) {
        HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
        Container container = message(settings);
        boolean sameChannel = settings.postedChannelId() != null && settings.postedChannelId() == channel.getIdLong() && settings.postedMessageId() != null;

        CompletableFuture<Message> result = new CompletableFuture<>();
        if (sameChannel) {
            channel.retrieveMessageById(settings.postedMessageId()).queue(
                    existing -> existing.editMessageComponents(container).useComponentsV2(true).queue(result::complete, result::completeExceptionally),
                    error -> send(channel, container, result));
        } else {
            send(channel, container, result);
        }
        return result.thenAccept(message -> repository.setHelpPosted(guild.getIdLong(), channel.getIdLong(), message.getIdLong()));
    }

    private static void send(GuildMessageChannel channel, Container container, CompletableFuture<Message> result) {
        channel.sendMessageComponents(container).useComponentsV2(true).queue(result::complete, result::completeExceptionally);
    }

    /** If the guidelines message is already posted, brings it up to date with the saved text. Best effort: the save itself has already succeeded. */
    public void refreshPosted(Guild guild) {
        HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
        if (settings.postedChannelId() == null || settings.postedMessageId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.postedChannelId());
        if (channel == null) return;

        channel.retrieveMessageById(settings.postedMessageId()).queue(
                message -> message.editMessageComponents(message(settings)).useComponentsV2(true).queue(null,
                        error -> log.warn("Couldn't refresh the helper guidelines message", error)),
                error -> log.warn("Couldn't find the helper guidelines message to refresh it", error));
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}
