package com.younglings.bot.internal;

import com.younglings.bot.commands.ticket.DevTicketPostCommand;
import com.younglings.bot.commands.ticket.TicketRules;
import com.younglings.bot.internal.TicketAdminJson.BadRequest;
import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.Settings;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TicketAdminJsonTest {
    private static final long BIG_ID = 1_495_619_657_674_264_717L; // beyond a JavaScript number's exact range

    @Test
    void aPanelSurvivesTheTripToJsonAndBack() {
        PanelDefinition sample = DevTicketPostCommand.sample(1);
        PanelDefinition back = TicketAdminJson.readPanel(1, 0, DataObject.fromJson(TicketAdminJson.definitionJson(sample).toJson()));

        assertEquals(sample.panel().name(), back.panel().name());
        assertEquals(sample.panel().helperCap(), back.panel().helperCap());
        assertEquals(sample.fields().size(), back.fields().size());
        for (int i = 0; i < sample.fields().size(); i++) {
            Field a = sample.fields().get(i);
            Field b = back.fields().get(i);
            assertEquals(a.label(), b.label());
            assertEquals(a.kind(), b.kind());
            assertEquals(a.required(), b.required());
            assertEquals(a.options().size(), b.options().size());
        }
        assertTrue(TicketRules.validatePanel(back.panel(), back.fields()).isEmpty());
    }

    @Test
    void closeSettingsAndCloseRolesSurviveTheTrip() {
        DataObject json = TicketAdminJson.definitionJson(DevTicketPostCommand.sample(1))
                .put("closeByRequester", false)
                .put("closeByHelpers", true)
                .put("closeRoleIds", net.dv8tion.jda.api.utils.data.DataArray.fromCollection(List.of("42", "43")));
        PanelDefinition back = TicketAdminJson.readPanel(1, 0, DataObject.fromJson(json.toJson()));

        assertFalse(back.panel().closeByRequester());
        assertTrue(back.panel().closeByHelpers());
        assertEquals(java.util.Set.of(42L, 43L), back.roles().closeRoleIds());

        DataObject out = TicketAdminJson.definitionJson(back);
        assertFalse(out.getBoolean("closeByRequester"));
        assertEquals(2, out.getArray("closeRoleIds").length());
    }

    @Test
    void aPanelWithoutCloseSettingsKeepsTheOldBehaviour() {
        PanelDefinition read = TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"name\":\"a\",\"title\":\"b\"}"));
        assertTrue(read.panel().closeByRequester());
        assertTrue(read.panel().closeByHelpers());
        assertTrue(read.roles().closeRoleIds().isEmpty());
    }

    @Test
    void theDefaultsForNewPanelsKeepTheSharedSettingsAndLeaveOutTheContent() {
        DataObject body = DataObject.fromJson("""
                {"name":"x","title":"x","description":"Not a default","buttonLabel":"Ask for help","categoryId":"900","staffRoleIds":["1","2"],
                 "closeRoleIds":["3"],"closeByRequester":false,"perUserLimit":2,"helperCap":2,"escalationHours":72,
                 "openingMessage":"{user} Welcome","fields":[{"label":"Q","kind":"SHORT"}]}
                """);
        DataObject defaults = TicketAdminJson.defaultsJson(TicketAdminJson.readPanel(1, 0, body));

        assertEquals("Ask for help", defaults.getString("buttonLabel"));
        assertEquals("900", defaults.getString("categoryId"));
        assertEquals(2, defaults.getArray("staffRoleIds").length());
        assertEquals(1, defaults.getArray("closeRoleIds").length());
        assertFalse(defaults.getBoolean("closeByRequester"));
        assertEquals(72, defaults.getInt("escalationHours"));
        assertFalse(defaults.hasKey("description"), "descriptions, names, titles and questions are per panel");
        assertFalse(defaults.hasKey("fields"));
        assertFalse(defaults.hasKey("name"));
    }

    @Test
    void theBuiltInDefaultsAreAValidStartingPanel() {
        PanelDefinition read = TicketAdminJson.readPanel(1, 0, TicketAdminJson.builtinDefaults().put("name", "n").put("title", "t"));
        assertTrue(TicketRules.validatePanel(read.panel(), read.fields()).isEmpty());
    }

    @Test
    void idsTravelAsStringsSoTheWebsiteCannotRoundThem() {
        Settings settings = new Settings(1, BIG_ID, 5, true, 10, null);
        DataObject json = DataObject.fromJson(TicketAdminJson.settingsJson(settings).toJson());
        assertEquals(Long.toString(BIG_ID), json.getString("logChannelId"));
        assertEquals(BIG_ID, TicketAdminJson.readSettings(settings, json).logChannelId());
    }

    @Test
    void missingOrNullValuesReadAsNotSet() {
        DataObject json = DataObject.fromJson("{\"name\":\"CA\",\"title\":\"T\",\"buttonLabel\":\"Go\",\"channelNameTemplate\":\"ca-{number}\"," +
                "\"categoryId\":null,\"helperCap\":null}");
        var panel = TicketAdminJson.readPanel(1, 0, json).panel();
        assertNull(panel.categoryId());
        assertNull(panel.helperCap());
        assertNull(panel.defaultPingRoleId());
        assertTrue(panel.enabled(), "a new panel is enabled unless it says otherwise");
        assertEquals(1, panel.perUserLimit());
    }

    @Test
    void textIsTrimmedAndBlankPlaceholdersDropped() {
        DataObject json = DataObject.fromJson("{\"name\":\"  CA  \",\"fields\":[{\"label\":\" Q \",\"kind\":\"short\",\"placeholder\":\"   \"}]}");
        PanelDefinition definition = TicketAdminJson.readPanel(1, 0, json);
        assertEquals("CA", definition.panel().name());
        assertEquals("Q", definition.fields().getFirst().label());
        assertEquals(FieldKind.SHORT, definition.fields().getFirst().kind());
        assertNull(definition.fields().getFirst().placeholder());
    }

    @Test
    void choicesAreOnlyKeptOnDropdownQuestions() {
        DataObject json = DataObject.fromJson("{\"fields\":[" +
                "{\"label\":\"A\",\"kind\":\"SHORT\",\"options\":[{\"label\":\"x\"}]}," +
                "{\"label\":\"B\",\"kind\":\"SELECT\",\"options\":[{\"label\":\"x\",\"pingRoleId\":\"" + BIG_ID + "\"}]}]}");
        PanelDefinition definition = TicketAdminJson.readPanel(1, 0, json);
        assertTrue(definition.fields().get(0).options().isEmpty());
        assertEquals(BIG_ID, definition.fields().get(1).options().getFirst().pingRoleId());
    }

    @Test
    void garbageIsRejectedAsABadRequest() {
        assertThrows(BadRequest.class, () -> TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"categoryId\":\"abc\"}")));
        assertThrows(BadRequest.class, () -> TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"categoryId\":\"-5\"}")));
        assertThrows(BadRequest.class, () -> TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"helperCap\":\"lots\"}")));
        assertThrows(BadRequest.class, () -> TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"fields\":[{\"label\":\"A\",\"kind\":\"BANANA\"}]}")));
        assertThrows(BadRequest.class, () -> TicketAdminJson.readPanel(1, 0, DataObject.fromJson("{\"helperRoleIds\":[\"x\"]}")));
    }

    @Test
    void theClientCannotSetWhereAPanelIsPosted() {
        DataObject json = DataObject.fromJson("{\"name\":\"CA\",\"postedChannelId\":\"5\",\"postedMessageId\":\"6\"}");
        var panel = TicketAdminJson.readPanel(1, 7, json).panel();
        assertEquals(7, panel.id());
        assertNull(panel.postedChannelId());
        assertNull(panel.postedMessageId());
    }

    @Test
    void settingsKeepTheCounterTheClientCannotChange() {
        Settings current = new Settings(1, null, 42, true, 10, null);
        Settings updated = TicketAdminJson.readSettings(current, DataObject.fromJson("{\"nextNumber\":1,\"transcriptDm\":false,\"closeDelaySeconds\":30,\"transcriptRetentionDays\":90}"));
        assertEquals(42, updated.nextNumber());
        assertFalse(updated.transcriptDm());
        assertEquals(30, updated.closeDelaySeconds());
        assertEquals(90, updated.transcriptRetentionDays());
    }

    @Test
    void aTicketShowsItsAnswersAndTimes() {
        OffsetDateTime now = OffsetDateTime.parse("2026-10-05T17:00:00Z");
        Ticket ticket = new Ticket(9, 1, 4L, 3, BIG_ID, 100L, Status.CLOSED, null, "Elite", List.of(new Answer("Which?", "Amascut")),
                66L, now, null, now, 101L, "Done", false);
        DataObject json = DataObject.fromJson(TicketAdminJson.ticketJson(ticket, "CA Help", "Metorrite").toJson());
        assertEquals("CLOSED", json.getString("status"));
        assertEquals(Long.toString(BIG_ID), json.getString("channelId"));
        assertEquals("Amascut", json.getArray("answers").getObject(0).getString("answer"));
        assertEquals("2026-10-05T17:00Z", json.getString("createdAt"));
        assertNull(json.getString("escalatedAt", null));
    }
}
