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
     * What sits at the top of a ticket: a welcome line (editable on the panel, {@code {user}} is the requester), a support
     * embed with who opened it, and an embed with every question and its answer, then the buttons. Laid out like Ticket
     * Tool's so it will feel familiar. Plain messages, not Components V2, because embeds can't share a message with those.
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

        String template = panel == null || panel.openingMessage() == null || panel.openingMessage().isBlank() ? Panel.DEFAULT_OPENING : panel.openingMessage();
        String content = truncate(template.replace("{user}", "<@" + ticket.requesterId() + ">"), 1800);
        if (pingRoleId != null && !closed) content += "\n🔔 <@&" + pingRoleId + ">";

        String support = panel == null || panel.welcomeText() == null || panel.welcomeText().isBlank() ? Panel.DEFAULT_SUPPORT : truncate(panel.welcomeText(), 1500);
        EmbedBuilder info = new EmbedBuilder().setColor(color).setDescription(support)
                .setFooter("Ticket #" + String.format("%04d", ticket.number()) + (panel != null ? " · " + panel.title() : ""));
        info.addField("Opened by", "<@" + ticket.requesterId() + ">", true);
        if (!rsns.isEmpty()) info.addField("RuneScape name", truncate(String.join(", ", rsns), 1000), true);
        if (ticket.routingLabel() != null) info.addField("Type", truncate(ticket.routingLabel(), 1000), true);
        if (panel != null && panel.usesHelpers()) info.addField("Helping", helpersLine(panel.helperCap(), helperIds, closed), false);
        if (closed) {
            StringBuilder end = new StringBuilder("🔒 Closed");
            if (ticket.closedBy() != null) end.append(" by <@").append(ticket.closedBy()).append(">");
            if (ticket.closeReason() != null && !ticket.closeReason().isBlank()) end.append("\n").append(truncate(ticket.closeReason(), 500));
            info.addField("Status", end.toString(), false);
        }

        List<MessageEmbed> embeds = new ArrayList<>();
        embeds.add(info.build());

        // Each question in bold with the answer in a code block underneath, as Ticket Tool shows them.
        StringBuilder qa = new StringBuilder();
        for (Answer answer : ticket.answers()) {
            if (answer.answer() == null || answer.answer().isBlank()) continue;
            qa.append("**").append(truncate(answer.label(), 200)).append("**\n```\n")
                    .append(truncate(answer.answer().replace("```", "ˋˋˋ"), ANSWER_MAX)).append("\n```\n");
        }
        if (!qa.isEmpty()) embeds.add(new EmbedBuilder().setColor(color).setDescription(truncate(qa.toString().strip(), 3900)).build());

        List<Button> buttons = new ArrayList<>();
        if (panel != null && panel.usesHelpers()) {
            Button join = Button.success(JOIN_PREFIX + ticket.id(), "Join as helper");
            buttons.add(closed ? join.asDisabled() : join);
        }
        Button close = Button.danger(CLOSE_PREFIX + ticket.id(), "Close");
        buttons.add(closed ? close.asDisabled() : close);

        return new Opening(content, embeds, ActionRow.of(buttons));
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
