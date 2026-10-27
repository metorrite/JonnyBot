package com.younglings.bot.internal;

import com.younglings.bot.plugin.PluginPointsRepository;
import com.younglings.bot.plugin.PluginPointsRules;
import com.younglings.bot.plugin.PluginPointsRules.XpRule;
import com.younglings.bot.plugin.PluginXpPoints;
import com.younglings.bot.points.PointsRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Clan plugin page's "Points for XP gained" route (T-542). */
class PluginXpPointsAdminApiTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));

    private final PluginPointsRepository repository = mock(PluginPointsRepository.class);
    private final PointsRepository points = mock(PointsRepository.class);
    private final PluginXpPoints xpPoints = mock(PluginXpPoints.class);
    private final PluginXpPointsAdminApi api = new PluginXpPointsAdminApi(repository, points, xpPoints);
    private final Guild guild = mock(Guild.class);
    private final Member actor = mock(Member.class);

    PluginXpPointsAdminApiTest() {
        when(guild.getIdLong()).thenReturn(GUILD);
        when(actor.getIdLong()).thenReturn(Long.parseLong("1".repeat(18)));
        when(points.settings(GUILD)).thenReturn(new PointsRepository.Settings(GUILD, true, "points", null));
    }

    private static int refusal(String json) {
        return assertThrows(TicketAdminApi.ApiError.class, () -> PluginXpPointsAdminApi.parse(DataObject.fromJson(json))).status;
    }

    @Test
    void aClanThatNeverSavedOneSeesItOff() {
        when(repository.xpRule(GUILD)).thenReturn(XpRule.OFF);
        when(xpPoints.offered()).thenReturn(true);
        DataObject view = api.route(guild, actor, "GET", new String[]{"plugin-xp-points"}, DataObject::empty);
        assertFalse(view.getBoolean("enabled"));
        assertEquals(0, view.getInt("points"));
        assertEquals(PluginPointsRules.DEFAULT_PER_XP, view.getLong("perXp"));
        assertNull(api.route(guild, actor, "GET", new String[]{"plugin-points"}, DataObject::empty), "only its own path");
    }

    @Test
    void savesTheRuleAndRefusesSwitchingItOnWhileNotOffered() {
        when(repository.xpRule(GUILD)).thenReturn(XpRule.OFF);
        when(xpPoints.offered()).thenReturn(false);
        String on = "{\"enabled\":true,\"points\":10,\"perXp\":100000,\"dailyCap\":50}";
        assertEquals(409, assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"plugin-xp-points"}, () -> DataObject.fromJson(on))).status);
        verify(repository, never()).saveXpRule(anyLong(), any(), anyLong(), any());

        when(xpPoints.offered()).thenReturn(true);
        api.route(guild, actor, "PUT", new String[]{"plugin-xp-points"}, () -> DataObject.fromJson(on));
        verify(repository).saveXpRule(org.mockito.ArgumentMatchers.eq(GUILD), org.mockito.ArgumentMatchers.eq(new XpRule(true, 10, 100_000, 50)), anyLong(), any());
    }

    @Test
    void malformedRulesAreRefusedWith400() {
        assertEquals(400, refusal("{}"));
        assertEquals(400, refusal("{\"enabled\":\"yes\"}"));
        assertEquals(400, refusal("{\"enabled\":true,\"points\":\"lots\",\"perXp\":100000}"));
        assertEquals(400, refusal("{\"enabled\":true,\"points\":0,\"perXp\":100000}"));
        assertEquals(400, refusal("{\"enabled\":true,\"points\":5,\"perXp\":10}"));
        assertEquals(400, refusal("{\"enabled\":false,\"points\":5,\"perXp\":100000,\"dailyCap\":-1}"));
        assertEquals(new XpRule(false, 5, 100_000, 0), PluginXpPointsAdminApi.parse(DataObject.fromJson("{\"enabled\":false,\"points\":5,\"perXp\":100000}")));
    }
}
