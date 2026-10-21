package com.younglings.bot.honeypot;

import com.younglings.bot.honeypot.HoneypotConfig.Action;
import com.younglings.bot.honeypot.HoneypotRules.Trigger;
import com.younglings.bot.moderation.ModAction;
import com.younglings.bot.moderation.ModCase;
import com.younglings.bot.moderation.ModService;
import com.younglings.bot.permission.AdminRoleFilter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The honeypot's decision and its listener. Everything is a mock: no real member is ever acted on. */
class HoneypotTest {
    private static final long GUILD = 10, CHANNEL = 100, ROLE = 200, EXEMPT = 300;

    private static HoneypotConfig on(Action action) {
        return new HoneypotConfig(GUILD, true, CHANNEL, ROLE, action, 60, List.of(EXEMPT), 0);
    }

    private static boolean act(HoneypotConfig c, Trigger t, long channel, long role, boolean bot, boolean owner, boolean staff, List<Long> roles) {
        return HoneypotRules.shouldAct(c, t, channel, role, bot, owner, staff, roles);
    }

    @Test
    void itIsOffByDefaultAndWhenOffNeverActs() {
        assertFalse(HoneypotConfig.defaults(GUILD).enabled());
        assertEquals(Action.TIMEOUT, HoneypotConfig.defaults(GUILD).action(), "the reversible action is the default");
        HoneypotConfig off = new HoneypotConfig(GUILD, false, CHANNEL, ROLE, Action.BAN, 60, List.of(), 0);
        assertFalse(act(off, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, false, false, false, List.of()));
        assertFalse(act(off, Trigger.TOOK_ROLE, 0, ROLE, false, false, false, List.of()));
    }

    @Test
    void itActsOnlyOnTheDecoyChannelOrTheDecoyRole() {
        HoneypotConfig c = on(Action.BAN);
        assertTrue(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, false, false, false, List.of()));
        assertFalse(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL + 1, 0, false, false, false, List.of()), "another channel");
        assertTrue(act(c, Trigger.TOOK_ROLE, 0, ROLE, false, false, false, List.of()));
        assertFalse(act(c, Trigger.TOOK_ROLE, 0, ROLE + 1, false, false, false, List.of()), "another role");
        HoneypotConfig channelOnly = new HoneypotConfig(GUILD, true, CHANNEL, null, Action.KICK, 60, List.of(), 0);
        assertFalse(act(channelOnly, Trigger.TOOK_ROLE, 0, ROLE, false, false, false, List.of()), "no decoy role set");
    }

    @Test
    void botsTheOwnerStaffAndExemptRolesAreNeverActedOn() {
        HoneypotConfig c = on(Action.BAN);
        assertFalse(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, true, false, false, List.of()));
        assertFalse(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, false, true, false, List.of()));
        assertFalse(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, false, false, true, List.of()));
        assertFalse(act(c, Trigger.POSTED_IN_CHANNEL, CHANNEL, 0, false, false, false, List.of(5L, EXEMPT)));
        assertFalse(act(c, Trigger.TOOK_ROLE, 0, ROLE, false, false, false, List.of(EXEMPT)));
    }

    @Test
    void settingsAreCheckedBeforeTheyCanBeSwitchedOn() {
        assertTrue(new HoneypotConfig(GUILD, true, null, null, Action.TIMEOUT, 60, List.of(), 0).problems().get(0).contains("decoy channel or a decoy role"));
        assertFalse(new HoneypotConfig(GUILD, false, null, null, Action.TIMEOUT, 60, List.of(), 0).problems().stream().anyMatch(p -> p.contains("decoy")), "off needs nothing chosen");
        assertFalse(new HoneypotConfig(GUILD, true, CHANNEL, null, Action.TIMEOUT, 0, List.of(), 0).problems().isEmpty());
        assertFalse(new HoneypotConfig(GUILD, true, CHANNEL, null, Action.TIMEOUT, 60 * 24 * 29, List.of(), 0).problems().isEmpty());
        assertFalse(new HoneypotConfig(GUILD, true, CHANNEL, null, Action.BAN, 60, List.of(), 8).problems().isEmpty());
        assertTrue(on(Action.KICK).problems().isEmpty());
    }

    // ---- the listener, with every Discord object a mock ----

    private HoneypotRepository repository;
    private ModService mod;
    private AdminRoleFilter admins;
    private HoneypotListener listener;
    private Guild guild;
    private Member member;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(HoneypotRepository.class);
        mod = mock(ModService.class);
        admins = mock(AdminRoleFilter.class);
        listener = new HoneypotListener(repository, mod, admins);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn("10");
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getOwnerIdLong()).thenReturn(1L);
        user = mock(User.class);
        when(user.isBot()).thenReturn(false);
        member = mock(Member.class);
        when(member.getUser()).thenReturn(user);
        when(member.getId()).thenReturn("4");
        when(member.getIdLong()).thenReturn(4L);
        when(member.getRoles()).thenReturn(List.of());
        when(mod.execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new ModService.Outcome(true, "done", new ModCase(GUILD, 9, 4, "x", 2, "Bot", ModAction.TIMEOUT, "r", 60, ModCase.HONEYPOT, java.time.Instant.now(), null, null, null))));
    }

    @SuppressWarnings("unchecked")
    private MessageReceivedEvent message(long channelId) {
        MessageReceivedEvent event = mock(MessageReceivedEvent.class);
        MessageChannelUnion channel = mock(MessageChannelUnion.class);
        when(channel.getIdLong()).thenReturn(channelId);
        Message message = mock(Message.class);
        AuditableRestAction<Void> delete = mock(AuditableRestAction.class);
        when(message.delete()).thenReturn(delete);
        when(event.isFromGuild()).thenReturn(true);
        when(event.isWebhookMessage()).thenReturn(false);
        when(event.getAuthor()).thenReturn(user);
        when(event.getMember()).thenReturn(member);
        when(event.getGuild()).thenReturn(guild);
        when(event.getChannel()).thenReturn(channel);
        when(event.getMessage()).thenReturn(message);
        return event;
    }

    @Test
    void aPostInTheDecoyChannelDeletesItAndActsThroughTheModerationService() {
        when(repository.get(GUILD)).thenReturn(on(Action.TIMEOUT));
        MessageReceivedEvent event = message(CHANNEL);
        listener.onMessageReceived(event);

        verify(event.getMessage()).delete();
        verify(mod).execute(eq(guild), eq(null), eq(user), eq(member), eq(ModAction.TIMEOUT), eq(HoneypotRules.reason(Trigger.POSTED_IN_CHANNEL)), eq(60), eq(0), eq(ModCase.HONEYPOT));
    }

    @Test
    void aSecondMessageRightAfterDoesNotActAgain() {
        when(repository.get(GUILD)).thenReturn(on(Action.KICK));
        listener.onMessageReceived(message(CHANNEL));
        listener.onMessageReceived(message(CHANNEL));
        verify(mod, times(1)).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void messagesElsewhereBotsAndStaffDoNothing() {
        when(repository.get(GUILD)).thenReturn(on(Action.BAN));
        listener.onMessageReceived(message(CHANNEL + 5));
        when(user.isBot()).thenReturn(true);
        listener.onMessageReceived(message(CHANNEL));
        when(user.isBot()).thenReturn(false);
        when(member.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        listener.onMessageReceived(message(CHANNEL));
        verify(mod, never()).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void withTheHoneypotOffNothingHappensEvenInTheDecoyChannel() {
        when(repository.get(GUILD)).thenReturn(HoneypotConfig.defaults(GUILD));
        MessageReceivedEvent event = message(CHANNEL);
        listener.onMessageReceived(event);
        verify(event.getMessage(), never()).delete();
        verify(mod, never()).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    void takingTheDecoyRoleActsButAnotherRoleDoesNot() {
        when(repository.get(GUILD)).thenReturn(on(Action.BAN));
        Role decoy = mock(Role.class);
        when(decoy.getIdLong()).thenReturn(ROLE);
        Role other = mock(Role.class);
        when(other.getIdLong()).thenReturn(ROLE + 1);

        GuildMemberRoleAddEvent harmless = mock(GuildMemberRoleAddEvent.class);
        when(harmless.getGuild()).thenReturn(guild);
        when(harmless.getMember()).thenReturn(member);
        when(harmless.getRoles()).thenReturn(List.of(other));
        listener.onGuildMemberRoleAdd(harmless);
        verify(mod, never()).execute(any(), any(), any(), any(), any(), anyString(), any(), anyInt(), anyString());

        GuildMemberRoleAddEvent trap = mock(GuildMemberRoleAddEvent.class);
        when(trap.getGuild()).thenReturn(guild);
        when(trap.getMember()).thenReturn(member);
        when(trap.getRoles()).thenReturn(List.of(decoy));
        listener.onGuildMemberRoleAdd(trap);
        verify(mod).execute(eq(guild), eq(null), eq(user), eq(member), eq(ModAction.BAN), eq(HoneypotRules.reason(Trigger.TOOK_ROLE)), eq(null), eq(0), eq(ModCase.HONEYPOT));
    }
}
