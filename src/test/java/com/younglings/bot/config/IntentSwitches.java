package com.younglings.bot.config;

import net.dv8tion.jda.api.requests.GatewayIntent;

import java.util.EnumSet;

/** Test helper: flips {@link PrivilegedIntents}' switches as if the portal had them on or off. Call {@link #allOn()} after a test that turned one off. */
public final class IntentSwitches {
    private IntentSwitches() {}

    private static final EnumSet<GatewayIntent> PRIVILEGED = EnumSet.of(GatewayIntent.GUILD_PRESENCES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS);

    public static void allOn() {
        PrivilegedIntents.filter(PrivilegedIntents.PRESENCE | PrivilegedIntents.MESSAGE_CONTENT | PrivilegedIntents.GUILD_MEMBERS, PRIVILEGED);
    }

    public static void set(boolean presence, boolean messageContent) {
        long flags = PrivilegedIntents.GUILD_MEMBERS;
        if (presence) flags |= PrivilegedIntents.PRESENCE;
        if (messageContent) flags |= PrivilegedIntents.MESSAGE_CONTENT;
        PrivilegedIntents.filter(flags, PRIVILEGED);
    }
}
