package com.runeclans.bot.permission;

import com.runeclans.bot.config.BotConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The bot owner's "lift restrictions" mode (T-445): one server, one person, and it ends on its own. Made-up ids. */
class OwnerOverrideTest {
    private static final long BASE = Long.parseLong("8".repeat(17));
    private static final long GUILD = BASE + 1;
    private static final long OTHER_GUILD = BASE + 2;
    private static final long OWNER = BASE + 3;
    private static final long SOMEONE = BASE + 4;

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private OwnerOverride override() {
        OwnerOverride o = new OwnerOverride();
        o.clock = now::get;
        return o;
    }

    @Test
    void liftingLastsHalfAnHourForThatServerAndPersonOnly() {
        OwnerOverride o = override();
        assertFalse(o.active(GUILD, OWNER));
        long until = o.lift(GUILD, OWNER);
        assertEquals(now.get() + OwnerOverride.DURATION_MS, until);
        assertTrue(o.active(GUILD, OWNER));
        assertFalse(o.active(OTHER_GUILD, OWNER), "only the server it was lifted for");
        assertFalse(o.active(GUILD, SOMEONE), "only the person who lifted it");
        assertFalse(o.active(GUILD, 0), "nobody signed in never matches");

        now.addAndGet(OwnerOverride.DURATION_MS - 1);
        assertTrue(o.active(GUILD, OWNER), "still running a moment before the end");
        now.addAndGet(1);
        assertFalse(o.active(GUILD, OWNER), "ends on its own");
        assertEquals(0, o.until(GUILD, OWNER));
    }

    @Test
    void endingStopsItAtOnceAndLiftingAgainRenews() {
        OwnerOverride o = override();
        o.lift(GUILD, OWNER);
        now.addAndGet(20 * 60_000L);
        long renewed = o.lift(GUILD, OWNER);
        assertEquals(now.get() + OwnerOverride.DURATION_MS, renewed, "a second acknowledgement starts a fresh half hour");
        assertTrue(o.end(GUILD, OWNER));
        assertFalse(o.active(GUILD, OWNER));
        assertFalse(o.end(GUILD, OWNER), "nothing left to end");

        o.lift(GUILD, OWNER);
        now.addAndGet(OwnerOverride.DURATION_MS);
        assertFalse(o.end(GUILD, OWNER), "an override that already ran out doesn't count as ended now");
    }

    @Test
    void theDashboardTreatsTheOwnerAsAdminOnlyWhileItRuns() {
        AdminRoleFilter filter = mock(AdminRoleFilter.class);
        PermissionGroupService groups = mock(PermissionGroupService.class);
        BotConfig config = mock(BotConfig.class);
        when(config.getGuildId()).thenReturn(BASE);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        Member owner = mock(Member.class);
        when(owner.getIdLong()).thenReturn(OWNER);
        OwnerOverride o = override();
        DashboardAccess access = new DashboardAccess(filter, groups, config, o);

        assertEquals(DashboardAccess.Tier.NONE, access.tierOf(guild, owner), "restrictions in place: no dashboard");
        o.lift(GUILD, OWNER);
        assertEquals(DashboardAccess.Tier.ADMIN, access.tierOf(guild, owner));
        assertFalse(access.isEventCoordinator(guild, owner));
        now.addAndGet(OwnerOverride.DURATION_MS);
        assertEquals(DashboardAccess.Tier.NONE, access.tierOf(guild, owner), "back to no dashboard when it runs out");
    }
}
