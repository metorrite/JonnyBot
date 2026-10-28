package com.runeclans.bot.internal;

import com.runeclans.bot.events.BingoTiles;
import com.runeclans.bot.events.PluginObjectiveMatcher;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginTileHintsAdminApiTest {
    private static final PluginObjectiveMatcher.Labels LABELS = label -> {
        int colon = label.indexOf(": ");
        return colon > 0 ? new BingoTiles.Ref(label.substring(0, colon), label.substring(colon + 2).strip(), null) : new BingoTiles.Ref(null, label.strip(), null);
    };

    @Test
    void answersOneHintPerLabelInOrder() {
        DataObject answer = PluginTileHintsAdminApi.hints(DataObject.fromJson("{\"template\":\"bingo\",\"labels\":[\"Nex: Torva boots\",\"99 Fletching\",\"\"]}"),
                LABELS, item -> item.equalsIgnoreCase("Torva boots"));

        assertEquals(3, answer.getArray("hints").length());
        assertTrue(answer.getArray("hints").getObject(0).getBoolean("auto"));
        assertFalse(answer.getArray("hints").getObject(1).getBoolean("auto"));
        assertTrue(answer.getArray("hints").getObject(2).isNull("text"), "an empty label gets no hint");
    }

    @Test
    void refusesWhatIsNotAListOfText() {
        assertThrows(TicketAdminApi.ApiError.class, () -> PluginTileHintsAdminApi.hints(DataObject.fromJson("{\"labels\":\"Nex\"}"), LABELS, item -> false));
        assertThrows(TicketAdminApi.ApiError.class, () -> PluginTileHintsAdminApi.hints(DataObject.fromJson("{\"labels\":[1]}"), LABELS, item -> false));
        StringBuilder many = new StringBuilder("{\"labels\":[");
        for (int i = 0; i <= PluginTileHintsAdminApi.MAX_LABELS; i++) many.append(i == 0 ? "" : ",").append("\"x\"");
        assertThrows(TicketAdminApi.ApiError.class, () -> PluginTileHintsAdminApi.hints(DataObject.fromJson(many.append("]}").toString()), LABELS, item -> false));
    }
}
