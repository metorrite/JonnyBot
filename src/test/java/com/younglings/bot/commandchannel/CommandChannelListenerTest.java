package com.younglings.bot.commandchannel;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The listener's decisions: whom it leaves alone, when it deletes, what the notice says, and that one burst doesn't become a wall of notices. */
class CommandChannelListenerTest {
    private static final long GUILD_ID = 1L, CHANNEL_ID = 10L, USER_ID = 5L;

    private CommandChannelService service;
    private CommandChannelListener listener;
    private CommandChannelGroup group;

    private MessageReceivedEvent event;
    private Message message;
    private User author;
    private Member member;
    private Guild guild;
    private SelfMember self;
    private GuildMessageChannelUnion channel;
    private AuditableRestAction<Void> deleteAction;
    private MessageCreateAction noticeAction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = mock(CommandChannelService.class);
        listener = new CommandChannelListener(service);
        group = new CommandChannelGroup(7, GUILD_ID, "Custom", "Please use a slash command.", true, null, null,
                Set.of(CHANNEL_ID), Set.of(), Set.of());

        event = mock(MessageReceivedEvent.class);
        message = mock(Message.class);
        author = mock(User.class);
        member = mock(Member.class);
        guild = mock(Guild.class);
        self = mock(SelfMember.class);
        channel = mock(GuildMessageChannelUnion.class);
        deleteAction = mock(AuditableRestAction.class);
        noticeAction = mock(MessageCreateAction.class);

        when(event.isFromGuild()).thenReturn(true);
        when(event.isWebhookMessage()).thenReturn(false);
        when(event.getAuthor()).thenReturn(author);
        when(author.isBot()).thenReturn(false);
        when(event.getMessage()).thenReturn(message);
        when(message.getType()).thenReturn(MessageType.DEFAULT);
        when(message.delete()).thenReturn(deleteAction);
        when(event.getMember()).thenReturn(member);
        when(member.getIdLong()).thenReturn(USER_ID);
        when(member.getAsMention()).thenReturn("<@5>");
        when(event.getGuild()).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD_ID);
        when(guild.getSelfMember()).thenReturn(self);
        when(self.hasPermission(any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class), any(Permission[].class))).thenReturn(true);
        MessageChannelUnion plainChannel = mock(MessageChannelUnion.class);
        when(plainChannel.getIdLong()).thenReturn(CHANNEL_ID);
        when(event.getChannel()).thenReturn(plainChannel);
        when(event.getGuildChannel()).thenReturn(channel);
        when(channel.getIdLong()).thenReturn(CHANNEL_ID);
        when(channel.canTalk()).thenReturn(true);
        when(channel.sendMessage(any(CharSequence.class))).thenReturn(noticeAction);
        when(noticeAction.setAllowedMentions(any())).thenReturn(noticeAction);

        when(service.findGroup(GUILD_ID, CHANNEL_ID)).thenReturn(group);
        when(service.appliesTo(group, member, guild)).thenReturn(true);
    }

    private void fire() {
        listener.onMessageReceived(event);
    }

    @SuppressWarnings("unchecked")
    private void deliverDeleteSuccess() {
        ArgumentCaptor<Consumer<Void>> success = ArgumentCaptor.forClass(Consumer.class);
        verify(deleteAction).queue(success.capture(), any());
        success.getValue().accept(null);
    }

    @Test
    void deletesACoveredMembersMessageThenPostsTheNoticeMentioningOnlyThem() {
        fire();
        verify(message).delete();
        verify(channel, never()).sendMessage(any(CharSequence.class)); // not until the delete has actually succeeded

        deliverDeleteSuccess();

        ArgumentCaptor<CharSequence> text = ArgumentCaptor.forClass(CharSequence.class);
        verify(channel).sendMessage(text.capture());
        assertEquals("<@5> Please use a slash command.", text.getValue().toString());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Message.MentionType>> mentions = ArgumentCaptor.forClass(Collection.class);
        verify(noticeAction).setAllowedMentions(mentions.capture());
        assertEquals(Set.of(Message.MentionType.USER), Set.copyOf(mentions.getValue()),
                "an admin-written notice containing @everyone or a role must never actually ping");
    }

    @Test
    void theBuiltInDefaultIsUsedWhenTheGroupHasNoCustomMessage() {
        group = new CommandChannelGroup(7, GUILD_ID, "Default", null, true, null, null, Set.of(CHANNEL_ID), Set.of(), Set.of());
        when(service.findGroup(GUILD_ID, CHANNEL_ID)).thenReturn(group);
        when(service.appliesTo(group, member, guild)).thenReturn(true);

        fire();
        deliverDeleteSuccess();

        ArgumentCaptor<CharSequence> text = ArgumentCaptor.forClass(CharSequence.class);
        verify(channel).sendMessage(text.capture());
        assertEquals("<@5> " + CommandChannelService.DEFAULT_MESSAGE, text.getValue().toString());
    }

    @Test
    void neverTouchesBotsWebhooksOrSystemMessages() {
        when(author.isBot()).thenReturn(true);
        fire();
        when(author.isBot()).thenReturn(false);

        when(event.isWebhookMessage()).thenReturn(true);
        fire();
        when(event.isWebhookMessage()).thenReturn(false);

        when(message.getType()).thenReturn(MessageType.CHANNEL_PINNED_ADD);
        fire();

        verify(message, never()).delete();
    }

    @Test
    void leavesAChannelThatIsNotCommandOnlyAlone() {
        when(service.findGroup(GUILD_ID, CHANNEL_ID)).thenReturn(null);
        fire();
        verify(message, never()).delete();
    }

    @Test
    void leavesADisabledGroupAlone() {
        group = new CommandChannelGroup(7, GUILD_ID, "Custom", null, false, null, null, Set.of(CHANNEL_ID), Set.of(), Set.of());
        when(service.findGroup(GUILD_ID, CHANNEL_ID)).thenReturn(group);
        fire();
        verify(message, never()).delete();
    }

    @Test
    void leavesAnExemptMemberAlone() {
        when(service.appliesTo(group, member, guild)).thenReturn(false);
        fire();
        verify(message, never()).delete();
    }

    @Test
    void doesNothingWhenTheBotCannotManageMessagesThere() {
        when(self.hasPermission(any(net.dv8tion.jda.api.entities.channel.middleman.GuildChannel.class), any(Permission[].class))).thenReturn(false);
        fire();
        verify(message, never()).delete();
        verify(channel, never()).sendMessage(any(CharSequence.class));
    }

    @Test
    void aBurstDeletesEveryMessageButPostsOnlyOneNotice() {
        Message second = mock(Message.class);
        AuditableRestAction<Void> secondDelete = mock(AuditableRestAction.class);
        when(second.getType()).thenReturn(MessageType.DEFAULT);
        when(second.delete()).thenReturn(secondDelete);

        fire();
        deliverDeleteSuccess();

        when(event.getMessage()).thenReturn(second);
        fire();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Void>> success = ArgumentCaptor.forClass(Consumer.class);
        verify(secondDelete).queue(success.capture(), any());
        success.getValue().accept(null);

        verify(message).delete();
        verify(second).delete();
        verify(channel, times(1)).sendMessage(any(CharSequence.class));
    }
}
