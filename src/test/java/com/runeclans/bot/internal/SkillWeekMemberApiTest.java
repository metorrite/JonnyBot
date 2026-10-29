package com.runeclans.bot.internal;

import com.runeclans.bot.discord.DiscordLookups;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.SkillWeekService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The member's skill of the week routes (T-711): only members of the server vote or choose, on the home site as on a clan site. */
class SkillWeekMemberApiTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long MEMBER = Long.parseLong("1".repeat(18));
    private static final long STRANGER = Long.parseLong("2".repeat(18));

    private final SkillWeekService weeks = Mockito.mock(SkillWeekService.class);
    private final SkillWeekMemberApi api = new SkillWeekMemberApi(weeks);
    private final Guild guild = Mockito.mock(Guild.class);

    SkillWeekMemberApiTest() {
        when(guild.getIdLong()).thenReturn(GUILD);
        Member member = Mockito.mock(Member.class);
        when(guild.getMemberById(MEMBER)).thenReturn(member);
        when(weeks.castVote(GUILD, MEMBER, 9)).thenReturn(DataObject.empty().put("open", true));
        when(weeks.setOptOut(GUILD, MEMBER, true)).thenReturn(DataObject.empty().put("optedOut", true));
    }

    @Test
    void membersVoteAndChoose() {
        assertEquals(true, api.vote(guild, MEMBER, DataObject.empty().put("skillId", 9)).getBoolean("open"));
        assertEquals(true, api.setAuto(guild, MEMBER, DataObject.empty().put("optedOut", true)).getBoolean("optedOut"));
        assertEquals(400, assertThrows(EventProblem.class, () -> api.setAuto(guild, MEMBER, DataObject.empty())).status());
        assertEquals(400, assertThrows(EventProblem.class, () -> api.vote(guild, MEMBER, DataObject.empty().put("skillId", "nine"))).status());
    }

    @Test
    void someoneOutsideTheServerIsRefused() {
        // Discord says they aren't a member (the lookup's Unknown Member answer is null here)
        try (var lookups = Mockito.mockStatic(DiscordLookups.class)) {
            lookups.when(() -> DiscordLookups.memberOrSlow(guild, STRANGER)).thenReturn(null);
            assertEquals(403, assertThrows(EventProblem.class, () -> api.vote(guild, STRANGER, DataObject.empty().put("skillId", 9))).status());
            assertEquals(403, assertThrows(EventProblem.class, () -> api.get(guild, STRANGER)).status());
            assertEquals(403, assertThrows(EventProblem.class, () -> api.auto(guild, STRANGER)).status());
            assertEquals(403, assertThrows(EventProblem.class, () -> api.setAuto(guild, STRANGER, DataObject.empty().put("optedOut", true))).status());
        }
        verify(weeks, never()).castVote(anyLong(), anyLong(), anyInt());
        verify(weeks, never()).setOptOut(anyLong(), anyLong(), anyBoolean());
    }
}
