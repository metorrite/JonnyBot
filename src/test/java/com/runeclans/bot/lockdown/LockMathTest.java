package com.runeclans.bot.lockdown;

import com.runeclans.bot.lockdown.LockMath.Snapshot;
import net.dv8tion.jda.api.Permission;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockMathTest {
    private static final long VIEW = Permission.getRaw(Permission.VIEW_CHANNEL);
    private static final long SEND = Permission.getRaw(Permission.MESSAGE_SEND);
    private static final long HISTORY = Permission.getRaw(Permission.MESSAGE_HISTORY);

    @Test
    void lockingDeniesSpeakingToEveryoneAndTouchesNothingElse() {
        long[] locked = LockMath.lockedEveryone(new Snapshot(1, true, VIEW | SEND, HISTORY));

        assertEquals(VIEW, locked[0] & (VIEW | SEND), "view stays allowed, send is no longer allowed");
        assertTrue((locked[1] & SEND) != 0, "send is denied");
        assertTrue((locked[1] & HISTORY) != 0, "an existing denial is kept");
        assertEquals(0, locked[0] & LockMath.SPEAK_RAW);
    }

    @Test
    void lockingAChannelWithNoOverwriteStillDeniesSpeaking() {
        long[] locked = LockMath.lockedEveryone(new Snapshot(1, false, 0, 0));

        assertEquals(0, locked[0]);
        assertEquals(LockMath.SPEAK_RAW, locked[1]);
    }

    @Test
    void rolesThatKeepTalkingAreAllowedAndNotDeniedSending() {
        long[] role = LockMath.keepTalking(new Snapshot(2, true, VIEW, SEND | HISTORY));

        assertTrue((role[0] & SEND) != 0);
        assertTrue((role[0] & VIEW) != 0);
        assertEquals(0, role[1] & SEND);
        assertTrue((role[1] & HISTORY) != 0, "unrelated denials stay");
    }

    @Test
    void snapshotsSurviveBeingStored() {
        List<Snapshot> before = List.of(new Snapshot(10, true, 123456789012L, 99), new Snapshot(20, false, 0, 0));

        assertEquals(before, LockMath.decode(LockMath.encode(before)));
        assertTrue(LockMath.decode(null).isEmpty());
        assertTrue(LockMath.decode("junk;1:2").isEmpty());
    }
}
