package com.runeclans.bot.internal;

import com.runeclans.bot.events.SkillWeekRepository;
import com.runeclans.bot.events.SkillWeekService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's Skill of the week (T-541): a rota of skills only, and no switching on without the hub. */
class SkillWeekAdminApiTest {
    private static final long GUILD = Long.parseLong("7".repeat(18));
    private static final long ADMIN = Long.parseLong("5".repeat(18));

    private final SkillWeekService service = Mockito.mock(SkillWeekService.class);
    private final SkillWeekRepository repository = Mockito.mock(SkillWeekRepository.class);
    private final SkillWeekAdminApi api = new SkillWeekAdminApi(service, repository);

    private static DataObject body(boolean enabled, Integer... rota) {
        return DataObject.empty().put("enabled", enabled).put("rota", DataArray.fromCollection(List.of(rota)));
    }

    @Test
    void savesTheRotaAndOpensThisWeekWhenSwitchedOn() {
        when(service.available()).thenReturn(true);
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.save(GUILD, ADMIN, body(true, 9, 15));
        verify(repository).save(GUILD, true, List.of(9, 15), com.runeclans.bot.events.SkillWeekRepository.Options.OFF, ADMIN);
        verify(service).open(eq(GUILD), any());
    }

    @Test
    void refusesBadSkillsAndSwitchingOnWithoutTheHub() {
        when(service.available()).thenReturn(false);
        TicketAdminApi.ApiError noHub = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(true, 9)));
        assertEquals(400, noHub.status);
        assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(false, 99)));
        verify(repository, never()).save(anyLong(), anyBoolean(), any(), any(), anyLong());
        // off needs no hub
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.save(GUILD, ADMIN, body(false));
        verify(repository).save(GUILD, false, List.of(), com.runeclans.bot.events.SkillWeekRepository.Options.OFF, ADMIN);
        verify(service, never()).open(anyLong(), any());
    }
    @Test
    void aVoteNeedsTwoSkillsWhenSwitchedOn() {
        when(service.available()).thenReturn(true);
        TicketAdminApi.ApiError one = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(true, 9).put("mode", "vote")));
        assertEquals(400, one.status);
        assertThrows(TicketAdminApi.ApiError.class, () -> api.save(GUILD, ADMIN, body(true, 9).put("mode", "pick")));
        verify(repository, never()).save(anyLong(), anyBoolean(), any(), any(), anyLong());
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.save(GUILD, ADMIN, body(true, 9, 15).put("mode", "vote"));
        verify(repository).save(GUILD, true, List.of(9, 15),
                new com.runeclans.bot.events.SkillWeekRepository.Options(com.runeclans.bot.events.SkillWeekRepository.Mode.VOTE, null, false), ADMIN);
    }
    @Test
    void theAnnouncementChannelMustBeOneTheBotCanPostIn() {
        net.dv8tion.jda.api.entities.Guild guild = Mockito.mock(net.dv8tion.jda.api.entities.Guild.class);
        net.dv8tion.jda.api.entities.Member actor = Mockito.mock(net.dv8tion.jda.api.entities.Member.class);
        net.dv8tion.jda.api.entities.SelfMember self = Mockito.mock(net.dv8tion.jda.api.entities.SelfMember.class);
        var channel = Mockito.mock(net.dv8tion.jda.api.entities.channel.concrete.TextChannel.class);
        long channelId = Long.parseLong("6".repeat(18));
        when(guild.getIdLong()).thenReturn(GUILD);
        when(actor.getIdLong()).thenReturn(ADMIN);
        when(guild.getSelfMember()).thenReturn(self);
        when(channel.getName()).thenReturn("announcements");
        when(guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId)).thenReturn(channel);
        DataObject sent = body(false, 9).put("announceChannelId", String.valueOf(channelId));

        when(self.hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND)).thenReturn(false);
        TicketAdminApi.ApiError cant = assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"skill-week"}, () -> sent));
        assertEquals(400, cant.status);
        assertThrows(TicketAdminApi.ApiError.class, () -> api.route(guild, actor, "PUT", new String[]{"skill-week"},
                () -> body(false, 9).put("announceChannelId", "1".repeat(18))), "not a channel of this server");
        verify(repository, never()).save(anyLong(), anyBoolean(), any(), any(), anyLong());

        when(self.hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_SEND)).thenReturn(true);
        when(service.view(eq(GUILD), Mockito.anyInt())).thenReturn(DataObject.empty());
        api.route(guild, actor, "PUT", new String[]{"skill-week"}, () -> sent);
        verify(repository).save(GUILD, false, List.of(9),
                new SkillWeekRepository.Options(SkillWeekRepository.Mode.ROTA, channelId, false), ADMIN);
    }
}
