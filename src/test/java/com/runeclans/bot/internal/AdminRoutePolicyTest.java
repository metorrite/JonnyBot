package com.runeclans.bot.internal;

import com.runeclans.bot.internal.AdminRoutePolicy.Mode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which dashboard routes are cached or time-limited. */
class AdminRoutePolicyTest {
    private static Mode mode(String method, String path) {
        return AdminRoutePolicy.modeOf(method, path.isEmpty() ? new String[0] : path.split("/"));
    }

    @Test
    void slowPagesAreServedFromTheCache() {
        for (String path : new String[] {"structure", "points", "points/redemptions", "chat-levels", "chat-levels/top", "citadel/winners",
                "citadel/wheel", "branding", "tempvoice", "appeals", "autopurge", "events/12/space"}) {
            assertEquals(Mode.CACHED, mode("GET", path), path);
        }
    }

    @Test
    void writesToDiscordHaveATimeLimit() {
        assertEquals(Mode.BOUNDED, mode("POST", "welcome/test"));
        assertEquals(Mode.BOUNDED, mode("POST", "ban-announcement/test"));
        assertEquals(Mode.BOUNDED, mode("PUT", "branding"));
        assertEquals(Mode.BOUNDED, mode("POST", "tempvoice/hub"));
        assertEquals(Mode.BOUNDED, mode("POST", "appeals/5/approve"));
        assertEquals(Mode.BOUNDED, mode("POST", "autopurge/preview"));
    }

    @Test
    void everythingElseIsUntouched() {
        assertEquals(Mode.PLAIN, mode("GET", ""));
        assertEquals(Mode.PLAIN, mode("GET", "welcome"));
        assertEquals(Mode.PLAIN, mode("PUT", "welcome"));
        assertEquals(Mode.PLAIN, mode("PUT", "chat-levels"));
        assertEquals(Mode.PLAIN, mode("POST", "points/award"));
        assertEquals(Mode.PLAIN, mode("GET", "events/12/bans"));
        assertEquals(Mode.PLAIN, mode("PUT", "autopurge"));
    }
}
