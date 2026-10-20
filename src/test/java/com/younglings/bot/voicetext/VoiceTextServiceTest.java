package com.younglings.bot.voicetext;

import com.younglings.bot.voicetext.VoiceTextRepository.Config;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.PermissionOverrideAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** JDA is mocked: nothing here touches Discord. */
class VoiceTextServiceTest {
    private static final long GUILD = 5L;
    private static final long VOICE = 10L;
    private static final long TEXT = 20L;
    private static final long USER = 30L;

    private VoiceTextRepository repository;
    private VoiceTextService service;
    private Guild guild;
    private TextChannel text;
    private VoiceChannel voice;
    private Member member;
    private PermissionOverrideAction grantAction;

    @BeforeEach
    void setUp() {
        repository = mock(VoiceTextRepository.class);
        service = new VoiceTextService(repository);
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        SelfMember self = mock(SelfMember.class);
        when(self.hasPermission(any(TextChannel.class), any(Permission[].class))).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(self);
        text = mock(TextChannel.class);
        when(text.getIdLong()).thenReturn(TEXT);
        when(text.getName()).thenReturn("vc-chat");
        when(text.canTalk()).thenReturn(false);
        when(guild.getTextChannelById(TEXT)).thenReturn(text);
        voice = mock(VoiceChannel.class);
        when(voice.getIdLong()).thenReturn(VOICE);
        when(voice.getName()).thenReturn("Raids");
        member = mock(Member.class);
        when(member.getIdLong()).thenReturn(USER);
        User user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        when(member.getUser()).thenReturn(user);
        grantAction = mock(PermissionOverrideAction.class, Mockito.RETURNS_SELF);
        when(text.upsertPermissionOverride(member)).thenReturn(grantAction);
        when(repository.textFor(GUILD, VOICE)).thenReturn(Optional.of(TEXT));
        when(repository.config(GUILD)).thenReturn(new Config(GUILD, true, false, false));
    }

    @Test
    void joiningAPairedVoiceChannelRecordsAndGrantsAccess() {
        service.joined(guild, member, voice);

        verify(repository).addGrant(GUILD, TEXT, USER);
        verify(grantAction).grant(VoiceTextService.ACCESS);
    }

    @Test
    void anOverwriteSomeoneSetByHandIsLeftAloneAndNeverTakenAwayLater() {
        when(text.getPermissionOverride(member)).thenReturn(mock(PermissionOverride.class));

        service.joined(guild, member, voice);

        verify(repository, never()).addGrant(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
        verify(text, never()).upsertPermissionOverride(any(Member.class));
    }

    @Test
    void nothingHappensWhileTheFeatureIsOffOrTheChannelIsNotPaired() {
        when(repository.config(GUILD)).thenReturn(Config.defaults(GUILD));
        service.joined(guild, member, voice);
        when(repository.config(GUILD)).thenReturn(new Config(GUILD, true, false, false));
        when(repository.textFor(GUILD, VOICE)).thenReturn(Optional.empty());
        service.joined(guild, member, voice);

        verify(repository, never()).addGrant(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    void leavingRemovesOnlyAccessJonnyBotGaveEvenIfTheFeatureWasSwitchedOffSince() {
        when(repository.config(GUILD)).thenReturn(Config.defaults(GUILD));
        when(repository.removeGrant(GUILD, TEXT, USER)).thenReturn(true);
        PermissionOverride override = mock(PermissionOverride.class);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class, Mockito.RETURNS_SELF);
        when(override.delete()).thenReturn(delete);
        when(text.getPermissionOverride(member)).thenReturn(override);

        service.left(guild, member, voice);

        verify(override).delete();
    }

    @Test
    void leavingWithoutAGrantLeavesTheirOverwriteAlone() {
        when(repository.removeGrant(GUILD, TEXT, USER)).thenReturn(false);
        PermissionOverride override = mock(PermissionOverride.class);
        when(text.getPermissionOverride(member)).thenReturn(override);

        service.left(guild, member, voice);

        verify(override, never()).delete();
    }

    @Test
    void theTextChannelIsOnlyClearedWhenThePurgeIsOnAndTheVoiceChannelIsEmpty() {
        when(repository.config(GUILD)).thenReturn(new Config(GUILD, true, false, true));
        Member other = mock(Member.class);
        User otherUser = mock(User.class);
        when(otherUser.isBot()).thenReturn(false);
        when(other.getUser()).thenReturn(otherUser);
        when(voice.getMembers()).thenReturn(List.of(other));

        service.left(guild, member, voice);

        verify(text, never()).getHistory();
    }

    @Test
    void releasingATextChannelRemovesOnlyTheAccessItGave() {
        when(repository.grantsIn(GUILD, TEXT)).thenReturn(List.of(USER));
        when(guild.getMemberById(USER)).thenReturn(member);
        PermissionOverride override = mock(PermissionOverride.class);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class, Mockito.RETURNS_SELF);
        when(override.delete()).thenReturn(delete);
        when(text.getPermissionOverride(member)).thenReturn(override);

        service.releaseAll(guild, TEXT);

        verify(repository).removeGrant(GUILD, TEXT, USER);
        verify(override).delete();
        verify(text, never()).getHistory();
    }
}
