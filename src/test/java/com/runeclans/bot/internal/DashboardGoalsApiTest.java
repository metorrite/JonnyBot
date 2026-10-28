package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.permission.DashboardAccess;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Set up by goal" (T-607): one list of goals per server, only the four known ones, changed by an Admin only. Over a real database (skipped without one). */
class DashboardGoalsApiTest {
    private static final long G = 999_000_000_000_000_607L;
    private static ConnectionSupplier supplier;
    private DashboardGoalsApi api;
    private DashboardAccess access;
    private Guild guild;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        access = Mockito.mock(DashboardAccess.class);
        api = new DashboardGoalsApi(supplier, access);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.dashboard_goals WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private Member person(long id, DashboardAccess.Tier tier) {
        Member member = Mockito.mock(Member.class);
        Mockito.when(member.getIdLong()).thenReturn(id);
        Mockito.when(access.tierOf(guild, member)).thenReturn(tier);
        return member;
    }

    private DataObject put(Member who, String json) {
        return api.route(guild, who, "PUT", new String[]{"prefs", "goals"}, () -> DataObject.fromJson(json));
    }

    private DataObject get(Member who) {
        return api.route(guild, who, "GET", new String[]{"prefs", "goals"}, DataObject::empty);
    }

    private static List<String> goals(DataObject answer) {
        return answer.getArray("goals").toList().stream().map(Object::toString).toList();
    }

    @Test
    void nothingIsChosenUntilAnAdminPicks() {
        DataObject answer = get(person(1, DashboardAccess.Tier.ADMIN));
        assertEquals(List.of(), goals(answer));
        assertFalse(answer.getBoolean("chosen"));
        assertTrue(answer.getBoolean("canChange"));
    }

    @Test
    void theGoalsAreKeptPerServerInTheDashboardsOrderEachOnce() {
        Member admin = person(1, DashboardAccess.Tier.ADMIN);
        put(admin, "{\"goals\":[\"events\",\"PvM\",\"events\"]}");
        DataObject answer = get(person(2, DashboardAccess.Tier.DEVELOPER));
        // one list for the server: another manager sees the same
        assertEquals(List.of("pvm", "events"), goals(answer));
        assertTrue(answer.getBoolean("chosen"));
        assertFalse(answer.getBoolean("canChange"));
    }

    @Test
    void anEmptyListClearsTheChoice() {
        Member admin = person(1, DashboardAccess.Tier.ADMIN);
        put(admin, "{\"goals\":[\"social\"]}");
        DataObject answer = put(admin, "{\"goals\":[]}");
        assertFalse(answer.getBoolean("chosen"));
        assertEquals(List.of(), goals(get(admin)));
    }

    @Test
    void onlyAnAdminMayChangeThem() {
        Member developer = person(2, DashboardAccess.Tier.DEVELOPER);
        ApiError error = assertThrows(ApiError.class, () -> put(developer, "{\"goals\":[\"pvm\"]}"));
        assertEquals(403, error.status);
        assertEquals(List.of(), goals(get(developer)));
    }

    @Test
    void anUnknownGoalOrAMissingListIsRefused() {
        Member admin = person(1, DashboardAccess.Tier.ADMIN);
        assertEquals(400, assertThrows(ApiError.class, () -> put(admin, "{\"goals\":[\"raids\"]}")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> put(admin, "{}")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> put(admin, "{\"goals\":null}")).status);
    }

    @Test
    void otherPathsAreNotItsOwn() {
        Member admin = person(1, DashboardAccess.Tier.ADMIN);
        assertNull(api.route(guild, admin, "GET", new String[]{"prefs", "view"}, DataObject::empty));
        assertNull(api.route(guild, admin, "GET", new String[]{"prefs"}, DataObject::empty));
        assertNull(api.route(guild, admin, "GET", new String[]{"goals"}, DataObject::empty));
    }

    @Test
    void cleanKeepsTheKnownOrder() {
        assertEquals(List.of("pvm", "skilling", "social", "events"),
                DashboardGoalsApi.clean(DataArray.fromJson("[\"events\",\"social\",\"skilling\",\"pvm\"]")));
    }
}
