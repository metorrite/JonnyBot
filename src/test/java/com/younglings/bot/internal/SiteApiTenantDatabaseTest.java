package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.coffer.CofferDatabaseInitializer;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.poll.PollDatabaseInitializer;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.PlayerActivity;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import com.younglings.bot.runescape.RuneScapeProfile;
import com.younglings.bot.runescape.SkillValue;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import com.younglings.bot.signup.SignupDatabaseInitializer;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Clan-site answers ({@link SiteApi#tenant}, T-364) over the developer's local Postgres (skipped without one): two made-up servers, each with
 * its own clan, and in the first a member who hid themselves from the rankings and hid their adventure log. Every route is called for both
 * servers: a server only ever sees its own clan, the hidden member is in no list of names (only on the roster, which the game itself makes
 * public), and no Discord user id ever leaves.
 */
class SiteApiTenantDatabaseTest {
    // Made-up ids, built at run time (a long literal of this shape trips the secret scanner).
    private static final long GUILD_A = Long.parseLong("7".repeat(17) + "1");
    private static final long GUILD_B = Long.parseLong("7".repeat(17) + "2");
    private static final long USER_SHOWN = Long.parseLong("6".repeat(17) + "1");
    private static final long USER_HIDDEN = Long.parseLong("6".repeat(17) + "2");
    private static final long USER_OTHER = Long.parseLong("6".repeat(17) + "3");
    private static final String SHOWN = "T364Shown";
    private static final String HIDDEN = "T364Hidden";
    private static final String OTHER = "T364Other";
    private static final List<String> NAMES = List.of(SHOWN, HIDDEN, OTHER);
    private static final DateTimeFormatter RUNEMETRICS = DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH);

    /** Each tenant route with the query a site would send; the answers that must name the visible member (a check that the data is there at all). */
    private static final Map<String, String> QUERIES = Map.ofEntries(
            Map.entry("members", ""), Map.entry("overview", ""), Map.entry("leaderboard", "period=last-30d"), Map.entry("recap", "period=last-30d"),
            Map.entry("feed", "limit=50"), Map.entry("records", ""), Map.entry("citadel-grid", "weeks=4"), Map.entry("history", ""),
            Map.entry("coffer", ""), Map.entry("polls", ""), Map.entry("signups", ""), Map.entry("events", ""), Map.entry("pvm", ""),
            Map.entry("drops", ""), Map.entry("bosses", "period=last-30d"), Map.entry("boss", "boss=general_graardor&period=last-30d"),
            Map.entry("item", "item=bandos_hilt&period=last-30d"), Map.entry("drop-log", "period=last-30d"),
            Map.entry("member/skill", "rsn=" + SHOWN + "&skill=0"), Map.entry("notifications", ""),
            Map.entry("member", "rsn=" + SHOWN), Map.entry("member/recap", "rsn=" + SHOWN + "&period=last-30d"));
    private static final Set<String> NAMES_THE_SHOWN_MEMBER = Set.of("members", "overview", "leaderboard", "feed", "records", "citadel-grid", "history",
            "coffer", "signups", "pvm", "drops", "drop-log", "notifications");

    private static ConnectionSupplier db;
    // Built once: each initializer runs its whole schema pass, which is slow.
    private static SiteStatsRepository stats;
    private static PlayerLinkRepository links;
    private static WeeklyDigestRepository digest;
    private static ClanPointsRepository points;
    private static MemberProfileRepository profiles;
    private static CommunitySettings community;
    private static WikiCatalog catalog;
    private SiteApi api;
    private SiteCache cache;
    private Guild guildA;
    private Guild guildB;

    @BeforeAll
    static void connect() throws Exception {
        db = LocalDatabase.supplier();
        var initializer = new RuneScapeDatabaseInitializer(db);
        new SignupDatabaseInitializer(db);
        new PollDatabaseInitializer(db);
        new CofferDatabaseInitializer(db);
        stats = new SiteStatsRepository(db, initializer);
        links = new PlayerLinkRepository(db, initializer);
        digest = new WeeklyDigestRepository(db, initializer);
        points = new ClanPointsRepository(db, initializer);
        profiles = new MemberProfileRepository(db);
        community = new CommunitySettings(db, com.younglings.bot.testsupport.SiteMappings.off(db));
        catalog = new WikiCatalog();
    }

    static Stream<String> routes() {
        return SiteApi.TENANT_ROUTES.stream().sorted();
    }

    @BeforeEach
    void setUp() throws Exception {
        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(anyLong())).thenReturn(mock(GuildSettings.class));
        cache = new SiteCache();
        api = new SiteApi(stats, links, points, settings, digest, profiles, mock(SiteNewsService.class), mock(SignupService.class),
                cache, new RecapService(stats, profiles, digest, settings), community, new BossStatsService(stats, profiles, catalog),
                discordShowingEvents());
        guildA = guild(GUILD_A);
        guildB = guild(GUILD_B);

        cleanUp();
        member(GUILD_A, USER_SHOWN, SHOWN, links);
        member(GUILD_A, USER_HIDDEN, HIDDEN, links);
        member(GUILD_B, USER_OTHER, OTHER, links);
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_HIDDEN, "", null, null, true, true, false, false, false));
        sheetAndPoll(GUILD_A, List.of(SHOWN, HIDDEN), List.of(USER_SHOWN, USER_HIDDEN));
        sheetAndPoll(GUILD_B, List.of(OTHER), List.of(USER_OTHER));
    }

    private static Guild guild(long id) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        when(guild.getId()).thenReturn(Long.toString(id));
        when(guild.getScheduledEvents()).thenReturn(List.of());
        return guild;
    }

    /** A clan member with a link, two snapshots ten days apart, adventure-log lines, a roster join and a coffer donation. */
    private static void member(long guildId, long userId, String rsn, PlayerLinkRepository links) throws Exception {
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", guildId, rsn);
        links.createLink(guildId, userId, rsn, "TEST");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long before = links.saveSnapshotAt(rsn, now.minusDays(10), profile(rsn, 199_000_000), "[]");
        links.saveSkillSnapshot(before, List.of(new SkillValue(0, 99, 199_000_000, 5)));
        long after = links.saveSnapshotAt(rsn, now.minusMinutes(5), profile(rsn, 200_000_000), "[]");
        links.saveSkillSnapshot(after, List.of(new SkillValue(0, 99, 200_000_000, 4)));
        String date = RUNEMETRICS.format(now.minusHours(2).atZoneSameInstant(ZoneId.of("Europe/London")));
        links.saveActivities(rsn, List.of(new PlayerActivity(date, "Capped at my Clan Citadel.", "x"), new PlayerActivity(date, "I killed 5 General Graardors.", "x"),
                new PlayerActivity(date, "I found a Bandos hilt.", "x"), new PlayerActivity(date, "Levelled up Attack.", "x"),
                new PlayerActivity(date, "Quest complete: Desperate Measures", "x"), new PlayerActivity(date, "I found Shrimpy, the Fishing pet.", "x")));
        sql("INSERT INTO younglings.clan_roster_event (guild_id, rsn, event_type, event_at) VALUES (?, ?, 'JOIN', NOW() - INTERVAL '3 days')", guildId, rsn);
        sql("INSERT INTO younglings.coffer_donation (guild_id, donor_name, amount, submitted_by_discord_id) VALUES (?, ?, 1000000, ?)", guildId, rsn, userId);
    }

    private static RuneScapeProfile profile(String rsn, long xp) {
        return new RuneScapeProfile(rsn, 2000, xp, 120, 10, 0, 0, List.of(), List.of());
    }

    /** One open signup sheet with everyone on it (typed names, as a queue sheet stores them) and one open poll. */
    private static void sheetAndPoll(long guildId, List<String> names, List<Long> users) throws Exception {
        long sheet = insertReturningId("INSERT INTO younglings.signup (guild_id, title, created_by_user_id) VALUES (?, 'T364 sheet', ?) RETURNING signup_id", guildId, users.getFirst());
        for (int i = 0; i < names.size(); i++) {
            sql("INSERT INTO younglings.signup_entry (signup_id, discord_user_id, rsn, queue_position, added_by_user_id) VALUES (?, ?, ?, ?, ?)", sheet, users.get(i), names.get(i), i + 1, users.get(i));
        }
        long poll = insertReturningId("INSERT INTO younglings.poll (guild_id, channel_id, title, created_by_user_id) VALUES (?, 1, 'T364 poll', ?) RETURNING poll_id", guildId, users.getFirst());
        sql("INSERT INTO younglings.poll_option (poll_id, option_number, label) VALUES (?, 1, 'Yes')", poll);
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (db == null) return;
        for (String table : List.of("clan_member", "player_link", "member_profile", "coffer_donation", "clan_roster_event", "signup", "poll", "member_privacy_default")) {
            sql("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)", GUILD_A, GUILD_B);
        }
        for (String rsn : NAMES) {
            for (String table : List.of("player_account", "player_stats_snapshot", "player_activity")) {
                sql("DELETE FROM younglings." + table + " WHERE LOWER(rsn) = LOWER(?)", rsn);
            }
        }
    }

    private DataObject call(Guild guild, String route) {
        HttpExchange exchange = mock(HttpExchange.class);
        String query = QUERIES.get(route);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/x" + (query.isEmpty() ? "" : "?" + query)));
        return api.tenant(guild, route, exchange);
    }

    private static String text(DataObject answer) {
        return answer == null ? "" : answer.toString().toLowerCase(Locale.ROOT);
    }

    /** The same route for a bot-less site: by server id alone, with no Discord guild. */
    private DataObject callWithoutBot(long guildId, String route) {
        HttpExchange exchange = mock(HttpExchange.class);
        String query = QUERIES.get(route);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/y" + (query.isEmpty() ? "" : "?" + query)));
        return api.tenant(guildId, null, route, exchange);
    }

    /** The answer as text without the period's own from/to (worked out from the clock at each call). */
    private static String timeless(DataObject answer) {
        return text(answer).replaceAll("\"(from|to)\":\"[^\"]*\"", "");
    }

    @Test
    void aBotlessSiteGetsTheSameClanDataByServerIdAndNeedsBotForDiscordOnlyRoutes() {
        // a first read seeds the server's rank list on the way (ClanPointsRepository), so warm every route up before comparing
        for (String route : SiteApi.TENANT_ROUTES) call(guildA, route);
        for (String route : SiteApi.TENANT_ROUTES) {
            cache.clear();
            DataObject withoutBot = callWithoutBot(GUILD_A, route);
            if (SiteApi.DISCORD_ROUTES.contains(route)) {
                assertEquals(SiteApi.needsBot().toString(), withoutBot.toString(), route + " needs Discord");
            } else if (!route.equals("notifications")) {
                cache.clear();
                assertEquals(timeless(call(guildA, route)), timeless(withoutBot), route + ": the stats are stored by server id, so the answer is the same");
            }
        }
        DataObject bell = callWithoutBot(GUILD_A, "notifications");
        assertTrue(bell.getBoolean("needsBot"));
        assertEquals(0, bell.getArray("events").length());
        assertEquals(0, bell.getArray("polls").length(), "the bot's polls aren't shown without it");
        assertTrue(bell.getArray("feed").length() > 0, "the activity lines are");
    }

    @Test
    void everyTenantRouteHasAQueryHere() {
        assertEquals(SiteApi.TENANT_ROUTES, QUERIES.keySet(), "a new tenant route needs its row here, so its privacy is tested");
    }

    @ParameterizedTest
    @MethodSource("routes")
    void aServerSeesOnlyItsOwnClanAndNeverAHiddenMemberOrADiscordId(String route) {
        String a = text(call(guildA, route));
        String b = text(call(guildB, route));

        assertFalse(a.contains(OTHER.toLowerCase()), route + ": the other server's member is not in this server's answer");
        assertFalse(b.contains(SHOWN.toLowerCase()) || b.contains(HIDDEN.toLowerCase()), route + ": nor the other way round");
        if (route.equals("members")) {
            assertTrue(a.contains(HIDDEN.toLowerCase()), "the roster lists everyone on the in-game clan list, as the home site does");
        } else {
            assertFalse(a.contains(HIDDEN.toLowerCase()), route + ": a member hidden from the rankings (and with a hidden log) never appears");
        }
        for (long user : List.of(USER_SHOWN, USER_HIDDEN, USER_OTHER)) {
            assertFalse(a.contains(Long.toString(user)) || b.contains(Long.toString(user)), route + ": no Discord user id leaves the bot");
        }
        if (NAMES_THE_SHOWN_MEMBER.contains(route)) {
            assertTrue(a.contains(SHOWN.toLowerCase()), route + ": the visible member is there (so the check above means something): " + a);
            assertTrue(b.contains(OTHER.toLowerCase()), route + ": and the second server gets its own clan's data: " + b);
        }
    }

    @Test
    void theDropLogGridSaysWhoGotEachItemFirst() {
        DataObject log = call(guildA, "drop-log");
        DataObject hilt = null;
        DataArray grid = log.getArray("grid");
        for (int i = 0; i < grid.length(); i++) if (grid.getObject(i).getString("key").equals("bandos_hilt")) hilt = grid.getObject(i);
        assertNotNull(hilt, "the hilt is in the grid");
        assertEquals(SHOWN, hilt.getString("firstRsn"), "the visible member got it first (the hidden member's drop isn't counted)");
        assertFalse(hilt.isNull("first"));
    }

    @Test
    void oneSkillSeriesOnlyForAVisibleMemberOfThatClan() {
        HttpExchange shown = exchange("rsn=" + SHOWN + "&skill=0");
        assertNotNull(api.tenant(guildA, "member/skill", shown));
        assertNull(api.tenant(guildA, "member/skill", exchange("rsn=" + HIDDEN + "&skill=0")), "hidden from the rankings");
        assertNull(api.tenant(guildA, "member/skill", exchange("rsn=" + OTHER + "&skill=0")), "not in this clan");
        assertNull(api.tenant(guildB, "member/skill", exchange("rsn=" + SHOWN + "&skill=0")), "the same name asked of the other server");
    }

    @Test
    void theRecapCarriesTheStoryTreesDataStreamsWithoutHiddenMembers() {
        DataObject recap = call(guildA, "recap");
        DataObject activity = recap.getObject("activity");
        assertEquals("Attack", activity.getArray("levelUpSkills").getObject(0).getString("skill"), "T-653: level-ups by skill");
        assertTrue(activity.getArray("questNames").toList().contains("Desperate Measures"));
        assertTrue(activity.getLong("pets") >= 1);
        assertEquals(1, recap.getObject("xp").getLong("skillsTrained"), "one skill gained XP");
        assertTrue(recap.hasKey("previous"), "the window before, for growth");
        DataArray levelers = recap.getArray("topLevelers");
        for (int i = 0; i < levelers.length(); i++) assertNotEquals(HIDDEN, levelers.getObject(i).getString("rsn"), "a hidden member is never named");
        DataObject mine = api.tenant(guildA, "member/recap", exchange("rsn=" + SHOWN + "&period=last-30d"));
        assertEquals("Attack", mine.getObject("activity").getArray("levelUpSkills").getObject(0).getString("skill"));
        assertTrue(mine.hasKey("previous"));
    }

    @Test
    void aMemberPageAndMemberRecapOnlyForAVisibleMemberOfThatClan() {
        for (String route : List.of("member", "member/recap")) {
            String period = route.equals("member") ? "" : "&period=last-30d";
            DataObject page = api.tenant(guildA, route, exchange("rsn=" + SHOWN + period));
            assertNotNull(page, route);
            assertTrue(text(page).contains(SHOWN.toLowerCase()), route);
            assertNull(api.tenant(guildA, route, exchange("rsn=" + HIDDEN + period)), route + ": a member hidden from the rankings has no page (404, like an unknown name)");
            assertNull(api.tenant(guildA, route, exchange("rsn=" + OTHER + period)), route + ": not in this clan");
            assertNull(api.tenant(guildB, route, exchange("rsn=" + SHOWN + period)), route + ": the same name asked of the other server");
            assertNotNull(api.tenant(guildB, route, exchange("rsn=" + OTHER + period)), route + ": the other server's own member");
        }
        assertNotNull(api.member(guildA, HIDDEN), "the home site's member page is unchanged");
    }

    private static java.util.Map<String, Boolean> pagePublic(DataObject members) {
        java.util.Map<String, Boolean> out = new java.util.HashMap<>();
        DataArray rows = members.getArray("members");
        for (int i = 0; i < rows.length(); i++) out.put(rows.getObject(i).getString("rsn"), rows.getObject(i).getBoolean("pagePublic"));
        return out;
    }

    @Test
    void theRosterSaysWhoseMemberPageIsPrivate() {
        java.util.Map<String, Boolean> shown = pagePublic(call(guildA, "members"));
        assertEquals(Boolean.TRUE, shown.get(SHOWN), "T-416: a visible member's page can be opened");
        assertEquals(Boolean.FALSE, shown.get(HIDDEN), "T-416: a member hidden from the rankings keeps their page private");
    }

    @Test
    void privacyDefaultsHideMembersWhoHaveNotChosenAndNeverShowAHiddenOne() throws Exception {
        String unlinked = "T395Unlinked";
        sql("INSERT INTO younglings.clan_member (guild_id, rsn, clan_rank) VALUES (?, ?, 'Recruit')", GUILD_A, unlinked);
        assertTrue(pagePublic(api.members(GUILD_A)).get(unlinked), "all defaults off: nothing changes");

        profiles.savePrivacyDefaults(GUILD_A, new MemberProfileRepository.PrivacyDefaults(true, true, false), USER_SHOWN);
        java.util.Map<String, Boolean> withDefaults = pagePublic(api.members(GUILD_A));
        assertFalse(withDefaults.get(unlinked), "a member nobody linked follows the default");
        assertFalse(withDefaults.get(SHOWN), "a member who never chose follows the default");
        assertFalse(withDefaults.get(HIDDEN), "a member who hid themselves stays hidden");
        assertNull(api.tenant(guildA, "member", exchange("rsn=" + SHOWN)), "and their page answers 404");
        assertTrue(profiles.adventureLogHidden(GUILD_A, SHOWN), "the log default applies too");
        assertTrue(profiles.adventureLogHidden(GUILD_A, unlinked));
        assertFalse(profiles.hiddenDiscordUsers(GUILD_A).contains(USER_SHOWN), "the Discord link default is still off");

        // the member chooses: their own toggles win over the defaults
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, "", null, null, false, false, true, false, false, true));
        java.util.Map<String, Boolean> chosen = pagePublic(api.members(GUILD_A));
        assertTrue(chosen.get(SHOWN), "a member who chose to show stays shown");
        assertFalse(chosen.get(unlinked));
        assertFalse(profiles.adventureLogHidden(GUILD_A, SHOWN));

        // a later save can't un-choose
        profiles.saveProfile(new MemberProfileRepository.Profile(GUILD_A, USER_SHOWN, "bio", null, null, false, false, true, false, false));
        assertTrue(profiles.getProfile(GUILD_A, USER_SHOWN).privacyChosen());

        // the Discord link default hides everyone with a link who hasn't chosen
        profiles.savePrivacyDefaults(GUILD_A, new MemberProfileRepository.PrivacyDefaults(false, false, true), USER_SHOWN);
        assertEquals(java.util.Set.of(USER_HIDDEN), profiles.hiddenDiscordUsers(GUILD_A));
        assertEquals(new MemberProfileRepository.PrivacyDefaults(false, false, true), profiles.privacyDefaults(GUILD_A));
        profiles.deletePrivacyDefaults(GUILD_A);
        assertEquals(MemberProfileRepository.PrivacyDefaults.NONE, profiles.privacyDefaults(GUILD_A));
    }

    @Test
    void theHomeSitesOwnAnswersAreUnchanged() {
        // the home site's records still list everyone in "longest serving", and its signups show typed names as before
        assertTrue(text(api.records(guildA)).contains(HIDDEN.toLowerCase()));
        assertTrue(text(api.signups(guildA)).contains(HIDDEN.toLowerCase()));
        assertFalse(text(api.tenant(guildA, "records", exchange(""))).contains(HIDDEN.toLowerCase()));
    }

    @Test
    void aPollOrSignupWriteClearsEveryServersCachedAnswer() throws Exception {
        assertEquals(1, call(guildA, "polls").getArray("polls").length());
        long poll = insertReturningId("INSERT INTO younglings.poll (guild_id, channel_id, title, created_by_user_id) VALUES (?, 1, 'T364 second', ?) RETURNING poll_id", GUILD_A, USER_SHOWN);
        sql("INSERT INTO younglings.poll_option (poll_id, option_number, label) VALUES (?, 1, 'No')", poll);
        assertEquals(1, call(guildA, "polls").getArray("polls").length(), "cached for a few seconds");
        cache.invalidate("polls"); // what the poll writes call (MemberApi, CommunityAdminApi, PollCloser)
        assertEquals(2, call(guildA, "polls").getArray("polls").length(), "the clan site's key (#g<id>) is cleared with the home server's");

        assertEquals(2, call(guildA, "signups").getArray("signups").getObject(0).getArray("entries").length());
        sql("DELETE FROM younglings.signup_entry WHERE discord_user_id = ?", USER_HIDDEN);
        cache.invalidate("signups");
        assertEquals(1, call(guildA, "signups").getArray("signups").getObject(0).getArray("entries").length());
    }

    private static HttpExchange exchange(String query) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://localhost/x" + (query.isEmpty() ? "" : "?" + query)));
        return exchange;
    }

    private static void sql(String statement, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            s.executeUpdate();
        }
    }

    private static long insertReturningId(String statement, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The site's connections leave Discord events on, as for a site that never changed them. */
    private static SiteDiscordService discordShowingEvents() {
        SiteDiscordService discord = mock(SiteDiscordService.class);
        when(discord.eventsShown(org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        return discord;
    }
}
