package com.younglings.bot.internal;

import com.younglings.bot.accountgate.AccountGateConfig;
import com.younglings.bot.accountgate.AccountGateConfig.Action;
import com.younglings.bot.accountgate.AccountGateRepository;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountGateAdminApiTest {
    private AccountGateRepository repository;
    private AccountGateAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        repository = mock(AccountGateRepository.class);
        api = new AccountGateAdminApi(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        self = mock(SelfMember.class);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        when(repository.get(5L)).thenReturn(AccountGateConfig.defaults(5L));
    }

    private AccountGateConfig saved() {
        ArgumentCaptor<AccountGateConfig> captor = ArgumentCaptor.forClass(AccountGateConfig.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private DataObject body(String json) {
        return DataObject.fromJson(json);
    }

    @Test
    void nameRulesAreOffByDefaultAndStayOffWhenNothingAboutThemIsSent() {
        assertFalse(AccountGateConfig.defaults(5L).nameRulesEnabled());
        assertEquals(Action.ALERT, AccountGateConfig.defaults(5L).nameAction());

        api.save(guild, actor, body("{\"enabled\": false, \"minAgeDays\": 7}"));

        assertFalse(saved().nameRulesEnabled());
    }

    @Test
    void rulesAreCleanedAndAnAlertOnlyRuleSetIsSaved() {
        when(repository.get(5L)).thenReturn(new AccountGateConfig(5L, false, 7, Action.ALERT, 9L));
        var channel = mock(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class);
        when(channel.canTalk()).thenReturn(true);
        when(guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, 9L)).thenReturn(channel);

        api.save(guild, actor, body("{\"nameRulesEnabled\": true, \"nameAction\": \"ALERT\", \"nameRules\": [\" Free*Nitro \", \"free*nitro\", \"*spam*\"], \"exemptUserIds\": [\"123456789012345678\"]}"));

        AccountGateConfig c = saved();
        assertTrue(c.nameRulesEnabled());
        assertEquals(List.of("free*nitro", "*spam*"), c.nameRules());
        assertEquals(Set.of(123456789012345678L), c.exemptUserIds());
        assertTrue(c.exempt(123456789012345678L));
    }

    @Test
    void aRuleThatWouldMatchEveryoneRefusesTheWholeSave() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("{\"nameRules\": [\"*\"]}")));

        assertEquals(400, error.status);
        verify(repository, never()).save(any());
    }

    @Test
    void banNeedsThePermissionAndNameRulesNeedAtLeastOneRule() {
        when(self.hasPermission(Permission.BAN_MEMBERS)).thenReturn(false);
        var noPermission = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, body("{\"nameRulesEnabled\": true, \"nameAction\": \"BAN\", \"nameRules\": [\"*spam*\"], \"alertChannelId\": null}")));
        assertTrue(noPermission.problems.stream().anyMatch(p -> p.contains("Ban Members")));

        var noRules = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("{\"nameRulesEnabled\": true, \"nameAction\": \"ALERT\"}")));
        assertTrue(noRules.problems.stream().anyMatch(p -> p.contains("at least one name rule")));
        verify(repository, never()).save(any());
    }

    @Test
    void banIsNotAnActionOfTheAgeCheck() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("{\"action\": \"BAN\"}")));

        assertTrue(error.problems.stream().anyMatch(p -> p.contains("Banning is only available for name rules")));
    }

    @Test
    void anExemptionThatIsNotAnIdIsRefused() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("{\"exemptUserIds\": [\"not an id\"]}")));

        assertEquals(400, error.status);
    }
}
