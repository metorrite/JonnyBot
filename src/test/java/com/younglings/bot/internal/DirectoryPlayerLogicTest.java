package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfile.Visibility;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.runescape.SkillValue;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which clans a player's public profile shows, and its stats ({@link DirectoryPlayerLogic}), over plain lists. Fake ids built at run time. */
class DirectoryPlayerLogicTest {
    private static final long GUILD_A = Long.parseLong("4".repeat(17) + "1");
    private static final long GUILD_B = Long.parseLong("4".repeat(17) + "2");
    private static final long GUILD_C = Long.parseLong("4".repeat(17) + "3");
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 26, 12, 0, 0, 0, ZoneOffset.UTC);

    /** An in-memory source: rosters by server, profiles, clan names, hidden members and snapshots. */
    private static final class Fake implements DirectoryPlayerLogic.Source {
        final Map<Long, List<MemberRow>> rosters = new HashMap<>();
        final Map<Long, ClanProfile> profiles = new HashMap<>();
        final Map<Long, String> clanNames = new HashMap<>();
        final Set<String> hidden = new HashSet<>();
        final List<StatsSnapshotRow> snapshots = new ArrayList<>();
        final Map<Long, List<SkillValue>> skills = new HashMap<>();

        @Override
        public List<Long> guildsWithActiveMember(String rsn) {
            return rosters.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(m -> m.rsn().equalsIgnoreCase(rsn))).map(Map.Entry::getKey).sorted().toList();
        }

        @Override
        public Optional<ClanProfile> profile(long guildId) {
            return Optional.ofNullable(profiles.get(guildId));
        }

        @Override
        public String clanName(long guildId) {
            return clanNames.get(guildId);
        }

        @Override
        public MemberRow visibleMember(long guildId, String rsn) {
            if (hidden.contains(guildId + ":" + rsn.toLowerCase())) return null;
            return rosters.getOrDefault(guildId, List.of()).stream().filter(m -> m.rsn().equalsIgnoreCase(rsn.trim())).findFirst().orElse(null);
        }

        @Override
        public int maxRankOrder(long guildId) {
            return 11;
        }

        @Override
        public List<StatsSnapshotRow> snapshots(String rsn) {
            return snapshots;
        }

        @Override
        public List<SkillValue> skills(long snapshotId) {
            return skills.getOrDefault(snapshotId, List.of());
        }

        /** A listed clan (public, verified for its current clan name) with this player on its roster. */
        Fake clan(long guildId, String slug, String name, Visibility visibility, String rsn, OffsetDateTime lastPolled) {
            profiles.put(guildId, profile(guildId, slug, name, visibility, null));
            clanNames.put(guildId, name);
            rosters.computeIfAbsent(guildId, g -> new ArrayList<>()).add(row(rsn, lastPolled));
            return this;
        }
    }

    private static ClanProfile profile(long guildId, String slug, String name, Visibility visibility, Instant suspended) {
        return new ClanProfile(guildId, visibility, slug, name, "", "", null, null, null, EnumSet.noneOf(ClanProfile.Focus.class), ClanProfile.Recruiting.CLOSED, "", null,
                false, name, Instant.EPOCH, null, suspended, suspended == null ? null : "spam", null);
    }

    private static MemberRow row(String rsn, OffsetDateTime lastPolled) {
        return new MemberRow(rsn, "Sergeant", 4, 200_000_000, 0, null, NOW.minusDays(100), 0, false, false, 2000, 120, lastPolled);
    }

    private static StatsSnapshotRow snapshot(long id, OffsetDateTime at, long totalXp) {
        return new StatsSnapshotRow(id, at, 2000 + (int) id, totalXp, 120, 50 + (int) id, 0, 0);
    }

    @Test
    void namesAreTrimmedAndCheckedAgainstTheRuneScapeRules() {
        assertEquals("Some Name", DirectoryPlayerLogic.cleanRsn("  Some Name "));
        assertEquals("a_b-c", DirectoryPlayerLogic.cleanRsn("a_b-c"));
        assertEquals("x", DirectoryPlayerLogic.cleanRsn("x"));
        assertEquals("123456789012", DirectoryPlayerLogic.cleanRsn("123456789012"));
        assertNull(DirectoryPlayerLogic.cleanRsn("1234567890123"), "13 characters is too long");
        assertNull(DirectoryPlayerLogic.cleanRsn("   "));
        assertNull(DirectoryPlayerLogic.cleanRsn(null));
        assertNull(DirectoryPlayerLogic.cleanRsn("bad;name"));
        assertNull(DirectoryPlayerLogic.cleanRsn("dröm"));
        assertNull(DirectoryPlayerLogic.cleanRsn("a%20b"));
    }

    @Test
    void anUnknownNameHasNoAnswer() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Known", NOW);
        assertNull(DirectoryPlayerLogic.answer("Nobody", fake, NOW));
    }

    @Test
    void aMemberHiddenFromTheRankingsLooksLikeANameNobodyKnows() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Shy", NOW);
        fake.hidden.add(GUILD_A + ":shy");
        assertNull(DirectoryPlayerLogic.answer("Shy", fake, NOW));
    }

    @Test
    void onlyClansThePublicDirectoryListsAreIncluded() {
        Fake fake = new Fake()
                .clan(GUILD_A, "alpha", "Alpha", Visibility.PRIVATE, "Player", NOW)
                .clan(GUILD_B, "beta", "Beta", Visibility.UNLISTED, "Player", NOW)
                .clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "Player", NOW);
        fake.clanNames.put(GUILD_C, "Renamed since"); // verified for its old name: no longer listed
        assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW), "private, unlisted and no longer verified clans are all left out");

        fake.clanNames.put(GUILD_C, "Gamma");
        DataObject answer = DirectoryPlayerLogic.answer("Player", fake, NOW);
        assertEquals(1, answer.getArray("clans").length());
        assertEquals("gamma", answer.getArray("clans").getObject(0).getString("slug"));
    }

    @Test
    void aSuspendedListingOrOneWithoutAnAddressIsLeftOut() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW).clan(GUILD_B, "beta", "Beta", Visibility.PUBLIC, "Player", NOW);
        fake.profiles.put(GUILD_A, profile(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, Instant.EPOCH));
        fake.profiles.put(GUILD_B, profile(GUILD_B, " ", "Beta", Visibility.PUBLIC, null));
        assertNull(DirectoryPlayerLogic.answer("Player", fake, NOW));
    }

    @Test
    void everyIncludedClanIsListedTheMostRecentlyPolledFirst() {
        Fake fake = new Fake()
                .clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW.minusDays(3))
                .clan(GUILD_B, "beta", "Beta", Visibility.PUBLIC, "player", NOW.minusHours(1))
                .clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "PLAYER", null);
        fake.hidden.add(GUILD_C + ":player");
        fake.clan(GUILD_C, "gamma", "Gamma", Visibility.PUBLIC, "Other", NOW);

        DataObject answer = DirectoryPlayerLogic.answer("PlAyEr", fake, NOW);
        DataArray clans = answer.getArray("clans");
        assertEquals(2, clans.length(), "the clan where they hid themselves is left out, the others stay");
        assertEquals("beta", clans.getObject(0).getString("slug"));
        assertEquals("alpha", clans.getObject(1).getString("slug"));
        assertEquals("player", answer.getString("rsn"), "the name as the most recently polled clan spells it");
        DataObject beta = clans.getObject(0);
        assertEquals("Beta", beta.getString("name"));
        assertEquals("Sergeant", beta.getString("rank"));
        assertEquals(4, beta.getInt("rankOrder"));
        assertEquals(11, beta.getInt("maxRankOrder"));
        assertEquals(NOW.minusDays(100).toString(), beta.getString("firstSeen"));
        assertEquals(NOW.minusHours(1).toString(), beta.getString("lastPolled"));
        assertFalse(beta.getBoolean("joinedExact"));
    }

    @Test
    void statsComeFromTheNewestSnapshotWithGainsOverTheDayWeekAndMonth() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        // out of order on purpose: the newest is picked by its time, not its place in the list
        fake.snapshots.add(snapshot(2, NOW.minusDays(3), 1_100));
        fake.snapshots.add(snapshot(4, NOW.minusMinutes(5), 1_500));
        fake.snapshots.add(snapshot(1, NOW.minusDays(40), 1_000));
        fake.snapshots.add(snapshot(3, NOW.minusHours(30), 1_300));
        fake.skills.put(4L, List.of(new SkillValue(0, 99, 13_034_431, 1234), new SkillValue(1, 80, 2_000_000, 99_999)));
        fake.skills.put(1L, List.of(new SkillValue(0, 1, 0, -1)));

        DataObject stats = DirectoryPlayerLogic.answer("Player", fake, NOW).getObject("stats");
        assertEquals(NOW.minusMinutes(5).toInstant().toString(), stats.getString("snapshotAt"));
        assertEquals(2004, stats.getInt("totalLevel"));
        assertEquals(1_500, stats.getLong("totalXp"));
        assertEquals(120, stats.getInt("combatLevel"));
        assertEquals(54, stats.getInt("questsComplete"));
        DataArray skills = stats.getArray("skills");
        assertEquals(2, skills.length());
        DataObject attack = skills.getObject(0);
        assertEquals(0, attack.getInt("id"));
        assertEquals("Attack", attack.getString("name"));
        assertEquals(99, attack.getInt("level"));
        assertEquals(13_034_431, attack.getLong("xp"));
        assertEquals(1234, attack.getInt("rank"));
        DataObject gains = stats.getObject("gains");
        assertEquals(200, gains.getLong("day"), "since the snapshot 30 hours ago");
        assertEquals(500, gains.getLong("week"), "since the one 40 days ago (the newest at or before a week ago)");
        assertEquals(500, gains.getLong("month"));
    }

    @Test
    void aPlayerWithNoSnapshotYetHasNoStats() {
        Fake fake = new Fake().clan(GUILD_A, "alpha", "Alpha", Visibility.PUBLIC, "Player", NOW);
        DataObject answer = DirectoryPlayerLogic.answer("Player", fake, NOW);
        assertTrue(answer.isNull("stats"));
        assertEquals(1, answer.getArray("clans").length());
    }
}
