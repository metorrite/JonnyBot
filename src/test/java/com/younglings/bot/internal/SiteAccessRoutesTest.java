package com.younglings.bot.internal;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.access.OverallAdmins;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoleRepository;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * N5 in a server the bot IS in: people the dashboard doesn't let in, and who may not be members at all, still reach the clan website's admin
 * and editor routes when the site lets them (an overall admin, someone named by user id), and nothing else. Made-up ids.
 */
class SiteAccessRoutesTest {
    private static final long BASE = Long.parseLong("9".repeat(17));
    private static final long GUILD = BASE + 1;
    private static final long OVERALL = BASE + 2;
    private static final long NAMED = BASE + 3;
    private static final long STRANGER = BASE + 4;

    private SiteAccessRoutes routes;
    private SiteRoleGuard guard;
    private Guild guild;
    private HttpExchange exchange;

    @BeforeEach
    void setUp() {
        SiteRoleRepository repository = mock(SiteRoleRepository.class);
        when(repository.get(GUILD)).thenReturn(SiteRoles.Settings.DEFAULT.withAdminAccess(new SiteRoles.AdminAccess(List.of(NAMED), List.of(), null, 0)));
        GuildSettingsService settings = mock(GuildSettingsService.class);
        when(settings.getEffective(anyLong())).thenReturn(mock(GuildSettings.class));
        OverallAdmins overall = mock(OverallAdmins.class);
        when(overall.isOverallAdmin(any(), eq(OVERALL))).thenReturn(true);
        guard = new SiteRoleGuard(repository, mock(DashboardAccess.class), settings, mock(PlayerLinkService.class), overall);
        routes = new SiteAccessRoutes(guard, mock(BotConfig.class));
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        exchange = mock(HttpExchange.class);
        when(exchange.getRequestHeaders()).thenReturn(new Headers());
    }

    private SiteCaller ask(long userId, String method, String... parts) {
        return routes.caller(exchange, guild, userId, null, method, parts);
    }

    @Test
    void anOverallAdminWhoIsNotInTheServerOwnsTheSite() {
        SiteCaller caller = ask(OVERALL, "GET", "clan-site", "roles");
        assertNotNull(caller);
        assertEquals(SiteRoles.Role.OWNER, caller.role(guard));
        assertNotNull(ask(OVERALL, "PUT", "clan-site", "roles", "admin-access"));
        assertNotNull(ask(OVERALL, "GET", "structure"));
        assertNotNull(ask(OVERALL, "GET", "audit"));
    }

    @Test
    void someoneNamedByUserIdIsAnAdminAndAStrangerIsNot() {
        assertNotNull(ask(NAMED, "GET", "clan-site", "layout"));
        assertNotNull(ask(NAMED, "GET", "structure"));
        assertNull(ask(STRANGER, "GET", "clan-site"));
        assertNull(ask(STRANGER, "GET", "structure"));
    }

    @Test
    void onlyTheSitesOwnPathsOpen() {
        assertNull(ask(OVERALL, "GET", "polls"), "the rest of the dashboard stays closed");
        assertNull(ask(OVERALL, "GET", "welcome"));
        assertNull(ask(OVERALL, "POST", "structure"), "structure and audit are read-only here");
        assertNull(ask(0, "GET", "clan-site"), "nobody signed in");
    }
}
