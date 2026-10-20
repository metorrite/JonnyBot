package com.younglings.bot.internal;

import com.younglings.bot.automod.AutomodRepository;
import com.younglings.bot.automod.AutomodRules;
import com.younglings.bot.automod.PhishingList;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The per-rule custom response and log channel on the automod settings route. */
class AutomodAdminApiTest {
    private AutomodRepository repository;
    private AutomodAdminApi api;
    private Guild guild;
    private Member actor;
    private GuildMessageChannel logChannel;

    @BeforeEach
    void setUp() {
        repository = mock(AutomodRepository.class);
        when(repository.get(5L)).thenReturn(AutomodRepository.Config.defaults(5L));
        PhishingList phishing = mock(PhishingList.class);
        when(phishing.size()).thenReturn(1234);
        api = new AutomodAdminApi(repository, phishing);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(5L);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(Permission.MESSAGE_MANAGE)).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        actor = mock(Member.class);
        when(actor.getId()).thenReturn("7");
        logChannel = mock(GuildMessageChannel.class);
        when(logChannel.getIdLong()).thenReturn(42L);
        when(logChannel.canTalk()).thenReturn(true);
        when(guild.getChannelById(GuildMessageChannel.class, 42L)).thenReturn(logChannel);
    }

    private static DataObject body(String rule, String response, String logChannelId) {
        DataObject item = DataObject.empty().put("key", rule).put("enabled", true).put("warn", false).put("limit", 4).put("seconds", 0).put("words", DataArray.empty())
                .put("response", response).put("logChannelId", logChannelId);
        return DataObject.empty().put("enabled", true).put("rules", DataArray.empty().add(item));
    }

    @Test
    void aResponseAndALogChannelAreSavedWithTheRule() {
        api.save(guild, actor, body("ZALGO", "{user}, please no", "42"));

        ArgumentCaptor<AutomodRepository.Config> saved = ArgumentCaptor.forClass(AutomodRepository.Config.class);
        verify(repository).save(saved.capture());
        var zalgo = saved.getValue().rules().get(AutomodRules.Rule.ZALGO);
        assertEquals("{user}, please no", zalgo.response());
        assertEquals(42L, zalgo.logChannelId());
    }

    @Test
    void aLogChannelThatIsNotInTheServerOrCannotBePostedInIsRefused() {
        var missing = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("ZALGO", "", "99")));
        assertTrue(missing.problems.get(0).contains("log channel"));
        when(logChannel.canTalk()).thenReturn(false);
        var silent = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("ZALGO", "", "42")));
        assertTrue(silent.problems.get(0).contains("can't post"));
        verify(repository, never()).save(any());
    }

    @Test
    void aTooLongResponseIsRefused() {
        var error = assertThrows(TicketAdminApi.ApiError.class, () -> api.save(guild, actor, body("ZALGO", "x".repeat(400), "")));
        assertTrue(error.problems.get(0).contains("at most"));
    }

    @Test
    void theSettingsListEveryRuleWithItsResponseAndTheScamListSize() {
        DataObject got = api.get(guild);
        assertEquals(AutomodRules.Rule.values().length, got.getArray("rules").length());
        assertEquals(1234, got.getInt("phishingListSize"));
        assertEquals(AutomodRules.RuleConfig.MAX_RESPONSE, got.getInt("maxResponse"));
    }
}
