package com.younglings.bot.events;

import com.younglings.bot.commands.signup.SignupEntry;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsService;
import com.younglings.bot.points.RewardsRepository;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to people who sign up in Discord but whom the event's rules turn away: they are left out, the reason is kept for staff and the player is
 * told once. Runs over the developer's local Postgres (skipped without one).
 */
class EventSignupsSyncTest {
    private static final long G = 999_000_000_000_000_021L;
    private static ConnectionSupplier supplier;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ?")) {
            s.setLong(1, G);
            s.executeUpdate();
        }
    }

    @Test
    void aSignupTheRulesTurnAwayIsRememberedWithItsReasonAndTheyAreToldOnce() {
        var repository = new EventRepository(supplier);
        EventKinds kinds = new EventKinds(null, null) {
            @Override
            public TeamRules.Kind kindOf(long hostGuildId, long userGuildId, long userId) {
                return userId == 502 ? TeamRules.Kind.PARTNER : TeamRules.Kind.HOME_CLAN;
            }
        };
        var service = new EventService(repository, new EventTemplates(List.of(new CustomTemplate())), kinds,
                new PointsService(new PointsRepository(supplier), new RewardsRepository(supplier)), new EventChanges());
        SignupService signupService = Mockito.mock(SignupService.class);
        Mockito.when(signupService.getEntries(7777L)).thenReturn(List.of(new SignupEntry(501, "clan member", null), new SignupEntry(502, "from elsewhere", null)));
        var signups = new EventSignups(repository, service, signupService);

        Guild guild = Mockito.mock(Guild.class);
        JDA jda = Mockito.mock(JDA.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getJDA()).thenReturn(jda);

        long id = service.createEvent(G, 9L, new EventService.EventInput("Home only", "", "custom", EventRules.Scope.PRIVATE, EventRules.ScoringMode.INDIVIDUAL, null, null, 0,
                DataObject.fromJson("{\"teamPolicy\":\"home\"}")));
        service.moveStage(G, id, EventRules.Status.SIGNUPS);
        repository.setSignup(id, 7777L);

        assertEquals(1, signups.sync(guild, id), "the clan member was added");
        var refusals = repository.refusals(id);
        assertEquals(1, refusals.size());
        assertEquals(502, refusals.get(0).userId());
        assertTrue(refusals.get(0).reason().contains("isn't open to"), refusals.get(0).reason());
        Mockito.verify(jda, Mockito.times(1)).retrieveUserById(502L);
        Mockito.verify(jda, Mockito.never()).retrieveUserById(501L);

        // the next sweep finds the same refusal and says nothing more
        assertEquals(0, signups.sync(guild, id));
        Mockito.verify(jda, Mockito.times(1)).retrieveUserById(502L);
        assertEquals(1, repository.refusals(id).size());

        // staff add them anyway as a clan member: the refusal goes away
        service.join(G, id, G, 502, null, true, TeamRules.Kind.HOME_CLAN);
        assertTrue(repository.refusals(id).isEmpty());
    }
}
