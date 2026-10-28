package com.runeclans.bot.moderation;

import com.runeclans.bot.permission.AdminRoleFilter;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The moderation service against mocks: nothing here can reach Discord, so no real member is ever banned, kicked or timed out. */
class ModServiceTest {
    private static final long GUILD = 10, MOD = 3, TARGET = 4, BOT = 2;

    private ModCaseRepository cases;
    private AdminRoleFilter admins;
    private ModService service;
    private Guild guild;
    private SelfMember bot;
    private Member actor;
    private Member targetMember;
    private User target;
    private ModSettings settings;
    private AuditableRestAction<Void> discordAction;
    private CacheRestAction<PrivateChannel> openDm;
    private RestAction<net.dv8tion.jda.api.entities.Message> sendDm;
    private PrivateChannel channel;
    private MessageCreateAction create;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        cases = mock(ModCaseRepository.class);
        admins = mock(AdminRoleFilter.class);
        service = new ModService(cases, admins, null);
        settings = ModSettings.defaults(GUILD);
        when(cases.settings(GUILD)).thenReturn(settings);
        when(cases.record(anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString(), any(), anyString()))
                .thenAnswer(i -> new ModCase(i.getArgument(0), 7, i.getArgument(1), i.getArgument(2), i.getArgument(3), i.getArgument(4), i.getArgument(5), i.getArgument(6),
                        i.getArgument(7), i.getArgument(8), Instant.now(), null, null, null));

        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getName()).thenReturn("Younglings");
        bot = mock(SelfMember.class);
        when(bot.getIdLong()).thenReturn(BOT);
        when(bot.getEffectiveName()).thenReturn("JonnyBot");
        for (Permission p : Permission.values()) when(bot.hasPermission(p)).thenReturn(true);
        when(guild.getSelfMember()).thenReturn(bot);
        when(guild.getOwnerIdLong()).thenReturn(1L);

        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(MOD);
        when(actor.getEffectiveName()).thenReturn("Mod Mia");
        targetMember = mock(Member.class);
        when(targetMember.getIdLong()).thenReturn(TARGET);
        when(targetMember.getRoles()).thenReturn(List.of());
        when(actor.canInteract(targetMember)).thenReturn(true);
        when(bot.canInteract(targetMember)).thenReturn(true);

        target = mock(User.class);
        when(target.getIdLong()).thenReturn(TARGET);
        when(target.getName()).thenReturn("troublemaker");
        when(target.getAsMention()).thenReturn("<@4>");
        when(targetMember.getUser()).thenReturn(target);

        discordAction = mock(AuditableRestAction.class);
        when(discordAction.reason(anyString())).thenReturn(discordAction);
        when(discordAction.submit()).thenReturn(CompletableFuture.completedFuture(null));
        when(guild.ban(any(User.class), anyInt(), any(TimeUnit.class))).thenReturn(discordAction);
        when(guild.unban(any(User.class))).thenReturn(discordAction);
        when(guild.kick(any(Member.class))).thenReturn(discordAction);
        when(targetMember.timeoutFor(any(Duration.class))).thenReturn(discordAction);
        when(targetMember.removeTimeout()).thenReturn(discordAction);

        openDm = mock(CacheRestAction.class);
        sendDm = mock(RestAction.class);
        channel = mock(PrivateChannel.class);
        create = mock(MessageCreateAction.class);
        when(target.openPrivateChannel()).thenReturn(openDm);
        when(openDm.flatMap(any(Function.class))).thenReturn(sendDm);
        when(sendDm.submit()).thenReturn(CompletableFuture.completedFuture(null));
        when(channel.sendMessage(anyString())).thenReturn(create);
    }

    private ModService.Outcome run(ModAction action, Integer minutes) throws Exception {
        return service.execute(guild, actor, target, targetMember, action, "spamming", minutes, 0).get(5, TimeUnit.SECONDS);
    }

    @Test
    void aBanTellsThemFirstThenBansThenWritesTheCase() throws Exception {
        ModService.Outcome out = run(ModAction.BAN, null);

        assertTrue(out.done());
        assertEquals(7, out.modCase().caseNo());
        assertTrue(out.message().contains("case #7"));
        InOrder order = inOrder(target, guild, cases);
        order.verify(target).openPrivateChannel();
        order.verify(guild).ban(eq(target), eq(0), eq(TimeUnit.DAYS));
        order.verify(cases).record(eq(GUILD), eq(TARGET), eq("troublemaker"), eq(MOD), eq("Mod Mia"), eq(ModAction.BAN), eq("spamming"), isNullInt(), eq(ModCase.COMMAND));
    }

    private static Integer isNullInt() {
        return org.mockito.ArgumentMatchers.isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDmCarriesTheReasonAndAppealLinkButNotTheModeratorUnlessTheServerShowsIt() throws Exception {
        when(cases.settings(GUILD)).thenReturn(new ModSettings(GUILD, true, false, "https://example.com/appeal", List.of()));
        run(ModAction.KICK, null);
        ArgumentCaptor<Function<PrivateChannel, RestAction<net.dv8tion.jda.api.entities.Message>>> flat = ArgumentCaptor.forClass(Function.class);
        verify(openDm).flatMap(flat.capture());
        flat.getValue().apply(channel);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(channel).sendMessage(text.capture());
        assertTrue(text.getValue().contains("You have been kicked in **Younglings**"), text.getValue());
        assertTrue(text.getValue().contains("**Reason:** spamming"));
        assertTrue(text.getValue().contains("https://example.com/appeal"));
        assertFalse(text.getValue().contains("Mod Mia"), "the moderator is not named by default");

        assertTrue(ModService.dmText("Younglings", ModAction.BAN, "x", null, new ModSettings(GUILD, true, true, null, List.of()), actor).contains("**By:** Mod Mia"));
        assertFalse(ModService.dmText("Younglings", ModAction.BAN, "x", null, ModSettings.defaults(GUILD), actor).contains("appeal"));
        assertTrue(ModService.dmText("Younglings", ModAction.TIMEOUT, "x", 90, ModSettings.defaults(GUILD), actor).contains("timed out for 1 hour 30 minutes"));
    }

    @Test
    void withTheDmSettingOffNobodyIsMessaged() throws Exception {
        when(cases.settings(GUILD)).thenReturn(new ModSettings(GUILD, false, false, null, List.of()));
        assertTrue(run(ModAction.BAN, null).done());
        verify(target, never()).openPrivateChannel();
    }

    @Test
    void aRefusedActionTouchesNothingAndWritesNoCase() throws Exception {
        when(actor.canInteract(targetMember)).thenReturn(false);
        ModService.Outcome out = run(ModAction.BAN, null);

        assertFalse(out.done());
        assertTrue(out.message().contains("below yours"));
        verify(guild, never()).ban(any(User.class), anyInt(), any(TimeUnit.class));
        verify(target, never()).openPrivateChannel();
        verify(cases, never()).record(anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void aProtectedRoleStopsEveryAction() throws Exception {
        Role founders = mock(Role.class);
        when(founders.getIdLong()).thenReturn(77L);
        when(founders.getName()).thenReturn("Founders");
        when(targetMember.getRoles()).thenReturn(List.of(founders));
        when(cases.settings(GUILD)).thenReturn(new ModSettings(GUILD, true, false, null, List.of(77L)));
        for (ModAction a : List.of(ModAction.BAN, ModAction.KICK, ModAction.SOFTBAN)) assertFalse(run(a, null).done(), a.name());
        assertFalse(run(ModAction.TIMEOUT, 10).done());
        verify(guild, never()).ban(any(User.class), anyInt(), any(TimeUnit.class));
        verify(guild, never()).kick(any(Member.class));
        verify(targetMember, never()).timeoutFor(any(Duration.class));
        verify(cases, never()).record(anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void whenDiscordRefusesNoCaseIsWritten() throws Exception {
        when(discordAction.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Missing Access")));
        ModService.Outcome out = run(ModAction.BAN, null);

        assertFalse(out.done());
        assertTrue(out.message().contains("Missing Access"), out.message());
        verify(cases, never()).record(anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void aClosedDmDoesNotStopTheAction() throws Exception {
        when(sendDm.submit()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Cannot send messages to this user")));
        assertTrue(run(ModAction.KICK, null).done());
        verify(guild).kick(targetMember);
    }

    @Test
    void aTimeoutNeedsARealDurationAndRecordsIt() throws Exception {
        assertFalse(run(ModAction.TIMEOUT, null).done());
        assertFalse(run(ModAction.TIMEOUT, 0).done());
        assertFalse(run(ModAction.TIMEOUT, ModDurations.MAX_TIMEOUT_MINUTES + 1).done());
        verify(targetMember, never()).timeoutFor(any(Duration.class));

        assertTrue(run(ModAction.TIMEOUT, 90).done());
        verify(targetMember).timeoutFor(Duration.ofMinutes(90));
        verify(cases).record(eq(GUILD), eq(TARGET), anyString(), eq(MOD), anyString(), eq(ModAction.TIMEOUT), anyString(), eq(90), eq(ModCase.COMMAND));
    }

    @Test
    void anUntimeoutOnSomeoneNotTimedOutIsRefused() throws Exception {
        when(targetMember.isTimedOut()).thenReturn(false);
        assertFalse(run(ModAction.UNTIMEOUT, null).done());
        when(targetMember.isTimedOut()).thenReturn(true);
        assertTrue(run(ModAction.UNTIMEOUT, null).done());
        verify(targetMember).removeTimeout();
    }

    @Test
    void aSoftbanBansThenUnbans() throws Exception {
        assertTrue(run(ModAction.SOFTBAN, null).done());
        InOrder order = inOrder(guild);
        order.verify(guild).ban(eq(target), eq(1), eq(TimeUnit.DAYS));
        order.verify(guild).unban(target);
    }

    @Test
    void anUnbanOfSomeoneNotInTheServerWorks() throws Exception {
        assertTrue(service.execute(guild, actor, target, null, ModAction.UNBAN, "appeal accepted", null, 0).get(5, TimeUnit.SECONDS).done());
        verify(guild).unban(target);
        verify(target, never()).openPrivateChannel();
    }

    @Test
    void timeoutAndKickOfSomeoneWhoLeftAreRefused() throws Exception {
        assertFalse(service.execute(guild, actor, target, null, ModAction.KICK, "x", null, 0).get(5, TimeUnit.SECONDS).done());
        assertFalse(service.execute(guild, actor, target, null, ModAction.TIMEOUT, "x", 5, 0).get(5, TimeUnit.SECONDS).done());
    }

    @Test
    void recordOnlyWritesACaseWithoutTouchingDiscordAndNeverThrows() {
        ModCase written = service.recordOnly(guild, TARGET, "troublemaker", BOT, "JonnyBot", ModAction.WARN, "  ", null, ModCase.WARN);
        assertEquals("No reason given", written.reason());
        verify(guild, never()).ban(any(User.class), anyInt(), any(TimeUnit.class));

        when(cases.record(anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString(), any(), anyString())).thenThrow(new RuntimeException("db down"));
        assertNull(service.recordOnly(guild, TARGET, "x", BOT, "JonnyBot", ModAction.WARN, "r", null, ModCase.WARN));
    }

    @Test
    void reasonsAreTrimmedDefaultedAndCapped() {
        assertEquals("No reason given", ModService.cleanReason(null));
        assertEquals("hi", ModService.cleanReason("  hi "));
        assertEquals(ModService.MAX_REASON, ModService.cleanReason("x".repeat(900)).length());
        assertNull(ModService.cleanAppealUrl("javascript:alert(1)"));
        assertEquals("https://example.com/appeal", ModService.cleanAppealUrl("example.com/appeal"));
    }
}
