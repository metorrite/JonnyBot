package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.ClanSiteLayoutRepository;
import com.runeclans.bot.plugin.RuneSyncRepository;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T-586: who the "In game now" answer names, for a site with the bot and one without. */
class SiteInGameNowTest {
    private static final long GUILD = Long.parseLong("9".repeat(17) + "6");
    private static final long QUEEN = Long.parseLong("1".repeat(18));
    private static final long GUEST = Long.parseLong("2".repeat(18));
    private static final long DISCORD_ONLY = Long.parseLong("3".repeat(18));
    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");
    private static final String PLACED = """
            {"pages":[{"id":"home","sections":[{"rows":[{"cells":[{"widgets":[{"id":"w1","type":"in-game-now","settings":{}}]}]}]}]}]}""";

    private final RuneSyncRepository runeSync = mock(RuneSyncRepository.class);
    private final PlayerLinkRepository links = mock(PlayerLinkRepository.class);
    private final SiteStatsRepository stats = mock(SiteStatsRepository.class);
    private final ClanSiteLayoutRepository layouts = mock(ClanSiteLayoutRepository.class);

    private SiteInGameNow service(String published) {
        when(layouts.byGuild(GUILD)).thenReturn(Optional.of(new ClanSiteLayoutRepository.Layouts(GUILD, null, 0, published, 1, null, NOW, NOW)));
        when(stats.members(GUILD)).thenReturn(List.of(row("Skill Queen")));
        when(links.getLinksForUser(anyLong(), anyLong())).thenReturn(List.of());
        when(links.getAccountsForUser(anyLong())).thenReturn(List.of());
        when(links.getAccountsForUser(QUEEN)).thenReturn(List.of(account("skill_queen", QUEEN)));
        when(links.getAccountsForUser(GUEST)).thenReturn(List.of(account("Not In Clan", GUEST)));
        when(runeSync.presentSince(any())).thenReturn(List.of(
                new RuneSyncRepository.Present(QUEEN, NOW.minusSeconds(60), null),
                new RuneSyncRepository.Present(GUEST, NOW.minusSeconds(30), "Not In Clan"),
                new RuneSyncRepository.Present(DISCORD_ONLY, NOW.minusSeconds(90), null)));
        return new SiteInGameNow(runeSync, links, stats, layouts).withClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SiteStatsRepository.MemberRow row(String rsn) {
        return new SiteStatsRepository.MemberRow(rsn, "Recruit", 0, 0, 0, null, null, 0, false, true, null, null, null);
    }

    private static PlayerLinkRepository.PlayerAccount account(String rsn, long user) {
        return new PlayerLinkRepository.PlayerAccount(1, rsn, user, "RSLINK", null, null);
    }

    private static List<String> names(DataObject answer) {
        DataArray members = answer.getArray("members");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < members.length(); i++) out.add(members.getObject(i).getString("rsn"));
        return out;
    }

    @Test
    void withoutTheWidgetNobodyIsNamedAndNothingIsRead() {
        DataObject a = service("{\"pages\":[]}").answer(GUILD, null);
        assertFalse(a.getBoolean("placed"));
        assertTrue(names(a).isEmpty());
        verify(runeSync, never()).presentSince(any());
    }

    @Test
    void aSiteWithoutTheBotMatchesTheRoster() {
        DataObject a = service(PLACED).answer(GUILD, null);
        assertTrue(a.getBoolean("placed"));
        assertEquals("members", a.getString("visibility"));
        assertEquals(List.of("Skill Queen"), names(a), "the roster's spelling; the guest and the unlinked member are left out");
        verify(runeSync).presentSince(NOW.minus(InGameNowRules.WINDOW));
    }

    @Test
    void aServerMemberWithoutARuneScapeNameIsOnlyCounted() {
        SiteInGameNow s = service(PLACED);
        Guild guild = mock(Guild.class);
        when(guild.getMemberById(DISCORD_ONLY)).thenReturn(mock(Member.class));
        DataObject a = s.answer(GUILD, guild);
        assertEquals(List.of("Skill Queen"), names(a), "never a Discord name");
        assertEquals(1, a.getInt("unnamed"));
        assertEquals(2, a.getInt("count"));
    }

    @Test
    void theCharacterPlayingNamesTheMember() {
        when(runeSync.presentSince(any())).thenReturn(List.of());
        SiteInGameNow s = service(PLACED);
        when(runeSync.presentSince(any())).thenReturn(List.of(new RuneSyncRepository.Present(QUEEN, NOW, "Skill_Queen")));
        when(links.getAccountsForUser(QUEEN)).thenReturn(List.of());
        assertEquals(List.of("Skill Queen"), names(s.answer(GUILD, null)), "the RuneLink name from the check-in, matched to the roster");
    }

    @Test
    void aServerLinkNamesTheMember() {
        SiteInGameNow s = service(PLACED);
        Guild guild = mock(Guild.class);
        Member member = mock(Member.class);
        when(guild.getMemberById(DISCORD_ONLY)).thenReturn(member);
        when(links.getLinksForUser(GUILD, DISCORD_ONLY)).thenReturn(List.of(new PlayerLink(1, GUILD, DISCORD_ONLY, "Pal Rsn", "ADMIN_MANUAL", null, null)));
        assertEquals(List.of("Pal Rsn", "Skill Queen"), names(s.answer(GUILD, guild)));
    }

    @Test
    void anAnswerIsKeptHalfAMinute() {
        SiteInGameNow s = service(PLACED);
        s.answer(GUILD, null);
        s.answer(GUILD, null);
        verify(runeSync, org.mockito.Mockito.times(1)).presentSince(any());
    }

    @Test
    void aDatabaseFailureSaysUnavailable() {
        SiteInGameNow s = service(PLACED);
        when(runeSync.presentSince(any())).thenThrow(new RuntimeException("down"));
        DataObject a = s.answer(GUILD, null);
        assertFalse(a.getBoolean("available"));
        assertTrue(names(a).isEmpty());
    }
}
