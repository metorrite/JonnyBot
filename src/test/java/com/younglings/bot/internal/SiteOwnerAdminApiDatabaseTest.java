package com.younglings.bot.internal;

import com.younglings.bot.access.GateSource;
import com.younglings.bot.beta.BetaDatabaseInitializer;
import com.younglings.bot.beta.BetaGuildRepository;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteLayoutRepository;
import com.younglings.bot.clansite.ClanSiteRepository;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bot owner's site console (T-423) over real SQL: owner only, the list of every site, the address with the clan profile's own checks,
 * switching off and on through the takedown, resetting the draft, deleting with a typed confirmation, and a test site. Made-up server ids.
 */
class SiteOwnerAdminApiDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long HOME = BASE + 501;
    private static final long CLAN = BASE + 502;
    private static final long OTHER = BASE + 503;
    private static final long TEST = BASE + 504;
    private static final long OWNER = BASE + 511;
    private static final long STRANGER = BASE + 512;
    private static final long[] GUILDS = {CLAN, OTHER, TEST};

    private static ConnectionSupplier db;
    private ClanProfileRepository profiles;
    private ClanSiteRepository sites;
    private ClanSiteLayoutRepository layouts;
    private BetaGuildRepository beta;
    private SiteRoleGuard roles;
    private SiteOwnerAdminApi api;
    private Guild home;
    private Member owner;
    private Member stranger;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        profiles = new ClanProfileRepository(db);
        sites = new ClanSiteRepository(db);
        layouts = new ClanSiteLayoutRepository(db, sites);
        beta = new BetaGuildRepository(db, new BetaDatabaseInitializer(db));
        BotConfig config = mock(BotConfig.class);
        when(config.getGuildId()).thenReturn(HOME);
        ClanSiteService service = new ClanSiteService(sites, GateSource.ownTables(beta::all, null, null), config, mock(com.younglings.bot.clansite.BotlessSiteRepository.class));
        roles = mock(SiteRoleGuard.class);
        when(roles.settings(anyLong())).thenReturn(new SiteRoles.Settings(List.of(), List.of(), List.of(), false, null));
        BotOwners owners = mock(BotOwners.class);
        when(owners.isOwner(any(), eq(OWNER))).thenReturn(true);
        api = new SiteOwnerAdminApi(profiles, sites, layouts, service, roles, beta, owners);

        JDA jda = mock(JDA.class);
        home = guild(jda, HOME, "Home");
        Guild clan = guild(jda, CLAN, "Clan server");
        Guild test = guild(jda, TEST, "Test server");
        when(jda.getGuildById(HOME)).thenReturn(home);
        when(jda.getGuildById(CLAN)).thenReturn(clan);
        when(jda.getGuildById(TEST)).thenReturn(test);
        when(jda.getGuilds()).thenReturn(List.of(home, clan, test));
        owner = member(OWNER);
        stranger = member(STRANGER);
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) clean();
    }

    private static Guild guild(JDA jda, long id, String name) {
        Guild g = mock(Guild.class);
        when(g.getIdLong()).thenReturn(id);
        when(g.getId()).thenReturn(String.valueOf(id));
        when(g.getName()).thenReturn(name);
        when(g.getOwnerId()).thenReturn(String.valueOf(OWNER));
        when(g.getJDA()).thenReturn(jda);
        return g;
    }

    private static Member member(long id) {
        Member m = mock(Member.class);
        when(m.getIdLong()).thenReturn(id);
        when(m.getId()).thenReturn(String.valueOf(id));
        return m;
    }

    private void clean() throws Exception {
        try (Connection c = db.getConnection()) {
            for (String table : new String[]{"clan_site_layout", "clan_site_block", "clan_site", "clan_profile", "clan_slug_hold", "beta_guild"}) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?, ?)")) {
                    for (int i = 0; i < GUILDS.length; i++) s.setLong(i + 1, GUILDS[i]);
                    s.executeUpdate();
                }
            }
        }
    }

    private DataObject call(Member actor, String method, String path, DataObject body) {
        Supplier<DataObject> b = () -> body == null ? DataObject.empty() : body;
        return api.route(home, actor, method, path.split("/"), b);
    }

    private int status(Runnable call) {
        return assertThrows(TicketAdminApi.ApiError.class, call::run).status;
    }

    private void profile(long id, String slug, String name) {
        profiles.saveContent(new ClanProfile(id, ClanProfile.Visibility.UNLISTED, slug, name, "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class),
                ClanProfile.Recruiting.CLOSED, "", null, false, null, null, null, null, null, null));
    }

    private void liveSite(long id) {
        Instant now = Instant.now();
        sites.save(new ClanSite(id, true, true, ClanSite.Template.CLASSIC, "{}", "[]", OWNER, now, now, null));
    }

    private static DataObject find(DataObject list, long id) {
        DataArray all = list.getArray("sites");
        for (int i = 0; i < all.length(); i++) if (all.getObject(i).getString("guildId").equals(String.valueOf(id))) return all.getObject(i);
        return null;
    }

    @Test
    void onlyTheBotOwnerGetsIn() {
        assertEquals(403, status(() -> call(stranger, "GET", "owner-sites", null)));
        assertEquals(403, status(() -> call(stranger, "POST", "owner-sites/" + CLAN + "/delete", DataObject.empty().put("confirm", "delete"))));
        assertNull(api.route(home, stranger, "GET", new String[]{"clan-site"}, DataObject::empty), "other routes are not claimed");
    }

    @Test
    void theListHasEveryProfileSiteAndLayoutAndTheBotsServers() {
        profile(CLAN, "owner-console-clan", "Console clan");
        liveSite(CLAN);
        layouts.saveDraft(OTHER, "{\"v\":2}", 0, OWNER);

        DataObject list = call(owner, "GET", "owner-sites", null);
        DataObject clan = find(list, CLAN);
        assertNotNull(clan);
        assertEquals("owner-console-clan", clan.getString("slug"));
        assertTrue(clan.getBoolean("botPresent"));
        assertTrue(clan.getObject("site").getBoolean("live"));
        DataObject other = find(list, OTHER);
        assertNotNull(other, "a layout alone is listed too");
        assertFalse(other.getBoolean("botPresent"));
        assertEquals(1, other.getObject("layout").getInt("draftRev"));
        assertTrue(list.getArray("servers").length() >= 3);
    }

    @Test
    void theAddressGetsTheClanProfilesChecks() {
        profile(CLAN, "owner-console-clan", "Console clan");
        profile(OTHER, "owner-console-other", "Other clan");

        assertEquals(400, status(() -> call(owner, "POST", "owner-sites/" + CLAN + "/address", DataObject.empty().put("slug", "admin"))), "reserved");
        assertEquals(400, status(() -> call(owner, "POST", "owner-sites/" + CLAN + "/address", DataObject.empty().put("slug", "owner-console-other"))), "taken");
        assertEquals(400, status(() -> call(owner, "POST", "owner-sites/" + CLAN + "/address", DataObject.empty().put("slug", "Not Valid"))));

        DataObject saved = call(owner, "POST", "owner-sites/" + CLAN + "/address", DataObject.empty().put("slug", "owner-console-new").put("displayName", "New name"));
        assertEquals("owner-console-new", saved.getString("slug"));
        assertEquals("New name", profiles.get(CLAN).orElseThrow().displayName());
        assertEquals(ClanProfile.Visibility.UNLISTED, profiles.get(CLAN).orElseThrow().visibility(), "nothing else changes");
    }

    @Test
    void switchingOffIsATakedownAndSwitchingOnPutsItBack() {
        profile(CLAN, "owner-console-clan", "Console clan");
        liveSite(CLAN);

        DataObject off = call(owner, "POST", "owner-sites/" + CLAN + "/disable", DataObject.empty());
        assertFalse(off.getObject("site").getBoolean("live"));
        assertEquals(SiteOwnerAdminApi.DEFAULT_REASON, sites.byGuild(CLAN).orElseThrow().takedownReason());

        DataObject on = call(owner, "POST", "owner-sites/" + CLAN + "/enable", DataObject.empty());
        assertTrue(on.getObject("site").getBoolean("live"), "a site that was published is live again");
        assertNull(sites.byGuild(CLAN).orElseThrow().takedownReason());
    }

    @Test
    void resettingTheDraftGoesBackToTheLiveCopyOrToNothing() {
        layouts.saveDraft(CLAN, "{\"v\":2,\"x\":1}", 0, OWNER);
        layouts.publishDraft(CLAN, OWNER);
        layouts.saveDraft(CLAN, "{\"v\":2,\"x\":2}", 1, OWNER);
        call(owner, "POST", "owner-sites/" + CLAN + "/reset-draft", null);
        assertTrue(layouts.byGuild(CLAN).orElseThrow().draft().contains("\"x\":1"), "the draft is the live copy again");

        layouts.saveDraft(OTHER, "{\"v\":2}", 0, OWNER);
        call(owner, "POST", "owner-sites/" + OTHER + "/reset-draft", null);
        assertTrue(layouts.byGuild(OTHER).isEmpty(), "never published: no draft, so the guided setup starts again");
        assertEquals(404, status(() -> call(owner, "POST", "owner-sites/" + OTHER + "/reset-draft", null)));
    }

    @Test
    void deletingNeedsTheTypedAddressAndKeepsTheProfile() {
        profile(CLAN, "owner-console-clan", "Console clan");
        liveSite(CLAN);
        layouts.saveDraft(CLAN, "{\"v\":2}", 0, OWNER);

        assertEquals(400, status(() -> call(owner, "POST", "owner-sites/" + CLAN + "/delete", DataObject.empty().put("confirm", "delete"))));
        call(owner, "POST", "owner-sites/" + CLAN + "/delete", DataObject.empty().put("confirm", "Owner-Console-Clan "));
        assertTrue(sites.byGuild(CLAN).isEmpty());
        assertTrue(layouts.byGuild(CLAN).isEmpty());
        assertTrue(profiles.get(CLAN).isPresent(), "the clan profile and its address stay");
        verify(roles).delete(CLAN);
    }

    @Test
    void aTestSiteGetsAnAddressTheSiteSwitchedOnAndBeta() {
        assertEquals(404, status(() -> call(owner, "POST", "owner-sites/test", DataObject.empty().put("guildId", String.valueOf(OTHER)))), "the bot isn't there");

        DataObject made = call(owner, "POST", "owner-sites/test", DataObject.empty().put("guildId", String.valueOf(TEST)));
        String slug = made.getString("slug");
        assertTrue(slug.startsWith("test-"), slug);
        assertEquals("Test server", made.getString("displayName"));
        assertTrue(made.getObject("site").getBoolean("enabled"));
        assertTrue(made.getBoolean("inBeta"));

        DataObject again = call(owner, "POST", "owner-sites/test", DataObject.empty().put("guildId", String.valueOf(TEST)));
        assertEquals(slug, again.getString("slug"), "running it twice keeps the address");
    }
}
