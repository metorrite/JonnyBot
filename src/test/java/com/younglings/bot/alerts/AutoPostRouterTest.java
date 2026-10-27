package com.younglings.bot.alerts;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The one switch that keeps a bot that is not live from posting its automatic alerts into the servers' channels. */
class AutoPostRouterTest {
    private static final long DEV = 555_555_555_555_555_555L;

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    private static GuildMessageChannel channel(long id, JDA jda) {
        GuildMessageChannel channel = mock(GuildMessageChannel.class);
        when(channel.getId()).thenReturn(Long.toString(id));
        when(channel.getName()).thenReturn("admin-alerts");
        when(channel.getJDA()).thenReturn(jda);
        return channel;
    }

    @Test
    void withNoRouterBuiltEverythingPassesThrough() {
        GuildMessageChannel real = channel(1, mock(JDA.class));

        assertSame(real, AutoPostRouter.route(real, "x"));
        assertFalse(AutoPostRouter.diverting());
        assertFalse(AutoPostRouter.skip("x"));
        assertFalse(AutoPostRouter.noPings());
        assertEquals("<@&5> @everyone", AutoPostRouter.defang("<@&5> @everyone"));
    }

    @Test
    void theLiveBotPostsWhereTheServerChose() {
        AutoPostRouter.install(() -> false, DEV);
        GuildMessageChannel real = channel(1, mock(JDA.class));

        assertSame(real, AutoPostRouter.route(real, "a rename alert"));
        assertFalse(AutoPostRouter.skip("a DM"));
        assertFalse(AutoPostRouter.noPings());
    }

    @Test
    void aBotThatIsNotLiveSendsAlertsToTheDevChannelAndPingsNobody() {
        AutoPostRouter.install(() -> true, DEV);
        JDA jda = mock(JDA.class);
        GuildMessageChannel dev = channel(DEV, jda);
        when(jda.getChannelById(GuildMessageChannel.class, DEV)).thenReturn(dev);
        GuildMessageChannel real = channel(1, jda);

        assertSame(dev, AutoPostRouter.route(real, "a rename alert"));
        assertTrue(AutoPostRouter.noPings());
        assertTrue(AutoPostRouter.skip("a DM to a player"));
    }

    @Test
    void withNoDevChannelTheAlertIsOnlyLogged() {
        AutoPostRouter.install(() -> true, null);

        assertNull(AutoPostRouter.route(channel(1, mock(JDA.class)), "a rename alert"));
    }

    @Test
    void aDevChannelTheBotCannotFindIsTreatedAsNone() {
        AutoPostRouter.install(() -> true, DEV);
        JDA jda = mock(JDA.class);

        assertNull(AutoPostRouter.route(channel(1, jda), "a rename alert"));
    }

    @Test
    void pingsInTextAreSwitchedOffWhileDivertedOnly() {
        AtomicBoolean diverted = new AtomicBoolean(true);
        AutoPostRouter.install(diverted::get, null);

        assertEquals("<123> <45> everyone here", AutoPostRouter.defang("<@&123> <@45> @everyone @here"));
        assertEquals("<9>", AutoPostRouter.defang("<@!9>"));

        diverted.set(false);
        assertEquals("<@&123> @everyone", AutoPostRouter.defang("<@&123> @everyone"));
    }

    @Test
    void theSwitchFollowsTheToggleLive() {
        AtomicBoolean diverted = new AtomicBoolean(true);
        AutoPostRouter.install(diverted::get, null);
        assertTrue(AutoPostRouter.diverting());

        diverted.set(false); // /dev toggleposting turned posting on for this run
        assertFalse(AutoPostRouter.diverting());
    }

    @Test
    void theDashboardBannerSaysWhatIsHappening() {
        AutoPostRouter.install(() -> true, null);
        var notice = com.younglings.bot.internal.OverviewAdminApi.devModeNotice();

        assertEquals("warning", notice.getString("severity"));
        assertTrue(notice.getString("body").contains("only written to the log"));
        AutoPostRouter.install(() -> true, DEV);
        assertTrue(com.younglings.bot.internal.OverviewAdminApi.devModeNotice().getString("body").contains("one test channel without pings"));
        assertTrue(com.younglings.bot.internal.OverviewAdminApi.devModeNotice().getString("body").contains("auto-clean, delayed autoroles")); // T-187
    }

    @Test
    void sweepsThatChangeTheRealServerRunOnlyOnTheLiveBot() {
        assertFalse(AutoPostRouter.skipSweep("auto-clean")); // no router built (a unit test): everything runs
        AtomicBoolean live = new AtomicBoolean(false);
        AutoPostRouter.install(() -> !live.get(), null);

        assertTrue(AutoPostRouter.skipSweep("auto-clean"));
        assertTrue(AutoPostRouter.skipSweep("auto-clean")); // logged once, still skipped
        live.set(true); // /dev toggleposting
        assertFalse(AutoPostRouter.skipSweep("auto-clean"));
        live.set(false);
        assertTrue(AutoPostRouter.skipSweep("auto-clean"));
    }
}
