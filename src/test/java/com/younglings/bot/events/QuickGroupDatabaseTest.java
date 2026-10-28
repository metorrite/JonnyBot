package com.younglings.bot.events;

import com.younglings.bot.events.QuickGroupRepository.Joined;
import com.younglings.bot.events.QuickGroupRepository.Settings;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The group finder over real SQL (T-589): a group is an event row the event engine never sees, its role slots fill one at a time, and it closes by
 * itself. Runs over the developer's local Postgres (skipped without one); made-up server ids.
 */
class QuickGroupDatabaseTest {
    private static final long BASE = Long.parseLong("9".repeat(17)) * 10;
    private static final long GUILD = BASE + 589, HOST = 11, ANN = 12, BOB = 13, CAT = 14;
    private static ConnectionSupplier supplier;
    private EventRepository events;
    private QuickGroupRepository groups;
    private EventService service;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        events = new EventRepository(supplier);
        groups = new QuickGroupRepository(supplier, events);
        service = new EventService(events, new EventTemplates(List.of(new CustomTemplate())), new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        }, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection()) {
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.group_finder_settings WHERE guild_id = ?")) {
                s.setLong(1, GUILD);
                s.executeUpdate();
            }
        }
    }

    private long group(Instant startsAt) {
        DataArray slots = DataArray.empty().add(DataObject.empty().put("role", "Base").put("places", 1)).add(DataObject.empty().put("role", "DPS").put("places", 2));
        QuickGroups.Plan plan = QuickGroups.check(DataObject.empty().put("activity", "Solak").put("startsAt", startsAt.toString()).put("slots", slots), startsAt);
        return groups.create(GUILD, plan, HOST);
    }

    @Test
    void settingsAreOffUntilSaved() {
        assertEquals(Settings.OFF, groups.settings(GUILD));
        groups.saveSettings(GUILD, new Settings(true, 42L), HOST);
        assertEquals(new Settings(true, 42L), groups.settings(GUILD));
        groups.saveSettings(GUILD, new Settings(false, null), HOST);
        assertEquals(new Settings(false, null), groups.settings(GUILD));
    }

    @Test
    void aGroupIsAnEventRowTheEventEngineAndItsListsNeverSee() {
        Instant now = Instant.now();
        long id = group(now.plus(Duration.ofHours(1)));
        groups.join(GUILD, id, ANN, new QuickGroups.Slot("DPS", 2));

        assertEquals(List.of(id), groups.open(GUILD, now, 10).stream().map(QuickGroupRepository.Group::id).toList());
        assertTrue(events.event(GUILD, id).isPresent(), "stored with the events");
        assertTrue(events.events(GUILD, List.of(), 50).stream().noneMatch(QuickGroups::isGroup), "not in the event manager's list");
        assertEquals(0, events.countOpen(GUILD), "doesn't count towards the open-event limit");
        assertTrue(events.eventsForUser(GUILD, ANN, 10).isEmpty(), "not in a member's events");
        assertTrue(events.eventsForUserEverywhere(ANN, 50).stream().noneMatch(QuickGroups::isGroup));
        assertTrue(events.eventsInStatus(EventRules.Status.SIGNUPS).stream().noneMatch(QuickGroups::isGroup), "not swept by the signup sweeps");
        EventProblem missing = assertThrows(EventProblem.class, () -> service.event(GUILD, id));
        assertEquals(404, missing.status());
        assertThrows(EventProblem.class, () -> service.join(GUILD, id, GUILD, BOB, null, false, null), "the events join can't be used to skip the role slots");
    }

    @Test
    void roleSlotsFillOneAtATimeAndAMemberCanSwitchOrLeave() {
        long id = group(Instant.now().plus(Duration.ofHours(1)));
        QuickGroups.Slot base = new QuickGroups.Slot("Base", 1), dps = new QuickGroups.Slot("DPS", 2);
        assertEquals(Joined.JOINED, groups.join(GUILD, id, ANN, base));
        assertEquals(Joined.FULL, groups.join(GUILD, id, BOB, base));
        assertEquals(Joined.ALREADY, groups.join(GUILD, id, ANN, base));
        assertEquals(Joined.JOINED, groups.join(GUILD, id, BOB, dps));
        assertEquals(Joined.SWITCHED, groups.join(GUILD, id, ANN, dps), "Ann moves from Base to DPS");
        assertEquals(Joined.FULL, groups.join(GUILD, id, CAT, dps));
        assertEquals(Joined.JOINED, groups.join(GUILD, id, CAT, base), "Ann's Base place came free");
        assertEquals(List.of("DPS", "DPS", "Base"), groups.members(id).stream().map(QuickGroupRepository.Member::role).toList());

        assertTrue(groups.leave(id, BOB));
        assertFalse(groups.leave(id, BOB));
        assertEquals(2, groups.members(id).size());

        assertTrue(groups.close(GUILD, id));
        assertFalse(groups.close(GUILD, id), "already closed");
        assertEquals(Joined.CLOSED, groups.join(GUILD, id, BOB, dps));
        assertTrue(groups.open(GUILD, Instant.now(), 10).isEmpty());
    }

    @Test
    void groupsCloseByThemselvesTwoHoursAfterTheirStartAndHostsAreCounted() {
        Instant now = Instant.now();
        long soon = group(now.plus(Duration.ofMinutes(5)));
        long later = group(now.plus(Duration.ofDays(1)));
        assertEquals(2, groups.countOpen(GUILD, HOST, now)[0]);
        assertEquals(2, groups.countOpen(GUILD, HOST, now)[1]);
        assertEquals(0, groups.countOpen(GUILD, ANN, now)[1]);

        Instant past = now.plus(Duration.ofHours(3));
        assertEquals(List.of(soon), groups.open(GUILD, now, 10).stream().map(QuickGroupRepository.Group::id).filter(g -> g == soon).toList());
        assertEquals(List.of(later), groups.open(GUILD, past, 10).stream().map(QuickGroupRepository.Group::id).toList(), "no longer listed once its time is past");
        List<Long> closed = groups.closeEnded(past).stream().map(QuickGroupRepository.Group::id).filter(g -> g == soon || g == later).toList();
        assertEquals(List.of(soon), closed);
        assertEquals(EventRules.Status.DONE, groups.group(GUILD, soon).orElseThrow().status());
    }

    @Test
    void theDiscordPostIsRememberedAndForgotten() {
        long id = group(Instant.now().plus(Duration.ofHours(1)));
        assertTrue(groups.post(id).isEmpty());
        groups.savePost(id, 21, 22);
        assertEquals(new QuickGroupRepository.Post(21, 22), groups.post(id).orElseThrow());
        groups.forgetPost(id);
        assertTrue(groups.post(id).isEmpty());
    }
}
