package com.younglings.bot.notice;

import com.younglings.bot.config.BotConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.ApplicationInfo;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BotOwnersTest {
    private static final long APP_OWNER = 11;
    private static final long CONFIGURED = 22;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private BotConfig config;
    private JDA jda;
    private BotOwners owners;

    @BeforeEach
    void setUp() {
        config = mock(BotConfig.class);
        when(config.getOwnerIds()).thenReturn(List.of(CONFIGURED));
        jda = mock(JDA.class);
        owners = new BotOwners(config);
        owners.clock = now::get;
    }

    @SuppressWarnings("unchecked")
    private void discordSays(long ownerId) {
        ApplicationInfo info = mock(ApplicationInfo.class);
        User user = mock(User.class);
        when(user.getIdLong()).thenReturn(ownerId);
        when(info.getOwner()).thenReturn(user);
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenReturn(info);
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    @SuppressWarnings("unchecked")
    private void discordIsDown() {
        CacheRestAction<ApplicationInfo> action = mock(CacheRestAction.class);
        when(action.complete()).thenThrow(new RejectedExecutionException("The Requester has been stopped!"));
        when(jda.retrieveApplicationInfo()).thenReturn(action);
    }

    @Test
    void theApplicationOwnerAndTheConfiguredOwnersAreOwners() {
        discordSays(APP_OWNER);

        assertTrue(owners.isOwner(jda, APP_OWNER));
        assertTrue(owners.isOwner(jda, CONFIGURED));
        assertFalse(owners.isOwner(jda, 99));
    }

    @Test
    void aFailedLookupDoesNotForgetWhoWasKnown() {
        discordSays(APP_OWNER);
        assertTrue(owners.isOwner(jda, APP_OWNER));

        now.addAndGet(11 * 60_000L); // the next call looks again, and Discord is unreachable
        discordIsDown();

        assertTrue(owners.isOwner(jda, APP_OWNER));
    }

    @Test
    void aFailedFirstLookupIsRetriedSoonNotAfterTenMinutes() {
        discordIsDown();
        assertFalse(owners.isOwner(jda, APP_OWNER));
        assertTrue(owners.isOwner(jda, CONFIGURED), "configured owners never depend on Discord");

        discordSays(APP_OWNER);
        now.addAndGet(31_000);

        assertTrue(owners.isOwner(jda, APP_OWNER));
    }

    @Test
    void aSuccessfulLookupIsRememberedForAWhile() {
        discordSays(APP_OWNER);
        owners.isOwner(jda, APP_OWNER);
        owners.isOwner(jda, APP_OWNER);

        verify(jda, times(1)).retrieveApplicationInfo();
    }
}
