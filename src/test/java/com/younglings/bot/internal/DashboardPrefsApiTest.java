package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The starred pages of the dashboard sidebar: kept per admin and server, in order, replaced as a whole, and only for things that look like pages. Over a real database (skipped without one). */
class DashboardPrefsApiTest {
    private static final long G = 999_000_000_000_000_101L;
    private static ConnectionSupplier supplier;
    private DashboardPrefsApi api;
    private Guild guild;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        api = new DashboardPrefsApi(supplier);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"dashboard_favorite", "dashboard_view"}) {
            try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, G);
                s.executeUpdate();
            }
        }
    }

    private Member admin(long id) {
        Member member = Mockito.mock(Member.class);
        Mockito.when(member.getIdLong()).thenReturn(id);
        return member;
    }

    private DataObject put(Member who, String json) {
        return api.route(guild, who, "PUT", new String[]{"prefs", "favorites"}, () -> DataObject.fromJson(json));
    }

    private List<String> get(Member who) {
        return api.route(guild, who, "GET", new String[]{"prefs", "favorites"}, DataObject::empty).getArray("paths").toList().stream().map(Object::toString).toList();
    }

    @Test
    void theStarredPagesComeBackInTheOrderTheyWereStarred() {
        Member a = admin(1);
        assertEquals(List.of(), get(a));
        put(a, "{\"paths\":[\"/event-manager\",\"/settings\",\"/citadel-wheel\"]}");
        assertEquals(List.of("/event-manager", "/settings", "/citadel-wheel"), get(a));
    }

    @Test
    void savingReplacesTheListAndEachAdminHasTheirOwn() {
        Member a = admin(1);
        Member b = admin(2);
        put(a, "{\"paths\":[\"/settings\",\"/welcome\"]}");
        put(b, "{\"paths\":[\"/tickets\"]}");
        put(a, "{\"paths\":[\"/welcome\"]}");
        assertEquals(List.of("/welcome"), get(a));
        assertEquals(List.of("/tickets"), get(b));
        put(a, "{\"paths\":[]}");
        assertEquals(List.of(), get(a), "unstarring the last one empties it");
    }

    @Test
    void aPageListedTwiceIsKeptOnceAndAnythingThatIsNotAPageIsRefused() {
        Member a = admin(1);
        put(a, "{\"paths\":[\"/settings\",\"/settings\",\"/welcome\"]}");
        assertEquals(List.of("/settings", "/welcome"), get(a));
        for (String bad : new String[]{"settings", "/Settings", "/a/b", "//evil.example", "/settings?x=1", ""}) {
            assertEquals(400, assertThrows(ApiError.class, () -> put(a, "{\"paths\":[\"" + bad + "\"]}")).status, bad);
        }
        assertEquals(List.of("/settings", "/welcome"), get(a), "a refused save changes nothing");
        StringBuilder many = new StringBuilder("{\"paths\":[");
        for (int i = 0; i < DashboardPrefsApi.MAX_FAVORITES + 1; i++) many.append(i == 0 ? "" : ",").append("\"/page-").append(i).append("\"");
        assertEquals(400, assertThrows(ApiError.class, () -> put(a, many.append("]}").toString())).status);
    }

    private DataObject view(Member who, String method, String json) {
        return api.route(guild, who, method, new String[]{"prefs", "view"}, () -> DataObject.fromJson(json));
    }

    @Test
    void theViewIsNullUntilChosenThenKeptPerAdmin() {
        Member a = admin(1);
        Member b = admin(2);
        assertNull(view(a, "GET", "{}").getString("mode", null), "never chosen: the browser decides");
        view(a, "PUT", "{\"mode\":\"advanced\"}");
        view(b, "PUT", "{\"mode\":\"basic\"}");
        assertEquals("advanced", view(a, "GET", "{}").getString("mode"));
        assertEquals("basic", view(b, "GET", "{}").getString("mode"));
        view(a, "PUT", "{\"mode\":\"basic\"}");
        assertEquals("basic", view(a, "GET", "{}").getString("mode"));
    }

    @Test
    void anythingButBasicOrAdvancedIsRefusedAndChangesNothing() {
        Member a = admin(1);
        view(a, "PUT", "{\"mode\":\"advanced\"}");
        for (String bad : new String[]{"{\"mode\":\"expert\"}", "{\"mode\":\"\"}", "{}"}) {
            assertEquals(400, assertThrows(ApiError.class, () -> view(a, "PUT", bad)).status, bad);
        }
        assertEquals("advanced", view(a, "GET", "{}").getString("mode"));
        assertEquals(405, assertThrows(ApiError.class, () -> view(a, "DELETE", "{}")).status);
    }

    @Test
    void otherRoutesAreNotThisOnes() {
        assertNull(api.route(guild, admin(1), "GET", new String[]{"prefs", "other"}, DataObject::empty));
        assertNull(api.route(guild, admin(1), "GET", new String[]{"events"}, DataObject::empty));
        assertEquals(405, assertThrows(ApiError.class, () -> api.route(guild, admin(1), "DELETE", new String[]{"prefs", "favorites"}, DataObject::empty)).status);
    }
}
