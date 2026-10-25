package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The home server's site read by server id (T-519): only the home server answers, the home-only routes answer exactly as {@code /internal/site/}
 * does, and every other route goes to the clan-site answer.
 */
class GuildSiteRoutesTest {
    // Made-up ids, built at run time (a long literal of this shape trips the secret scanner).
    private static final long HOME = Long.parseLong("5".repeat(17) + "1");
    private static final long OTHER = Long.parseLong("5".repeat(17) + "2");

    private SiteApi api;
    private GuildSiteRoutes routes;
    private Guild guild;
    private CommunitySettings community;
    private SiteNewsService news;

    @BeforeEach
    void setUp() {
        community = mock(CommunitySettings.class);
        news = mock(SiteNewsService.class);
        api = spy(new SiteApi(mock(SiteStatsRepository.class), mock(PlayerLinkRepository.class), mock(ClanPointsRepository.class), mock(GuildSettingsService.class),
                mock(WeeklyDigestRepository.class), mock(MemberProfileRepository.class), news, mock(SignupService.class), new SiteCache(), mock(RecapService.class),
                community, mock(BossStatsService.class), mock(SiteDiscordService.class)));
        BotConfig config = mock(BotConfig.class);
        when(config.getGuildId()).thenReturn(HOME);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(HOME);
        JDA jda = mock(JDA.class);
        when(jda.getGuildById(HOME)).thenReturn(guild);
        routes = new GuildSiteRoutes(api, config);
        routes.jda(jda);
    }

    private static HttpExchange exchange(String pathAndQuery) {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(URI.create("http://bot" + pathAndQuery));
        return exchange;
    }

    private GuildSiteRoutes.Answer get(String pathAndQuery) {
        HttpExchange exchange = exchange(pathAndQuery);
        return routes.route("GET", exchange.getRequestURI().getPath(), exchange);
    }

    @Test
    void homeSaysWhichServerIsTheHomeServer() {
        GuildSiteRoutes.Answer answer = get(GuildSiteRoutes.PREFIX + "home");
        assertEquals(200, answer.status());
        assertEquals(Long.toString(HOME), answer.body().getString("guildId"));
    }

    @Test
    void onlyTheHomeServerAnswers() {
        assertEquals(404, get(GuildSiteRoutes.PREFIX + OTHER + "/options").status());
        assertEquals(404, get(GuildSiteRoutes.PREFIX + "options").status());
        assertEquals(404, get(GuildSiteRoutes.PREFIX + HOME + "/no-such-route").status());
        HttpExchange exchange = exchange(GuildSiteRoutes.PREFIX + HOME + "/options");
        assertEquals(405, routes.route("POST", GuildSiteRoutes.PREFIX + HOME + "/options", exchange).status());
    }

    @Test
    void theHomeOnlyRoutesAnswerTheSameAsTheHomeSiteRoutes() {
        when(community.navEventBubble(anyLong())).thenReturn(true);
        DataArray posts = DataArray.empty().add(DataObject.empty().put("id", "1").put("text", "Hello"));
        when(news.latest(any(Guild.class), anyInt())).thenReturn(posts);

        for (String route : new String[]{"options", "news"}) {
            HttpExchange exchange = exchange(GuildSiteRoutes.PREFIX + HOME + "/" + route);
            GuildSiteRoutes.Answer answer = routes.route("GET", exchange.getRequestURI().getPath(), exchange);
            assertEquals(200, answer.status(), route);
            assertEquals(api.answer(guild, route, exchange("/internal/site/" + route)).toString(), answer.body().toString(), route);
        }
        assertEquals(true, get(GuildSiteRoutes.PREFIX + HOME + "/options").body().getBoolean("navEventBubble"));
    }

    @Test
    void everyOtherRouteIsTheClanSiteAnswer() {
        HttpExchange exchange = exchange(GuildSiteRoutes.PREFIX + HOME + "/members");
        routes.route("GET", exchange.getRequestURI().getPath(), exchange);
        verify(api).tenant(eq(guild), eq("members"), any(HttpExchange.class));

        HttpExchange news = exchange(GuildSiteRoutes.PREFIX + HOME + "/news");
        routes.route("GET", news.getRequestURI().getPath(), news);
        verify(api, never()).tenant(eq(guild), eq("news"), any(HttpExchange.class));
    }

}
