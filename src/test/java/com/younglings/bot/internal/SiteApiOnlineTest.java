package com.younglings.bot.internal;

import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.WeeklyDigestRepository;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SiteApiOnlineTest {
    // Made-up ids, built at run time.
    private static final long GUILD = Long.parseLong("6".repeat(17) + "1");
    private static final long SHOWN = Long.parseLong("6".repeat(17) + "2");
    private static final long HIDDEN = Long.parseLong("6".repeat(17) + "3");

    @Test
    void theOnlineAnswerSaysUnavailableInsteadOfNobody() {
        var answer = SiteApi.onlineUnavailable();

        assertTrue(answer.getBoolean("unavailable"));
        assertEquals(0, answer.getInt("total"));
        assertEquals(0, answer.getArray("groups").length());
    }

    @Test
    void aMemberOnTheHiddenFromOnlineListIsCountedButNotListed() {
        SiteDiscordService discord = mock(SiteDiscordService.class);
        when(discord.hiddenFromOnline(GUILD)).thenReturn(Set.of(HIDDEN));
        SiteApi api = new SiteApi(mock(SiteStatsRepository.class), mock(PlayerLinkRepository.class), mock(ClanPointsRepository.class),
                mock(GuildSettingsService.class), mock(WeeklyDigestRepository.class), mock(MemberProfileRepository.class), mock(SiteNewsService.class),
                mock(SignupService.class), new SiteCache(), mock(RecapService.class), mock(CommunitySettings.class), mock(BossStatsService.class), discord);
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        List<Member> online = List.of(member(SHOWN, "Shown"), member(HIDDEN, "Hidden"));
        when(guild.getMembers()).thenReturn(online);

        DataObject answer = api.online(guild);

        assertEquals(2, answer.getInt("total"));
        List<String> listedIds = new ArrayList<>();
        List<String> listedNames = new ArrayList<>();
        DataArray groups = answer.getArray("groups");
        for (int g = 0; g < groups.length(); g++) {
            DataArray members = groups.getObject(g).getArray("members");
            assertEquals(members.length(), groups.getObject(g).getInt("count"));
            for (int i = 0; i < members.length(); i++) {
                listedIds.add(members.getObject(i).getString("id"));
                listedNames.add(members.getObject(i).getString("displayName"));
            }
        }
        assertEquals(List.of(Long.toString(SHOWN)), listedIds);
        assertEquals(List.of("Shown"), listedNames);
    }

    private static Member member(long id, String name) {
        Member member = mock(Member.class, RETURNS_DEEP_STUBS);
        when(member.getIdLong()).thenReturn(id);
        when(member.getId()).thenReturn(Long.toString(id));
        when(member.getUser().isBot()).thenReturn(false);
        when(member.getOnlineStatus()).thenReturn(OnlineStatus.ONLINE);
        when(member.getRoles()).thenReturn(List.of());
        when(member.getEffectiveName()).thenReturn(name);
        when(member.getEffectiveAvatarUrl()).thenReturn("https://example.invalid/" + name + ".png");
        when(member.getColors().getPrimaryRaw()).thenReturn(0);
        return member;
    }
}
