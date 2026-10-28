package com.runeclans.bot.internal;

import com.runeclans.bot.plugin.PluginEventType;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginPointsAdminApiTest {
    private static int refusal(String json) {
        return assertThrows(TicketAdminApi.ApiError.class, () -> PluginPointsAdminApi.parse(DataObject.fromJson(json))).status;
    }

    @Test
    void readsOneRulePerTypeAndKeepsTheSmallestValueOnlyForDrops() {
        var rules = PluginPointsAdminApi.parse(DataObject.fromJson("""
                {"rules":[{"type":"DROP","points":5,"dailyCap":50,"minValueGp":2000000},{"type":"KILL","points":1,"dailyCap":0,"minValueGp":9}]}"""));

        assertEquals(2, rules.size());
        assertEquals(PluginEventType.DROP, rules.get(0).type());
        assertEquals(2_000_000L, rules.get(0).minValueGp());
        assertNull(rules.get(1).minValueGp());
    }

    @Test
    void malformedRulesAreRefusedWith400NotAServerError() {
        assertEquals(400, refusal("{\"rules\":\"all\"}"));
        assertEquals(400, refusal("{\"rules\":[\"KILL\"]}"));
        assertEquals(400, refusal("{\"rules\":[{\"type\":\"KILL\",\"points\":\"lots\"}]}"));
        assertEquals(400, refusal("{\"rules\":[{\"type\":\"KILL\",\"points\":99999999999}]}"));
        assertEquals(400, refusal("{\"rules\":[{\"type\":\"KILL\",\"points\":1},{\"type\":\"KILL\",\"points\":2}]}"));
        assertEquals(400, refusal("{\"rules\":[{\"type\":\"XP\",\"points\":1}]}"));
    }
}
