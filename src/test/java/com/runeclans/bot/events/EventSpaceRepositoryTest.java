package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What is recorded about an event's roles and channels, on the developer's local Postgres. Skipped where there is none. Everything is made in made-up servers and removed afterwards. */
class EventSpaceRepositoryTest {
    private static final long G = 999_000_000_000_000_031L;
    private static final long OTHER = 999_000_000_000_000_032L;

    private static ConnectionSupplier supplier;
    private EventRepository events;
    private EventSpaceRepository space;
    private long eventId;
    private long otherEventId;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        events = new EventRepository(supplier);
        space = new EventSpaceRepository(supplier, events);
        eventId = events.createEvent(G, "custom", "First event", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{}", 1L);
        otherEventId = events.createEvent(OTHER, "custom", "Other server", "", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, "{}", 1L);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id IN (?, ?)")) {
            s.setLong(1, G);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    @Test
    void rolesAndChannelsAreRecordedAndReadBack() {
        space.saveRole(eventId, "participant", 111, "First event Participant");
        space.saveRole(eventId, "team:5", 112, "First event Red");
        space.saveChannel(eventId, "category", 221, "First event", true, null);
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.saveChannel(eventId, "board", 223, "bingo-board", false, null);

        assertEquals(List.of("participant", "team:5"), space.roles(eventId).stream().map(EventSpaceRepository.RoleRow::key).toList());
        assertEquals(112L, space.roles(eventId).get(1).roleId());
        List<EventSpaceRepository.ChannelRow> channels = space.channels(eventId);
        assertEquals(3, channels.size());
        assertEquals("category", channels.get(1).parent());
        assertFalse(channels.stream().filter(c -> c.key().equals("board")).findFirst().orElseThrow().owned(), "a chosen channel is recorded, not owned");
        assertTrue(space.hasSpace(eventId));
        assertFalse(space.hasSpace(otherEventId));
        assertEquals(List.of(eventId), space.eventsWithSpace(G));
    }

    @Test
    void savingAgainReplacesTheRecordAndANewRoleStartsWithNoHolders() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "participant", 8);
        space.saveRole(eventId, "participant", 111, "Players renamed");
        assertEquals(Set.of(7L, 8L), space.members(eventId).get("participant"), "the same role keeps its holders");
        assertEquals("Players renamed", space.roles(eventId).get(0).name());

        space.saveRole(eventId, "participant", 999, "Players again");
        assertTrue(space.members(eventId).isEmpty(), "someone deleted the role on Discord, so nobody holds the new one yet");
        assertEquals(1, space.roles(eventId).size());
    }

    @Test
    void whoHoldsARoleIsRecordedAndForgottenOneByOne() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.saveRole(eventId, "organiser", 112, "Organisers");
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "participant", 7);
        space.addMember(eventId, "organiser", 7);
        space.addMember(eventId, "participant", 8);
        assertEquals(Map.of("participant", Set.of(7L, 8L), "organiser", Set.of(7L)), space.members(eventId));
        space.removeMember(eventId, "participant", 8);
        assertEquals(Set.of(7L), space.members(eventId).get("participant"));
        assertEquals(List.of(eventId), space.eventsHeldBy(G, 7));
        assertTrue(space.eventsHeldBy(G, 8).isEmpty());
        assertTrue(space.eventsHeldBy(OTHER, 7).isEmpty(), "another server's events don't count");
    }

    @Test
    void aChannelIsFoundBackToItsEventWithinTheServerOnly() {
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.saveChannel(otherEventId, "general", 333, "their-general", true, "category");
        assertEquals(eventId, space.channelById(G, 222).orElseThrow().eventId());
        assertTrue(space.channelById(G, 333).isEmpty(), "that channel belongs to another server's event");
        assertTrue(space.channelById(G, 12345).isEmpty());
        assertEquals(Map.of(222L, eventId), space.channelsOfOtherEvents(G, otherEventId));
        assertTrue(space.channelsOfOtherEvents(G, eventId).isEmpty());
    }

    @Test
    void forgettingEverythingClearsTheRolesChannelsAndHolders() {
        space.saveRole(eventId, "participant", 111, "Players");
        space.addMember(eventId, "participant", 7);
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        space.clear(eventId);
        assertTrue(space.roles(eventId).isEmpty());
        assertTrue(space.channels(eventId).isEmpty());
        assertTrue(space.members(eventId).isEmpty());
        assertFalse(space.hasSpace(eventId));
    }

    @Test
    void theStateOfTheSetupIsKeptWithTheRestOfTheEventsSetup() {
        assertFalse(events.setup(eventId).spaceApplied());
        events.markSpace(eventId, 42L, false, "the channel x is missing");
        EventRepository.Setup first = events.setup(eventId);
        assertTrue(first.spaceApplied());
        assertFalse(first.spaceComplete());
        assertEquals("the channel x is missing", first.spaceNote());
        assertEquals(42L, first.spaceAppliedBy());

        events.markSpace(eventId, 43L, true, "");
        EventRepository.Setup second = events.setup(eventId);
        assertTrue(second.spaceComplete());
        assertEquals(first.spaceAppliedAt(), second.spaceAppliedAt(), "when it was first set up stays");
        assertEquals(42L, second.spaceAppliedBy());

        events.clearSpace(eventId);
        assertFalse(events.setup(eventId).spaceApplied());
        assertFalse(events.setup(eventId).spaceComplete());
    }

    @Test
    void deletingTheEventDeletesWhatWasRecordedForIt() throws Exception {
        space.saveRole(eventId, "participant", 111, "Players");
        space.addMember(eventId, "participant", 7);
        space.saveChannel(eventId, "general", 222, "first-event-general", true, "category");
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE id = ?")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        }
        assertTrue(space.roles(eventId).isEmpty());
        assertTrue(space.channels(eventId).isEmpty());
        assertTrue(space.members(eventId).isEmpty());
    }
}
