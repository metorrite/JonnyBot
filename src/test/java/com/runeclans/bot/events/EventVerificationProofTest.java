package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRepository.Claim;
import com.runeclans.bot.events.EventRepository.ClaimKind;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.events.EventRoster.Verification;
import com.runeclans.bot.events.EventRules.Evidence;
import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import com.runeclans.bot.events.EventRules.Scope;
import com.runeclans.bot.events.EventRules.ScoringMode;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.TeamRules.Kind;
import com.runeclans.bot.points.PointsRepository;
import com.runeclans.bot.points.PointsService;
import com.runeclans.bot.points.RewardsRepository;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification proof sent with {@code /submit event-verify}, over a real {@link EventService} on the developer's local Postgres (skipped without one):
 * approving it verifies the player, denying it turns them down with the reason so they can send new proof, and it never touches tiles or points.
 * Everything is made in a made-up server and removed afterwards.
 */
class EventVerificationProofTest {
    private static final long G = 999_000_000_000_000_061L;
    private static ConnectionSupplier supplier;
    private EventRepository repository;
    private EventService service;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return Kind.HOME_CLAN;
            }
        };
        service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds, new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    private long event(String config) {
        long id = service.createEvent(G, 9L, new EventService.EventInput("Verify", "", "custom", Scope.PRIVATE, ScoringMode.INDIVIDUAL, null, null, 0, DataObject.fromJson(config)));
        service.setObjectives(G, id, List.of(new ObjectiveSpec("kill", "Kill it", "", 10, Evidence.PROOF, 1, 1, List.of())));
        service.moveStage(G, id, Status.SIGNUPS);
        service.join(G, id, G, 501, null, true, null);
        return id;
    }

    private static final String ON = "{\"verification\":{\"required\":true,\"gate\":\"TEAMS\"}}";

    @Test
    void approvingTheProofVerifiesThePlayer() {
        long id = event(ON);
        long claimId = service.submitVerification(G, id, 501, "my bank", "https://cdn.discordapp.com/x.png");
        Claim claim = repository.claim(id, claimId).orElseThrow();
        assertEquals(ClaimKind.VERIFICATION, claim.kind());
        assertEquals(Verification.PENDING, repository.participant(id, 501).orElseThrow().verification());

        service.decideClaim(G, id, claimId, true, "", 9L);
        assertEquals(Verification.VERIFIED, repository.participant(id, 501).orElseThrow().verification());
        assertEquals(ClaimStatus.APPROVED, repository.claim(id, claimId).orElseThrow().status());
        assertNull(repository.claim(id, claimId).orElseThrow().progressId(), "verification gives no points");
    }

    @Test
    void denyingTheProofTurnsThemDownWithTheReasonAndTheyMaySendNewProof() {
        long id = event(ON);
        long first = service.submitVerification(G, id, 501, "", "https://cdn.discordapp.com/x.png");
        service.decideClaim(G, id, first, false, "too blurry", 9L);
        var player = repository.participant(id, 501).orElseThrow();
        assertEquals(Verification.REJECTED, player.verification());
        assertEquals("too blurry", player.verificationNote());

        long second = service.submitVerification(G, id, 501, "", "https://cdn.discordapp.com/y.png");
        service.decideClaim(G, id, second, true, "", 9L);
        assertEquals(Verification.VERIFIED, repository.participant(id, 501).orElseThrow().verification());
    }

    @Test
    void oneVerificationWaitsAtATimeAndAVerifiedPlayerCannotSendMore() {
        long id = event(ON);
        service.submitVerification(G, id, 501, "", "https://cdn.discordapp.com/x.png");
        EventProblem again = assertThrows(EventProblem.class, () -> service.submitVerification(G, id, 501, "", "https://cdn.discordapp.com/y.png"));
        assertTrue(again.getMessage().contains("already waiting"), again.getMessage());

        service.setVerification(G, id, 501, Verification.WAIVED, 9L, "");
        EventProblem done = assertThrows(EventProblem.class, () -> service.submitVerification(G, id, 501, "", "https://cdn.discordapp.com/z.png"));
        assertTrue(done.getMessage().contains("already verified"), done.getMessage());
    }

    @Test
    void anEventThatDoesNotAskForVerificationRefusesItAndNeedsProofAttached() {
        long off = event("{}");
        EventProblem refused = assertThrows(EventProblem.class, () -> service.submitVerification(G, off, 501, "", "https://cdn.discordapp.com/x.png"));
        assertEquals(409, refused.status());
        long on = event(ON);
        EventProblem noProof = assertThrows(EventProblem.class, () -> service.submitVerification(G, on, 501, "note only", null));
        assertEquals(400, noProof.status());
        EventProblem stranger = assertThrows(EventProblem.class, () -> service.submitVerification(G, on, 777, "", "https://cdn.discordapp.com/x.png"));
        assertEquals(404, stranger.status());
    }

    @Test
    void verificationProofDoesNotCountAgainstThePlayersLimitOfTileProof() {
        long id = event(ON);
        service.setVerification(G, id, 501, Verification.WAIVED, 9L, "");
        assertEquals(0, repository.pendingClaimsFor(id, 501));
        assertEquals(0, repository.pendingVerificationClaimsFor(id, 501));
    }
}
