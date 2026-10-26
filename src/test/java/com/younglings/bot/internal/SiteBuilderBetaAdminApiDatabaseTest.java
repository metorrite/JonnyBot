package com.younglings.bot.internal;

import com.younglings.bot.access.AccessArea;
import com.younglings.bot.access.AccessGrantRepository;
import com.younglings.bot.access.AccessGrantService;
import com.younglings.bot.access.GateSource;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The owner's site builder beta list over real SQL: add, list, remove by Discord user id; the bot's owner only; and it opens the gate. */
class SiteBuilderBetaAdminApiDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long OWNER = BASE + 801;
    private static final long TESTER = BASE + 802;
    private static final long STRANGER = BASE + 803;

    private static ConnectionSupplier db;
    private AccessGrantRepository repository;
    private AccessGrantService service;
    private SiteBuilderBetaAdminApi api;
    private Guild home;
    private JDA jda;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        repository = new AccessGrantRepository(db);
        service = new AccessGrantService(GateSource.ownTables(null, repository::all, null));
        BotOwners owners = mock(BotOwners.class);
        when(owners.isOwner(any(), eq(OWNER))).thenReturn(true);
        api = new SiteBuilderBetaAdminApi(repository, service, owners);
        jda = mock(JDA.class);
        home = mock(Guild.class);
        when(home.getJDA()).thenReturn(jda);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.access_grant WHERE user_id IN (?, ?, ?)")) {
            s.setLong(1, OWNER);
            s.setLong(2, TESTER);
            s.setLong(3, STRANGER);
            s.executeUpdate();
        }
    }

    private static Member member(long id) {
        Member m = mock(Member.class);
        when(m.getIdLong()).thenReturn(id);
        return m;
    }

    private DataObject call(long who, String method, String path, DataObject body) {
        return api.route(home, member(who), method, path.split("/"), () -> body);
    }

    @Test
    void theOwnerAddsListsAndRemovesBetaUsers() {
        assertFalse(service.allows(jda, AccessArea.SITE_BUILDER_BETA, TESTER));

        DataObject added = call(OWNER, "POST", "site-builder-beta", DataObject.empty().put("userId", Long.toString(TESTER)).put("note", "clan leader"));
        assertEquals(1, added.getArray("users").length());
        assertEquals(Long.toString(TESTER), added.getArray("users").getObject(0).getString("userId"));
        assertEquals("site-builder-beta", added.getString("area"));
        assertTrue(service.allows(jda, AccessArea.SITE_BUILDER_BETA, TESTER), "the gate opens at once");
        assertFalse(service.allows(jda, AccessArea.HUB, TESTER), "only the site builder beta");

        call(OWNER, "POST", "site-builder-beta", DataObject.empty().put("userId", Long.toString(TESTER)));
        assertEquals(1, call(OWNER, "GET", "site-builder-beta", DataObject.empty()).getArray("users").length(), "adding twice keeps one entry");

        DataObject removed = call(OWNER, "DELETE", "site-builder-beta/" + TESTER, DataObject.empty());
        assertEquals(0, removed.getArray("users").length());
        assertFalse(service.allows(jda, AccessArea.SITE_BUILDER_BETA, TESTER));
        assertEquals(404, assertThrows(ApiError.class, () -> call(OWNER, "DELETE", "site-builder-beta/" + TESTER, DataObject.empty())).status);
    }

    @Test
    void onlyTheBotsOwnerMayUseIt() {
        assertEquals(403, assertThrows(ApiError.class, () -> call(STRANGER, "GET", "site-builder-beta", DataObject.empty())).status);
        assertEquals(403, assertThrows(ApiError.class,
                () -> call(STRANGER, "POST", "site-builder-beta", DataObject.empty().put("userId", Long.toString(STRANGER)))).status);
    }

    @Test
    void aUserIdMustBeOne() {
        assertEquals(400, assertThrows(ApiError.class, () -> call(OWNER, "POST", "site-builder-beta", DataObject.empty().put("userId", "12"))).status);
    }

    /** The website's names for the same list (runeclans {@code betaTestersLogic.ts}): {@code beta-testers}, PUT with a note, answers the list. */
    @Test
    void theWebsitesBetaTestersRoutesUseTheSameEntries() {
        DataObject added = call(OWNER, "PUT", "beta-testers/" + TESTER, DataObject.empty().put("note", "Lantern Hollow's leader"));
        assertEquals(1, added.getArray("testers").length());
        DataObject t = added.getArray("testers").getObject(0);
        assertEquals(Long.toString(TESTER), t.getString("userId"));
        assertEquals("Lantern Hollow's leader", t.getString("note"));
        assertEquals(Long.toString(OWNER), t.getString("addedBy"));
        assertTrue(java.time.Instant.parse(t.getString("addedAt")).isBefore(java.time.Instant.now().plusSeconds(5)));
        assertTrue(service.allows(jda, AccessArea.SITE_BUILDER_BETA, TESTER), "a site-builder-beta grant");
        assertEquals(1, call(OWNER, "GET", "site-builder-beta", DataObject.empty()).getArray("users").length(), "and shows on the old route");

        DataObject renamed = call(OWNER, "PUT", "beta-testers/" + TESTER, DataObject.empty().put("note", "co-leader"));
        assertEquals(1, renamed.getArray("testers").length(), "a second PUT changes the note, not the count");
        assertEquals("co-leader", renamed.getArray("testers").getObject(0).getString("note"));
        assertEquals("co-leader", call(OWNER, "GET", "beta-testers", DataObject.empty()).getArray("testers").getObject(0).getString("note"));

        assertEquals(0, call(OWNER, "DELETE", "beta-testers/" + TESTER, DataObject.empty()).getArray("testers").length());
        assertFalse(service.allows(jda, AccessArea.SITE_BUILDER_BETA, TESTER));

        assertEquals(403, assertThrows(ApiError.class, () -> call(STRANGER, "GET", "beta-testers", DataObject.empty())).status);
        assertEquals(403, assertThrows(ApiError.class, () -> call(STRANGER, "PUT", "beta-testers/" + STRANGER, DataObject.empty())).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call(OWNER, "PUT", "beta-testers/12", DataObject.empty())).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call(OWNER, "PUT", "beta-testers/" + TESTER, DataObject.empty().put("note", "n".repeat(121)))).status);
    }
}
