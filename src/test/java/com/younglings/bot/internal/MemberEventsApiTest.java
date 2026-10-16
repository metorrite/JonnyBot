package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.events.CustomTemplate;
import com.younglings.bot.events.EventChanges;
import com.younglings.bot.events.EventKinds;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRules;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventTemplates;
import com.younglings.bot.events.TeamRules;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a member does with events from the website: join and leave by the same rules as {@code /events join}, and only from inside the server's
 * Discord (a member who isn't in it is told to join first, with the clan's link when it has one). Runs over the developer's local Postgres
 * (skipped without one).
 */
class MemberEventsApiTest {
    private static final long G = 999_000_000_000_000_032L;
    private static final long INSIDE = 501L;
    private static final long OUTSIDE = 599L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;
    private ClanProfileRepository profiles;
    private MemberEventsApi api;
    private Guild guild;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return TeamRules.Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        profiles = Mockito.mock(ClanProfileRepository.class);
        Mockito.when(profiles.get(G)).thenReturn(Optional.empty());
        api = new MemberEventsApi(service, repository, profiles);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getName()).thenReturn("Younglings");
        Mockito.when(guild.getMemberById(INSIDE)).thenReturn(Mockito.mock(Member.class)); // anyone else isn't in the server
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long signupsOpen(String name, String config) {
        long id = service.createEvent(G, 9L, new EventService.EventInput(name, "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson(config)));
        service.setObjectives(G, id, List.of(new EventRules.ObjectiveSpec("anything", "Anything", "", 1, EventRules.Evidence.EITHER, 1, 1, List.of())));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        return id;
    }

    private static DataObject body(long eventId) {
        return DataObject.empty().put("eventId", Long.toString(eventId));
    }

    @Test
    void aMemberOutsideTheServerIsToldToJoinItAndGivenTheClansLinkWhenItHasOne() {
        long id = signupsOpen("Join me", "{}");
        EventProblem plain = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(id)));
        assertEquals(403, plain.status());
        assertTrue(plain.getMessage().contains("Younglings Discord server"), plain.getMessage());
        assertFalse(plain.getMessage().contains("http"), "no link to give");

        ClanProfile profile = Mockito.mock(ClanProfile.class);
        Mockito.when(profile.applyUrl()).thenReturn("https://discord.gg/example");
        Mockito.when(profiles.get(G)).thenReturn(Optional.of(profile));
        EventProblem withLink = assertThrows(EventProblem.class, () -> api.join(guild, OUTSIDE, body(id)));
        assertTrue(withLink.getMessage().contains("https://discord.gg/example"), withLink.getMessage());
        assertTrue(repository.participants(id).isEmpty(), "nothing was added");
    }

    @Test
    void aMemberJoinsAndLeavesWhileSignupsAreOpenByTheSameRulesAsTheCommand() {
        long id = signupsOpen("Join and leave", "{}");
        DataObject joined = api.join(guild, INSIDE, body(id));
        assertFalse(joined.getObject("result").getBoolean("waiting"));
        assertEquals(1, joined.getArray("events").length(), "their events now list it");
        assertEquals(0, joined.getArray("open").length(), "and it is no longer offered to them");
        assertEquals(409, assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, body(id))).status(), "already in");

        DataObject left = api.leave(guild, INSIDE, body(id));
        assertEquals(0, left.getArray("events").length());
        assertEquals(1, left.getArray("open").length());
        assertEquals(404, assertThrows(EventProblem.class, () -> api.leave(guild, INSIDE, body(id))).status(), "they aren't in it any more");
        assertEquals(400, assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, DataObject.empty())).status(), "say which event");
    }

    @Test
    void aClosedSignupRefusesTheMembersOwnJoinAndTheOpenListSaysSo() {
        long id = signupsOpen("Closing", "{}");
        assertFalse(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("closed"));
        service.closeSignups(G, id, 9L);
        assertTrue(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("closed"));
        EventProblem refused = assertThrows(EventProblem.class, () -> api.join(guild, INSIDE, body(id)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("Signups have closed"), refused.getMessage());
    }

    @Test
    void aMemberCanOnlyLeaveWhileTheEventIsTakingSignups() {
        long id = signupsOpen("Leave early", "{}");
        api.join(guild, INSIDE, body(id));
        service.moveStage(G, id, EventRules.Status.RUNNING);
        EventProblem refused = assertThrows(EventProblem.class, () -> api.leave(guild, INSIDE, body(id)));
        assertEquals(409, refused.status());
        assertTrue(refused.getMessage().contains("taking signups"), refused.getMessage());
    }

    @Test
    void aMemberSeesWhereTheirVerificationStandsAndWhyIfTurnedDown() {
        long id = signupsOpen("Verified", "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\",\"instructions\":\"Send a screenshot of your bank.\"}}");
        assertTrue(api.mine(guild, INSIDE).getArray("open").getObject(0).getBoolean("verification"), "players see it is asked for before joining");
        DataObject joined = api.join(guild, INSIDE, body(id));
        DataObject mine = joined.getArray("events").getObject(0).getObject("verification");
        assertTrue(mine.getBoolean("required"));
        assertEquals("PENDING", mine.getString("state"));
        assertEquals("Send a screenshot of your bank.", mine.getString("instructions"));

        service.setVerification(G, id, INSIDE, com.younglings.bot.events.EventRoster.Verification.REJECTED, 9L, "The bank was cropped");
        DataObject turnedDown = api.mine(guild, INSIDE).getArray("events").getObject(0).getObject("verification");
        assertEquals("REJECTED", turnedDown.getString("state"));
        assertEquals("The bank was cropped", turnedDown.getString("note"));

        service.setVerification(G, id, INSIDE, com.younglings.bot.events.EventRoster.Verification.VERIFIED, 9L, "");
        assertEquals("", api.mine(guild, INSIDE).getArray("events").getObject(0).getObject("verification").getString("note"), "a reason is only shown while it applies");
    }

    @Test
    void anEventWithoutVerificationShowsNothingToVerify() {
        long id = signupsOpen("Plain", "{}");
        DataObject joined = api.join(guild, INSIDE, body(id));
        DataObject verification = joined.getArray("events").getObject(0).getObject("verification");
        assertFalse(verification.getBoolean("required"));
        assertEquals("NOT_REQUIRED", verification.getString("state"));
        assertEquals("", verification.getString("instructions"));
    }
}
