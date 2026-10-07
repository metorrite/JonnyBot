package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    static final String PINGS_ID = "pvmhelp_pings";
    static final String PING_SELECT_ID = "pvmhelp_pings_select";

    private final TicketRepository repository;

    public HelpOnboarding(TicketRepository repository) {
        this.repository = repository;
    }

    /** The guidelines, a line saying what pressing the button means, then the buttons: agree, ping settings, step down. */
    static Container message(HelpSettings settings) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Sign up as a PVM Helper"));
        children.add(TextDisplay.of(truncate(settings.guidelinesOrDefault(), HelpSettings.MAX_GUIDELINES)));
        children.add(TextDisplay.of("-# Pressing the green button means you have read these guidelines and agree to them. Once you're a helper, **Ping Settings** lets you "
                + "choose which tiers ping you. You can hand the role back at any time."));
        children.add(ActionRow.of(
                Button.success(ACCEPT_ID, "I agree — sign me up"),
                Button.primary(PINGS_ID, "Ping Settings"),
                Button.secondary(LEAVE_ID, "Stop being a PVM Helper")));
        return Containers.card(Containers.PRIMARY, children);
    }

    /**
     * A helper's own ping settings, shown only to them: the tiers their role allows, ticked where they asked to be pinged. A PVM Helper
     * can be pinged for the tiers below Master; a PVM Helper+ for every tier. Every helper can see and join any ticket either way.
     */
    static Container pingSettings(HelpSettings settings, HelpPingService.HelperLevel level, Set<String> chosen) {
        boolean plus = level == HelpPingService.HelperLevel.HELPER_PLUS;
        List<String> allowed = HelpRules.allowedTiers(settings, plus);
        List<String> ticked = allowed.stream().filter(chosen::contains).toList();

        List<ContainerChildComponent> children = new ArrayList<>();
        StringBuilder text = new StringBuilder("### Your ping settings\n");
        if (plus) text.append("You're a **PVM Helper+**, so you can be pinged for every tier.");
        else text.append("You're a **PVM Helper**, so you can be pinged for the tiers below ").append(String.join(" and ", HelpRules.highTierLabels(settings)))
                .append(". Those tickets won't ping you, but you can still see and join them.");
        text.append("\n**Pinged for:** ").append(ticked.isEmpty() ? "*nothing. No ticket will ping you.*" : String.join(", ", ticked));
        children.add(TextDisplay.of(text.toString()));
        children.add(TextDisplay.of("Choose the tiers you want to hear about. Clear them all to turn pings off."));

        StringSelectMenu.Builder menu = StringSelectMenu.create(PING_SELECT_ID)
                .setPlaceholder("Choose tiers")
                .setRequiredRange(0, allowed.size());
        for (String tier : allowed) menu.addOption(tier.equals(HelpRules.GENERAL) ? "General help (no tier picked)" : tier, tier);
        if (!ticked.isEmpty()) menu.setDefaultValues(ticked);
        children.add(ActionRow.of(menu.build()));

        if (!settings.guestPingsEnabled()) children.add(TextDisplay.of("-# Tickets opened by guests (anyone who isn't in the clan) don't ping anyone yet."));
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
