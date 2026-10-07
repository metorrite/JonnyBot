package com.younglings.bot.commands.ticket;

import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TicketViewTest {
    private static Panel panel(String description, Integer helperCap, boolean enabled) {
        return new Panel(4, 1, "CA Help", "Combat Achievement Help", description, "Request CA help", null, "ca-{number}",
                "A helper will join you.", enabled, 1, null, helperCap, 24, null, null, null);
    }

    private static Ticket ticket(Status status, List<Answer> answers) {
        return new Ticket(9, 1, 4L, 3, 55L, 100L, status, null, "Elite", answers, 66L, OffsetDateTime.now(), null,
                status == Status.CLOSED ? OffsetDateTime.now() : null, status == Status.CLOSED ? 101L : null, status == Status.CLOSED ? "Done" : null, false);
    }

    private static void assertFits(Container container) {
        new MessageCreateBuilder().useComponentsV2(true).setComponents(container).build();
    }

    private static String text(Container container) {
        return String.join("\n", ComponentTree.of(List.of(container)).findAll(TextDisplay.class).stream().map(TextDisplay::getContent).toList());
    }

    private static List<Button> buttons(Container container) {
        return ComponentTree.of(List.of(container)).findAll(Button.class);
    }

    // ---------- the panel ----------

    @Test
    void thePanelShowsTitleDescriptionAndTheOpenButton() {
        Container message = TicketView.panelMessage(panel("Need a hand?\n~<LS>~\nTeamforming first.", 2, true));
        assertFits(message);
        assertTrue(text(message).contains("### Combat Achievement Help"));
        assertTrue(text(message).contains("Need a hand?"));
        assertEquals(List.of("ticket_open:4"), buttons(message).stream().map(Button::getCustomId).toList());
        assertFalse(buttons(message).getFirst().isDisabled());
    }

    @Test
    void aDisabledPanelHasADisabledButton() {
        assertTrue(buttons(TicketView.panelMessage(panel("x", 2, false))).getFirst().isDisabled());
    }

    @Test
    void aDescriptionWithATagMistakeStillPostsAsPlainText() {
        Container message = TicketView.panelMessage(panel("Hello ~<NOPE>~", 2, true));
        assertFits(message);
        assertTrue(text(message).contains("Hello"));
    }

    // ---------- the ticket message ----------

    @Test
    void theTicketMessageShowsWhoWhatAndHowManyHelpers() {
        Container message = TicketView.welcome(panel("", 2, true), ticket(Status.OPEN, List.of(new Answer("Which achievement(s)?", "Amascut elites"), new Answer("Notes", ""))),
                List.of("Metorrite"), List.of(201L), 700L);
        assertFits(message);

        String text = text(message);
        assertTrue(text.contains("#0003"));
        assertTrue(text.contains("<@&700>"), "the tier's role is pinged in the opening message");
        assertTrue(text.contains("Opened by <@100>"));
        assertTrue(text.contains("**RuneScape name:** Metorrite"));
        assertTrue(text.contains("**Type:** Elite"));
        assertTrue(text.contains("**Which achievement(s)?**\nAmascut elites"));
        assertFalse(text.contains("**Notes**"), "blank answers are left out");
        assertTrue(text.contains("**Helping (1/2):** <@201>"));
        assertEquals(List.of("ticket_join:9", "ticket_close:9"), buttons(message).stream().map(Button::getCustomId).toList());
    }

    @Test
    void withNoHelpersTheMessageInvitesSomeoneToJoin() {
        String text = text(TicketView.welcome(panel("", 2, true), ticket(Status.OPEN, List.of()), List.of(), List.of(), null));
        assertTrue(text.contains("Nobody has joined yet"));
    }

    @Test
    void aPanelWithoutHelpersHasNoJoinButtonOrHelperLine() {
        Container message = TicketView.welcome(panel("", null, true), ticket(Status.OPEN, List.of()), List.of(), List.of(), null);
        assertEquals(List.of("ticket_close:9"), buttons(message).stream().map(Button::getCustomId).toList());
        assertFalse(text(message).contains("Helping"));
    }

    @Test
    void aClosedTicketDisablesItsButtonsAndSaysWhoClosedIt() {
        Container message = TicketView.welcome(panel("", 2, true), ticket(Status.CLOSED, List.of()), List.of(), List.of(), 700L);
        assertTrue(buttons(message).stream().allMatch(Button::isDisabled));
        String text = text(message);
        assertTrue(text.contains("— Closed"));
        assertTrue(text.contains("Closed by <@101>"));
        assertFalse(text.contains("<@&700>"), "no more pings once it's closed");
    }

    @Test
    void theLongestRealisticTicketStillFitsInOneMessage() {
        List<Answer> answers = new ArrayList<>();
        for (int i = 0; i < 5; i++) answers.add(new Answer("Question number " + i, "x".repeat(1000)));
        List<Long> helpers = new ArrayList<>();
        for (long i = 0; i < 25; i++) helpers.add(900_000_000_000_000_000L + i);
        assertFits(TicketView.welcome(panel("", 25, true), ticket(Status.OPEN, answers), List.of("A", "B", "C"), helpers, 700L));
    }

    // ---------- transcript ----------

    @Test
    void theTranscriptListsTheTicketThenEveryMessageInOrder() {
        Ticket closed = ticket(Status.CLOSED, List.of());
        String transcript = TicketView.transcript(closed, "Combat Achievement Help", "Metorrite", List.of("Helper One"),
                List.of(new TicketView.Line(OffsetDateTime.parse("2026-10-05T17:00:00Z"), "Metorrite", "hello\nsecond line"),
                        new TicketView.Line(OffsetDateTime.parse("2026-10-05T17:01:00Z"), "JonnyBot [bot]", "Welcome")));
        assertTrue(transcript.startsWith("Ticket #0003 — Combat Achievement Help"));
        assertTrue(transcript.contains("Opened by: Metorrite"));
        assertTrue(transcript.contains("Helpers: Helper One"));
        assertTrue(transcript.contains("Reason: Done"));
        assertTrue(transcript.contains("Messages: 2"));
        assertTrue(transcript.indexOf("hello") < transcript.indexOf("Welcome"));
        assertTrue(transcript.contains("[2026-10-05 17:00 UTC] Metorrite: hello\n    second line"), "later lines of a message are indented");
    }

    // ---------- the form ----------

    @Test
    void theSamplePanelIsValidAndItsFormHasOneInputPerQuestion() {
        PanelDefinition sample = DevTicketPostCommand.sample(1);
        assertTrue(TicketRules.validatePanel(sample.panel(), sample.fields()).isEmpty());

        // Give every field a distinct id the way the database would.
        List<Field> withIds = new ArrayList<>();
        long id = 1;
        for (Field f : sample.fields()) {
            long fieldId = id++;
            List<Option> options = new ArrayList<>();
            long optionId = fieldId * 100;
            for (Option o : f.options()) options.add(new Option(optionId++, fieldId, o.position(), o.label(), o.pingRoleId(), o.escalateRoleId()));
            withIds.add(new Field(fieldId, 4, f.position(), f.label(), f.kind(), f.required(), f.placeholder(), f.maxLength(), options));
        }

        TicketService service = new TicketService(mock(TicketRepository.class), mock(PlayerLinkService.class), mock(AdminRoleFilter.class));
        var modal = service.buildForm(panel("", 2, true), withIds);
        assertEquals("ticket_form:4", modal.getId());
        assertEquals(5, modal.getComponents().size());
    }

    // ---------- the Ticket Tool style opening ----------

    private static String embedText(net.dv8tion.jda.api.entities.MessageEmbed e) {
        StringBuilder sb = new StringBuilder(e.getDescription() == null ? "" : e.getDescription());
        for (var f : e.getFields()) sb.append("\n").append(f.getName()).append(": ").append(f.getValue());
        return sb.toString();
    }

    @Test
    void aTicketOpensAsAWelcomeLineTwoEmbedsAndButtons() {
        var o = TicketView.opening(panel("", 2, true), ticket(Status.OPEN, List.of(new Answer("Which achievement?", "Elite Vorago"))), List.of("Metorrite"), List.of(), 77L);
        assertTrue(o.content().startsWith("<@100> Welcome"), o.content());
        assertTrue(o.content().contains("<@&77>"), "the tier's role is pinged in the message itself");
        assertEquals(2, o.embeds().size());

        String message = embedText(o.embeds().get(0));
        assertTrue(message.contains("A helper will join you."));
        assertTrue(o.embeds().get(0).getFields().isEmpty(), "the message embed is just text, no field clutter");
        assertTrue(message.contains("Nobody has joined yet"));
        assertEquals("Ticket #0003 · Combat Achievement Help", o.embeds().get(0).getFooter().getText());

        String info = embedText(o.embeds().get(1));
        assertTrue(info.startsWith("**RuneScape name**\n```\nMetorrite\n```"), "the linked name leads the info block: " + info);
        assertTrue(info.contains("**Which achievement?**\n```\nElite Vorago\n```"), info);

        assertEquals(List.of("ticket_join:9", "ticket_close:9"), o.buttons().getButtons().stream().map(Button::getCustomId).toList());
        assertEquals("Close", o.buttons().getButtons().getLast().getLabel());
        assertEquals("🔒", o.buttons().getButtons().getLast().getEmoji().getName(), "Close comes last, with a lock");
        o.toCreate();
        o.toEdit();
    }

    @Test
    void thePanelsOwnWordingCanUsePlaceholdersAndPlacesThePingItself() {
        Panel custom = new Panel(4, 1, "CA Help", "CA Help", "", "Open", null, "ca-{number}", "Ticket {number} for {type}: {rsn}. Helpers: {helpers}", true, 1, null, 2, 24, null, null, null,
                "{user} thanks for asking! {ping}");
        var o = TicketView.opening(custom, ticket(Status.OPEN, List.of()), List.of("Metorrite"), List.of(201L), 77L);
        assertEquals("<@100> thanks for asking! <@&77>", o.content(), "the ping sits where the wording put it, not added again underneath");
        String message = embedText(o.embeds().get(0));
        assertTrue(message.startsWith("Ticket 0003 for Elite: Metorrite. Helpers: <@201>"), message);
    }

    @Test
    void theOpeningMessageIsEditableAndBlankFallsBackToTheDefaults() {
        Panel custom = new Panel(4, 1, "CA Help", "Help", "", "Open", null, "ca-{number}", "", true, 1, null, 2, 24, null, null, null, "Hey {user}, thanks for asking!");
        var o = TicketView.opening(custom, ticket(Status.OPEN, List.of()), List.of(), List.of(), null);
        assertEquals("Hey <@100>, thanks for asking!", o.content());
        assertTrue(embedText(o.embeds().get(0)).contains("Support will be with you shortly."), "a blank support message uses the default");

        Panel blank = new Panel(4, 1, "CA Help", "Help", "", "Open", null, "ca-{number}", "", true, 1, null, 2, 24, null, null, null, " ");
        assertEquals("<@100> Welcome", TicketView.opening(blank, ticket(Status.OPEN, List.of()), List.of(), List.of(), null).content());
    }

    @Test
    void noAnswersMeansNoSecondEmbedAndAnAnswerCannotBreakOutOfItsCodeBlock() {
        assertEquals(1, TicketView.opening(panel("", 2, true), ticket(Status.OPEN, List.of(new Answer("Notes", " "))), List.of(), List.of(), null).embeds().size());

        var o = TicketView.opening(panel("", 2, true), ticket(Status.OPEN, List.of(new Answer("Notes", "evil ``` fence"))), List.of(), List.of(), null);
        String qa = embedText(o.embeds().get(1));
        assertEquals(2, qa.split("```", -1).length - 1, "only our own opening and closing fence remain: " + qa);
    }

    @Test
    void aTicketWithOnlyALinkedNameStillGetsAnInfoBlock() {
        var o = TicketView.opening(panel("", 2, true), ticket(Status.OPEN, List.of()), List.of("Metorrite"), List.of(), null);
        assertEquals(2, o.embeds().size());
        assertTrue(embedText(o.embeds().get(1)).contains("Metorrite"));
    }

    @Test
    void aClosedTicketHasDisabledButtonsAndNoPing() {
        var o = TicketView.opening(panel("", 2, true), ticket(Status.CLOSED, List.of()), List.of(), List.of(), 77L);
        assertFalse(o.content().contains("<@&77>"));
        assertTrue(o.buttons().getButtons().stream().allMatch(Button::isDisabled));
        String message = embedText(o.embeds().get(0));
        assertTrue(message.contains("Closed by <@101>") && message.contains("Done"), message);
    }
}
