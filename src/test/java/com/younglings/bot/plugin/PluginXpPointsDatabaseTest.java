package com.younglings.bot.plugin;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Points for XP gained over real SQL (T-542): a day pays each member once, and a re-run of the day pays nothing. Skipped without a local Postgres. */
class PluginXpPointsDatabaseTest {
    private static final long GUILD = Long.parseLong("9".repeat(17) + "2");
    private static final long ALICE = Long.parseLong("1".repeat(18));
    private static final LocalDate DAY = LocalDate.parse("2026-10-25");
    private static final Instant NOW = Instant.parse("2026-10-26T00:20:00Z");
    private static ConnectionSupplier supplier;
    private PluginPointsRepository repository;
    private PointsRepository points;
    private final RuneClansHubClient hub = mock(RuneClansHubClient.class);
    private PluginXpPoints xpPoints;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        cleanUp();
        repository = new PluginPointsRepository(supplier);
        points = new PointsRepository(supplier);
        points.saveSettings(new PointsRepository.Settings(GUILD, true, "points", null));
        BotConfig config = mock(BotConfig.class);
        when(config.getPluginXpPointsEnabled()).thenReturn(true);
        when(hub.enabled()).thenReturn(true);
        when(hub.aliasIfAnswered(ALICE)).thenReturn(Optional.of("a1_alice"));
        DataArray players = DataArray.empty().add(DataObject.empty().put("alias", "a1_alice").put("xp", 1_250_000L));
        when(hub.xpGains(anyLong(), any(), any(), any(), any())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", players)));
        xpPoints = new PluginXpPoints(repository, points, new PointsService(points, new RewardsRepository(supplier)), hub, config).withClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            for (String table : List.of("plugin_xp_points_day", "plugin_xp_points_hub_day", "plugin_xp_points_rule", "points_ledger", "points_settings")) {
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                    s.setLong(1, GUILD);
                    s.executeUpdate();
                } catch (java.sql.SQLException e) {
                    // the table isn't there yet on a fresh database
                }
            }
        }
    }

    @Test
    void aDayPaysOnceAndARerunPaysNothing() {
        repository.saveXpRule(GUILD, new XpRule(true, 10, 100_000, 50), 1L, NOW);
        xpPoints.note(ALICE, List.of(GUILD), Set.of(DAY), NOW);
        assertEquals(List.of(new PluginPointsRepository.XpDay(GUILD, DAY)), repository.openXpDays(DAY.minusDays(7), DAY.plusDays(1)));

        xpPoints.sweep();
        assertEquals(50, points.balance(GUILD, ALICE), "the cap holds");
        assertTrue(repository.openXpDays(DAY.minusDays(7), DAY.plusDays(1)).isEmpty(), "settled");

        // the same day again: noted again (a late batch), swept again, settled by hand as if the bookkeeping were lost
        repository.noteXpDay(GUILD, ALICE, DAY);
        xpPoints.sweep();
        assertEquals(0, xpPoints.settle(GUILD, DAY, NOW));
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.plugin_xp_points_day SET settled_at = NULL WHERE guild_id = ?")) {
            s.setLong(1, GUILD);
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
        }
        xpPoints.sweep();
        assertEquals(50, points.balance(GUILD, ALICE), "the points ledger pays the day's key once");
        assertEquals(1, points.history(GUILD, ALICE, 10).size());
        assertEquals("RuneSync: 1.2M XP gained", points.history(GUILD, ALICE, 10).get(0).reason());
    }

    @Test
    void aMemberWhoseXpReachesOnlyTheHubIsPaidAndTheDayIsntAskedAgainAfterARestart() {
        repository.saveXpRule(GUILD, new XpRule(true, 10, 100_000, 50), 1L, NOW);
        assertTrue(repository.xpRuleGuilds().contains(GUILD));
        // the bot never saw Alice's XP: only the hub did (T-712); she is offline, so not in the member cache, but Discord says she's in the server
        JDA jda = mock(JDA.class);
        Guild guild = mock(Guild.class);
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        @SuppressWarnings("unchecked")
        CacheRestAction<Member> lookup = mock(CacheRestAction.class);
        when(lookup.submit()).thenReturn(CompletableFuture.completedFuture(mock(Member.class)));
        when(guild.retrieveMemberById(ALICE)).thenReturn(lookup);
        DataArray gainers = DataArray.empty().add(DataObject.empty().put("alias", "a1_alice").put("discordId", Long.toString(ALICE)).put("xp", 1_250_000L));
        // the other days the sweep asks about had no gainers
        when(hub.xpGainers(eq(GUILD), any())).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("players", DataArray.empty())));
        when(hub.xpGainers(GUILD, DAY)).thenReturn(new RuneClansHubClient.Answer(200, DataObject.empty().put("day", DAY.toString()).put("players", gainers)));

        xpPoints.withJda(jda).sweep();
        assertEquals(50, points.balance(GUILD, ALICE));
        assertTrue(repository.openXpDays(DAY.minusDays(7), DAY.plusDays(1)).isEmpty(), "settled");
        assertTrue(repository.hubAskedDays(DAY.minusDays(7)).contains(new PluginPointsRepository.XpDay(GUILD, DAY)));

        // a restart: a new instance remembers the day was asked
        BotConfig config = mock(BotConfig.class);
        when(config.getPluginXpPointsEnabled()).thenReturn(true);
        new PluginXpPoints(repository, points, new PointsService(points, new RewardsRepository(supplier)), hub, config)
                .withClock(Clock.fixed(NOW, ZoneOffset.UTC)).withJda(jda).sweep();
        verify(hub, times(1)).xpGainers(GUILD, DAY);
        assertEquals(50, points.balance(GUILD, ALICE));
    }

    @Test
    void noAwardWhileTheRuleIsOff() {
        repository.saveXpRule(GUILD, new XpRule(true, 10, 100_000, 0), 1L, NOW);
        xpPoints.note(ALICE, List.of(GUILD), Set.of(DAY), NOW);
        repository.saveXpRule(GUILD, new XpRule(false, 10, 100_000, 0), 1L, NOW); // switched off before the day was settled
        assertTrue(!repository.xpRuleGuilds().contains(GUILD), "the hub isn't asked for a server whose rule is off");

        xpPoints.sweep();
        assertEquals(0, points.balance(GUILD, ALICE));
        assertTrue(repository.openXpDays(DAY.minusDays(7), DAY.plusDays(1)).isEmpty(), "settled with nothing");

        // never noted where the rule is off
        xpPoints.note(ALICE, List.of(GUILD), Set.of(DAY.plusDays(1)), NOW);
        assertTrue(repository.openXpDays(DAY.minusDays(7), DAY.plusDays(2)).isEmpty());
        assertEquals(new XpRule(false, 10, 100_000, 0), repository.xpRule(GUILD), "switched off, its numbers are kept");
    }
}
