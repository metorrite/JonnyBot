package com.younglings.bot.internal;

import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The feature status's one query over a real database (skipped without one): every table and column it names exists, and the no-row defaults. */
class FeatureStatusRepositoryDatabaseTest {
    private static final long G = 999_000_000_000_000_461L;
    /** The features' own classes that create the tables the query reads (each takes only the connection). */
    private static final List<Class<?>> TABLE_OWNERS = List.of(
            com.younglings.bot.welcome.WelcomeDatabaseInitializer.class, com.younglings.bot.goodbye.GoodbyeRepository.class,
            com.younglings.bot.accountgate.AccountGateRepository.class, com.younglings.bot.autorole.AutoroleRepository.class,
            com.younglings.bot.member.MemberProfileRepository.class, com.younglings.bot.rolepanel.RolePanelRepository.class,
            com.younglings.bot.rolekeep.RoleKeepRepository.class, com.younglings.bot.rolerules.RoleRuleRepository.class,
            com.younglings.bot.roletools.RoleToolkitRepository.class, com.younglings.bot.warning.WarningRepository.class,
            com.younglings.bot.moderation.ModCaseRepository.class, com.younglings.bot.appeal.AppealRepository.class,
            com.younglings.bot.lockdown.LockdownRepository.class, com.younglings.bot.autopurge.AutoPurgeRepository.class,
            com.younglings.bot.autodelete.AutoDeleteRepository.class, com.younglings.bot.honeypot.HoneypotRepository.class,
            com.younglings.bot.servertag.ServerTagRepository.class, com.younglings.bot.modlog.ModLogRepository.class,
            com.younglings.bot.messagelog.MessageLogRepository.class, AdminToolsStore.class,
            com.younglings.bot.staffdigest.StaffDigestRepository.class, com.younglings.bot.ban.BanAnnouncementDatabaseInitializer.class,
            com.younglings.bot.serverreminder.ServerReminderRepository.class, com.younglings.bot.autoreply.AutoReplyRepository.class,
            com.younglings.bot.customtag.CustomTagRepository.class, com.younglings.bot.embedder.EmbedderDatabaseInitializer.class,
            com.younglings.bot.feed.FeedRepository.class, com.younglings.bot.twitch.TwitchRepository.class, com.younglings.bot.kick.KickRepository.class,
            com.younglings.bot.highlight.HighlightRepository.class, com.younglings.bot.giveaway.GiveawayRepository.class,
            com.younglings.bot.suggestion.SuggestionRepository.class, com.younglings.bot.starboard.StarboardRepository.class,
            com.younglings.bot.ticket.TicketDatabaseInitializer.class, com.younglings.bot.forms.WebFormRepository.class,
            com.younglings.bot.points.PointsRepository.class, com.younglings.bot.chatlevel.ChatLevelRepository.class,
            com.younglings.bot.tempvoice.TempVoiceRepository.class, com.younglings.bot.voicetext.VoiceTextRepository.class,
            com.younglings.bot.events.EventRepository.class, com.younglings.bot.eventannounce.EventAnnounceRepository.class,
            com.younglings.bot.wheel.WheelSettingsRepository.class, com.younglings.bot.events.BingoTileRepository.class,
            com.younglings.bot.clanprofile.ClanProfileRepository.class, com.younglings.bot.runescape.RuneScapeDatabaseInitializer.class,
            com.younglings.bot.anniversary.AnniversaryRepository.class, com.younglings.bot.alliance.AllianceRepository.class,
            com.younglings.bot.plugin.RuneSyncRepository.class, com.younglings.bot.devtools.DevSnapshotRepository.class,
            com.younglings.bot.lookup.LookupReminderRepository.class, com.younglings.bot.tracking.TrackingDatabaseInitializer.class);
    private static final List<String> TOUCHED = List.of("goodbye_config", "self_role", "clan_profile", "lookup_reminder", "role_toolkit_allow",
            "tracking_group_config", "tracking_destination");
    private static ConnectionSupplier supplier;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
        for (Class<?> owner : TABLE_OWNERS) owner.getConstructor(ConnectionSupplier.class).newInstance(supplier);
        new CommunitySettings(supplier, com.younglings.bot.testsupport.SiteMappings.off(supplier)); // its tables too (it also takes the channel mappings)
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : TOUCHED) sql("DELETE FROM younglings." + table + " WHERE guild_id = " + G);
    }

    private static void sql(String statement) throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement(statement)) {
            s.executeUpdate();
        }
    }

    @Test
    void aServerThatNeverSavedAnythingHasEveryStoredFeatureOff() {
        FeatureStatusRepository.Facts facts = new FeatureStatusRepository(supplier).read(G);
        assertNotNull(facts, "the query runs: every table and column it names exists");
        assertEquals(FeatureStatusRepository.FLAGS.keySet(), facts.flags().keySet());
        for (Map.Entry<String, Boolean> flag : facts.flags().entrySet()) {
            // no profile row reads as UNLISTED (on); the snapshot list is the whole bot's, so other tests' rows may be there
            if (flag.getKey().equals("/clan/clan-profile")) assertTrue(flag.getValue());
            else if (!flag.getKey().equals("/tools/snapshots")) assertFalse(flag.getValue(), flag.getKey());
        }
        assertEquals(Set.of(), facts.toolkitGroups());
        assertEquals(Set.of(), facts.enabledLookupKinds());
        assertEquals(Set.of(), facts.trackingOff());
        assertEquals(Map.of(), facts.trackingChannels());
    }

    @Test
    void savedSettingsTurnTheirDotsOn() throws Exception {
        sql("INSERT INTO younglings.goodbye_config (guild_id, enabled) VALUES (" + G + ", TRUE)");
        sql("INSERT INTO younglings.self_role (guild_id, role_id) VALUES (" + G + ", 5)");
        sql("INSERT INTO younglings.clan_profile (guild_id, visibility) VALUES (" + G + ", 'PRIVATE')");
        sql("INSERT INTO younglings.lookup_reminder (guild_id, kind, enabled) VALUES (" + G + ", 'vos', TRUE), (" + G + ", 'reset-daily', FALSE)");
        sql("INSERT INTO younglings.role_toolkit_allow (guild_id, group_key, role_id) VALUES (" + G + ", 'admin', 7)");
        sql("INSERT INTO younglings.tracking_group_config (guild_id, group_key, enabled) VALUES (" + G + ", 'LEVEL_UPS', FALSE)");
        sql("INSERT INTO younglings.tracking_destination (guild_id, group_key, channel_id) VALUES (" + G + ", 'DROPS', 11), (" + G + ", 'DROPS', 12)");

        FeatureStatusRepository.Facts facts = new FeatureStatusRepository(supplier).read(G);
        assertTrue(facts.flag("/joins/goodbye"));
        assertTrue(facts.flag("/roles/self-roles"));
        assertFalse(facts.flag("/clan/clan-profile"), "a private profile is off");
        assertFalse(facts.flag("/joins/welcome"));
        assertEquals(Set.of("vos"), facts.enabledLookupKinds());
        assertEquals(Set.of("admin"), facts.toolkitGroups());
        assertEquals(Set.of("LEVEL_UPS"), facts.trackingOff());
        assertEquals(Map.of("DROPS", List.of(11L, 12L)), facts.trackingChannels());
    }
}
