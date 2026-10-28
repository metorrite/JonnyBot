package com.younglings.bot.internal;

import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.QuickGroupRepository.Group;
import com.younglings.bot.events.QuickGroupRepository.Member;
import com.younglings.bot.events.QuickGroups;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the clan site is told about one group (T-589): names, roles and the viewer's own place, never a Discord id. */
class DirectoryGroupFinderRoutesTest {
    private static final long HOST = Long.parseLong("7".repeat(17)), ANN = HOST + 1, BOB = HOST + 2;

    private static Group group() {
        DataArray slots = DataArray.empty().add(DataObject.empty().put("role", "Base").put("places", 1)).add(DataObject.empty().put("role", "DPS").put("places", 3));
        QuickGroups.Plan plan = QuickGroups.check(DataObject.empty().put("activity", "Solak").put("startsAt", "2026-10-27T20:00:00Z").put("slots", slots),
                Instant.parse("2026-10-27T19:00:00Z"));
        return new Group(5, 6, "Solak", "Bring food", plan.startsAt(), null, HOST, QuickGroups.config(plan), Status.SIGNUPS);
    }

    @Test
    void aMemberSeesTheirOwnPlaceAndNoIds() {
        Guild guild = Mockito.mock(Guild.class); // nobody in the cache: every name is "A member"
        DataObject json = DirectoryGroupFinderRoutes.groupJson(group(), List.of(new Member(ANN, "DPS", Instant.EPOCH), new Member(BOB, "Base", Instant.EPOCH)), ANN, false, guild);
        assertEquals("5", json.getString("id"));
        assertEquals("DPS", json.getString("myRole"));
        assertFalse(json.getBoolean("canClose"), "not the host, not staff");
        assertEquals("A member", json.getObject("host").getString("name"));
        DataObject dps = json.getArray("slots").getObject(1);
        assertEquals("DPS", dps.getString("role"));
        assertEquals(3, dps.getInt("places"));
        assertTrue(dps.getArray("members").getObject(0).getBoolean("me"));
        String text = json.toString();
        for (long id : new long[]{HOST, ANN, BOB}) assertFalse(text.contains(Long.toString(id)), "no Discord id is sent");
    }

    @Test
    void theHostAndStaffMayCloseAndSomeoneWithoutAPlaceHasNoRole() {
        Guild guild = Mockito.mock(Guild.class);
        assertTrue(DirectoryGroupFinderRoutes.groupJson(group(), List.of(), HOST, false, guild).getBoolean("canClose"));
        DataObject staff = DirectoryGroupFinderRoutes.groupJson(group(), List.of(), BOB, true, guild);
        assertTrue(staff.getBoolean("canClose"));
        assertNull(staff.getString("myRole", null));
        assertTrue(DirectoryGroupFinderRoutes.groupJson(group(), List.of(), HOST, false, guild).getObject("host").getBoolean("me"));
    }
}
