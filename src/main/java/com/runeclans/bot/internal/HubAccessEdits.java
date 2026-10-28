package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;

/**
 * With {@code RUNECLANS_HUB_ACCESS=on} (and the hub client on) the access gates are read from the RuneClans hub ({@link
 * com.runeclans.bot.access.GateSource}), so they are edited there too: on the website's Beta access page. The bot's own editors for the
 * beta servers, the access list, the beta testers and the site switches then refuse every change, so nothing is saved to tables nobody
 * reads. Reading them still works. Off, they work as before.
 */
final class HubAccessEdits {
    static final String MOVED = "The beta lists and site switches are kept in the RuneClans hub now. Change them on runeclans.com: Admin, Beta access.";

    private HubAccessEdits() {}

    /** Throws a 409 with {@link #MOVED} when the gates are edited in the hub. */
    static void refuseWhenInHub(boolean inHub) {
        if (inHub) throw new ApiError(409, MOVED);
    }
}
