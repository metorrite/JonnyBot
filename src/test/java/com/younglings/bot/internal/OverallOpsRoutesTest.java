package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The overall admin panel's read-only bot health and RuneMetrics budget: the two paths answer from the home server, anything else is not theirs. */
class OverallOpsRoutesTest {
    private final AdminOpsApi ops = mock(AdminOpsApi.class);
    private final RuneMetricsBudgetAdminApi budget = mock(RuneMetricsBudgetAdminApi.class);
    private final OverallOpsRoutes routes = new OverallOpsRoutes(ops, budget);
    private final Guild home = mock(Guild.class);

    @Test
    void healthAndBudgetAnswerFromTheHomeServer() {
        when(ops.health(home)).thenReturn(DataObject.empty().put("uptimeSeconds", 5));
        when(budget.overview(home)).thenReturn(DataObject.empty().put("day", "2026-10-25"));
        assertEquals(5, routes.route(home, "GET", new String[]{"health"}).getInt("uptimeSeconds"));
        assertEquals("2026-10-25", routes.route(home, "GET", new String[]{"runemetrics-budget"}).getString("day"));
    }

    @Test
    void onlyReadingIsAllowedAndOtherPathsAreNotThese() {
        assertEquals(405, assertThrows(ApiError.class, () -> routes.route(home, "POST", new String[]{"health"})).status);
        assertNull(routes.route(home, "GET", new String[]{"health", "more"}));
        assertNull(routes.route(home, "GET", new String[]{"admins"}));
    }
}
