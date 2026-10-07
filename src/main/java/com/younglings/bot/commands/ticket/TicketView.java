package com.younglings.bot.commands.ticket;

import com.younglings.bot.announcement.PostMarkup;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditData;

import java.awt.Color;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** What the ticket system shows: the panel people press to open a ticket, the message at the top of each ticket, and the saved transcript text. */
final class TicketView {
    private TicketView() {}

    static final String OPEN_PREFIX = "ticket_open:";
    static final String JOIN_PREFIX = "ticket_join:";
    static final String CLOSE_PREFIX = "ticket_close:";

    private static final int ANSWER_MAX = 500;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    // ---------- the panel ----------

    /**
     * The title, the panel's description (written with the same tags as an Embedded Post, so it can have
     * dividers, images and buttons), then the Open button. A description with a tag mistake is shown as
     * plain text rather than breaking the panel.
     */
    static Container panelMessage(Panel panel) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + panel.title()));

        if (!panel.description().isBlank()) {
            PostMarkup.Parsed parsed = PostMarkup.parse(panel.description(), true);
            if (parsed.hasErrors()) children.add(TextDisplay.of(truncate(panel.description(), 1500)));
            else children.addAll(parsed.children());
        }

        Button open = Button.primary(OPEN_PREFIX + panel.id(), panel.buttonLabel());
        children.add(ActionRow.of(panel.enabled() ? open : open.asDisabled()));
        return Containers.card(Containers.PRIMARY, children);
    }

    // ---------- a ticket ----------

    /**
     * What sits at the top of a ticket, laid out like Ticket Tool's so it feels familiar: a plain-text welcome line, an embed
     * with the panel's message, an info block with the requester's answers (each question in bold, the answer in a code box
     * beneath it), then the buttons, with Close last. The welcome line and the embed's text are the panel's own wording and
     * can use the placeholders listed in {@link TicketText}. Plain messages, not Components V2, because embeds can't share a
     * message with those.
     */
    record Opening(String content, List<MessageEmbed> embeds, ActionRow buttons) {
        MessageCreateData toCreate() {
            return new MessageCreateBuilder().setContent(content).setEmbeds(embeds).setComponents(buttons).build();
        }

        MessageEditData toEdit() {
            return new MessageEditBuilder().setContent(content).setEmbeds(embeds).setComponents(buttons).build();
        }
    }

    static Opening opening(Panel panel, Ticket ticket, List<String> rsns, List<Long> helperIds, Long pingRoleId) {
        boolean closed = ticket.status() == Status.CLOSED;
        Color color = closed ? Color.DARK_GRAY : Containers.PRIMARY;

        // The plain-text line. A role ping has to live in the message itself to notify anyone, so unless the wording places it
        // with {ping}, it goes on a line of its own.
        String template = panel == null || panel.openingMessage() == null || panel.openingMessage().isBlank() ? Panel.DEFAULT_OPENING : panel.openingMessage();
        String content = truncate(TicketText.fill(template, panel, ticket, rsns, helperIds, pingRoleId), 1800);
        if (pingRoleId != null && !closed && !TicketText.usesPing(template)) content += "\n🔔 <@&" + pingRoleId + ">";

        // The panel's embedded message, then whether anyone has joined and (once closed) how it ended.
        String supportTemplate = panel == null || panel.welcomeText() == null || panel.welcomeText().isBlank() ? Panel.DEFAULT_SUPPORT : panel.welcomeText();
        StringBuilder support = new StringBuilder(truncate(TicketText.fill(supportTemplate, panel, ticket, rsns, helperIds, pingRoleId), 1500));
        if (panel != null && panel.usesHelpers()) support.append("\n\n").append(helpersLine(panel.helperCap(), helperIds, closed));
        if (closed) {
            support.append("\n\n🔒 Closed");
            if (ticket.closedBy() != null) support.append(" by <@").append(ticket.closedBy()).append(">");
            if (ticket.closeReason() != null && !ticket.closeReason().isBlank()) support.append("\n").append(truncate(ticket.closeReason(), 500));
        }
        EmbedBuilder message = new EmbedBuilder().setColor(color).setDescription(support.toString())
                .setFooter("Ticket #" + String.format("%04d", ticket.number()) + (panel != null ? " · " + panel.title() : ""));

        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(message.build());

        // The info block: each question in bold with the answer in a code box underneath, as Ticket Tool shows them. The linked
        // RuneScape name comes first, since a helper wants it before anything else.
        StringBuilder info = new StringBuilder();
        if (!rsns.isEmpty()) appendEntry(info, "RuneScape name", String.join(", ", rsns));
        for (Answer answer : ticket.answers()) {
            if (answer.answer() == null || answer.answer().isBlank()) continue;
            appendEntry(info, answer.label(), answer.answer());
        }
        if (!info.isEmpty()) embeds.add(new EmbedBuilder().setColor(color).setDescription(truncate(info.toString().strip(), 3900)).build());

        List<Button> buttons = new ArrayList<>();
        if (panel != null && panel.usesHelpers()) {
            Button join = Button.success(JOIN_PREFIX + ticket.id(), "Join as helper");
            buttons.add(closed ? join.asDisabled() : join);
        }
        Button close = Button.danger(CLOSE_PREFIX + ticket.id(), "Close").withEmoji(Emoji.fromUnicode("🔒"));
        buttons.add(closed ? close.asDisabled() : close);

        return new Opening(content, embeds, ActionRow.of(buttons));
    }

    private static void appendEntry(StringBuilder into, String question, String answer) {
        into.append("**").append(truncate(question, 200)).append("**\n```\n")
                .append(truncate(answer.replace("```", "ˋˋˋ"), ANSWER_MAX)).append("\n```\n");
    }

    /** The older Components V2 layout, kept so tickets opened before the change keep updating in place. */

    static Container welcome(Panel panel, Ticket ticket, List<String> rsns, List<Long> helperIds, Long pingRoleId) {
        boolean closed = ticket.status() == Status.CLOSED;
        String title = panel != null ? panel.title() : "Ticket";

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### 🎫 " + title + " · #" + String.format("%04d", ticket.number()) + (closed ? " — Closed" : "")));

        StringBuilder who = new StringBuilder();
        if (pingRoleId != null && !closed) who.append("🔔 <@&").append(pingRoleId).append(">\n");
        who.append("Opened by <@").append(ticket.requesterId()).append(">");
        if (!rsns.isEmpty()) who.append("\n**RuneScape name:** ").append(String.join(", ", rsns));
        if (ticket.routingLabel() != null) who.append("\n**Type:** ").append(ticket.routingLabel());
        children.add(TextDisplay.of(who.toString()));

        StringBuilder answers = new StringBuilder();
        for (Answer answer : ticket.answers()) {
            if (answer.answer() == null || answer.answer().isBlank()) continue;
            if (!answers.isEmpty()) answers.append("\n\n");
            answers.append("**").append(answer.label()).append("**\n").append(truncate(answer.answer(), ANSWER_MAX));
        }
        if (!answers.isEmpty()) children.add(TextDisplay.of(answers.toString()));

        if (panel != null && !panel.welcomeText().isBlank()) children.add(TextDisplay.of(truncate(panel.welcomeText(), 800)));

        if (panel != null && panel.usesHelpers()) children.add(TextDisplay.of(helpersLine(panel.helperCap(), helperIds, closed)));

        if (closed) {
            StringBuilder end = new StringBuilder("🔒 Closed");
            if (ticket.closedBy() != null) end.append(" by <@").append(ticket.closedBy()).append(">");
            if (ticket.closeReason() != null && !ticket.closeReason().isBlank()) end.append("\n").append(truncate(ticket.closeReason(), 500));
            children.add(TextDisplay.of(end.toString()));
        }

        List<Button> buttons = new ArrayList<>();
        if (panel != null && panel.usesHelpers()) {
            Button join = Button.success(JOIN_PREFIX + ticket.id(), "Join as helper");
            buttons.add(closed ? join.asDisabled() : join);
        }
        Button close = Button.danger(CLOSE_PREFIX + ticket.id(), "Close");
        buttons.add(closed ? close.asDisabled() : close);
        children.add(ActionRow.of(buttons));

        return Containers.card(closed ? Color.DARK_GRAY : Containers.PRIMARY, children);
    }

    private static String helpersLine(int cap, List<Long> helperIds, boolean closed) {
        if (helperIds.isEmpty()) return closed ? "*No helper joined.*" : "*Nobody has joined yet — press **Join as helper** to take this one.*";
        List<String> mentions = helperIds.stream().map(id -> "<@" + id + ">").toList();
        return "**Helping (" + helperIds.size() + "/" + cap + "):** " + String.join(", ", mentions);
    }

    // ---------- transcript ----------

    record Line(OffsetDateTime time, String author, String text) {}

    /** A plain-text record of a ticket: who and when, then every message in order. */
    static String transcript(Ticket ticket, String panelTitle, String requesterName, List<String> helperNames, List<Line> lines) {
        StringBuilder out = new StringBuilder();
        out.append("Ticket #").append(String.format("%04d", ticket.number()));
        if (panelTitle != null) out.append(" — ").append(panelTitle);
        out.append("\n");
        out.append("Opened by: ").append(requesterName).append("\n");
        if (ticket.routingLabel() != null) out.append("Type: ").append(ticket.routingLabel()).append("\n");
        out.append("Opened: ").append(ticket.createdAt().withOffsetSameInstant(ZoneOffset.UTC).format(STAMP)).append("\n");
        if (ticket.closedAt() != null) out.append("Closed: ").append(ticket.closedAt().withOffsetSameInstant(ZoneOffset.UTC).format(STAMP)).append("\n");
        if (ticket.closeReason() != null && !ticket.closeReason().isBlank()) out.append("Reason: ").append(ticket.closeReason()).append("\n");
        out.append("Helpers: ").append(helperNames.isEmpty() ? "none" : String.join(", ", helperNames)).append("\n");
        out.append("Messages: ").append(lines.size()).append("\n");
        out.append("=".repeat(60)).append("\n\n");

        for (Line line : lines) {
            out.append("[").append(line.time().withOffsetSameInstant(ZoneOffset.UTC).format(STAMP)).append("] ").append(line.author()).append(": ");
            String[] parts = line.text().split("\\R", -1);
            out.append(parts[0]);
            for (int i = 1; i < parts.length; i++) out.append("\n    ").append(parts[i]);
            out.append("\n");
        }
        return out.toString();
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max - 1) + "…" : text;
    }
}
