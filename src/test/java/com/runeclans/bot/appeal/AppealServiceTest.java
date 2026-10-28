package com.runeclans.bot.appeal;

import com.runeclans.bot.appeal.AppealRules.Appeal;
import com.runeclans.bot.appeal.AppealRules.Settings;
import com.runeclans.bot.appeal.AppealRules.Status;
import com.runeclans.bot.commands.ticket.TicketService;
import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModCaseRepository;
import com.runeclans.bot.moderation.ModService;
import com.runeclans.bot.ticket.TicketRepository;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sending and deciding appeals (T-033), Discord mocked: only the person's own appealable case, only when the server takes appeals, only once at a time;
 * Approve lifts it as the staff member and gives the appeal back when Discord or the rules refuse; Reject needs a reason and the permission.
 */
class AppealServiceTest {
    private static final long GUILD = 1, USER = 7, STAFF = 9;
    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");
    private static final String TEXT = "I was banned by mistake, it was my brother on my account.";

    private AppealRepository appeals;
    private ModCaseRepository cases;
    private ModService moderation;
    private AppealService service;
    private Guild guild;
    private Member staff;
    private boolean inForce = true;

    private static ModCase ban(long no) {
        return new ModCase(GUILD, no, USER, "someone", STAFF, "mod", ModAction.BAN, "spam", null, ModCase.COMMAND, NOW.minusSeconds(3_600), null, null, null);
    }

    private static Appeal appeal(long id, Status status) {
        return new Appeal(id, GUILD, 5, USER, "someone", TEXT, status, null, null, NOW, null, null, null);
    }

    @BeforeEach
    void setUp() {
        appeals = mock(AppealRepository.class);
        cases = mock(ModCaseRepository.class);
        moderation = mock(ModService.class);
        service = new AppealService(appeals, cases, moderation, mock(TicketRepository.class), mock(TicketService.class)) {
            @Override
            CompletableFuture<Boolean> stillInForce(Guild g, ModCase c) {
                return CompletableFuture.completedFuture(inForce);
            }
        };
        guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn("1");
        staff = mock(Member.class);
        when(staff.getIdLong()).thenReturn(STAFF);
        when(staff.getId()).thenReturn("9");
        when(appeals.settings(GUILD)).thenReturn(new Settings(GUILD, true, null, 7, null));
        when(cases.forUser(GUILD, USER, 50)).thenReturn(List.of(ban(5)));
        when(cases.find(GUILD, 5)).thenReturn(Optional.of(ban(5)));
        when(appeals.forCase(GUILD, 5, USER)).thenReturn(List.of());
    }

    private static Throwable causeOf(Throwable e) {
        Throwable t = e;
        while ((t instanceof ExecutionException || t instanceof java.util.concurrent.CompletionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    @Test
    void anAppealForTheirOwnBanIsSaved() throws Exception {
        when(appeals.create(GUILD, 5, USER, "someone", TEXT)).thenReturn(Optional.of(appeal(11, Status.OPEN)));
        assertEquals(11, service.submit(guild, USER, "someone", 5, TEXT, NOW).get().id());
    }

    @Test
    void nothingIsSavedWhenTheServerDoesntTakeAppealsOrTheCaseIsntTheirs() {
        when(appeals.settings(GUILD)).thenReturn(Settings.defaults(GUILD));
        assertEquals(403, assertThrows(AppealService.AppealProblem.class, () -> service.submit(guild, USER, "someone", 5, TEXT, NOW)).status());
        when(appeals.settings(GUILD)).thenReturn(new Settings(GUILD, true, null, 7, null));
        assertEquals(404, assertThrows(AppealService.AppealProblem.class, () -> service.submit(guild, USER, "someone", 6, TEXT, NOW)).status(), "not one of their cases");
        when(cases.forUser(GUILD, 8L, 50)).thenReturn(List.of());
        assertEquals(404, assertThrows(AppealService.AppealProblem.class, () -> service.submit(guild, 8L, "else", 5, TEXT, NOW)).status(), "someone else can't appeal case 5");
        verify(appeals, never()).create(anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void aSecondAppealWhileOneWaitsIsRefusedAndABanLiftedByHandToo() {
        when(appeals.forCase(GUILD, 5, USER)).thenReturn(List.of(appeal(11, Status.OPEN)));
        assertEquals(409, assertThrows(AppealService.AppealProblem.class, () -> service.submit(guild, USER, "someone", 5, TEXT, NOW)).status());
        when(appeals.forCase(GUILD, 5, USER)).thenReturn(List.of());
        inForce = false;
        Throwable e = assertThrows(ExecutionException.class, () -> service.submit(guild, USER, "someone", 5, TEXT, NOW).get());
        assertInstanceOf(AppealService.AppealProblem.class, causeOf(e));
        verify(appeals, never()).create(anyLong(), anyLong(), anyLong(), anyString(), anyString());
    }

    @SuppressWarnings("unchecked")
    private void userLookup() {
        JDA jda = mock(JDA.class);
        User user = mock(User.class);
        CacheRestAction<User> lookup = mock(CacheRestAction.class);
        when(lookup.submit()).thenReturn(CompletableFuture.completedFuture(user));
        when(jda.retrieveUserById(USER)).thenReturn(lookup);
        when(guild.getJDA()).thenReturn(jda);
    }

    @Test
    void approvingLiftsTheBanAsTheStaffMember() throws Exception {
        when(appeals.find(GUILD, 11)).thenReturn(Optional.of(appeal(11, Status.OPEN)));
        when(appeals.decide(GUILD, 11, Status.APPROVED, STAFF, "fair enough")).thenReturn(true);
        userLookup();
        ModCase unban = new ModCase(GUILD, 6, USER, "someone", STAFF, "mod", ModAction.UNBAN, "Appeal approved: fair enough", null, "APPEAL", NOW, null, null, null);
        when(moderation.execute(eq(guild), eq(staff), any(), eq(null), eq(ModAction.UNBAN), eq("Appeal approved: fair enough"), eq(null), eq(0), eq("APPEAL")))
                .thenReturn(CompletableFuture.completedFuture(new ModService.Outcome(true, "done", unban)));
        service.approve(guild, staff, 11, "fair enough").get();
        verify(appeals, never()).reopen(anyLong(), anyLong());
    }

    @Test
    void whenTheLiftIsRefusedTheAppealIsOpenAgain() {
        when(appeals.find(GUILD, 11)).thenReturn(Optional.of(appeal(11, Status.OPEN)));
        when(appeals.decide(GUILD, 11, Status.APPROVED, STAFF, "")).thenReturn(true);
        userLookup();
        when(moderation.execute(any(), any(), any(), any(), any(), anyString(), any(), Mockito.anyInt(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(new ModService.Outcome(false, "You need the Ban Members permission.", null)));
        Throwable e = assertThrows(ExecutionException.class, () -> service.approve(guild, staff, 11, "").get());
        assertTrue(causeOf(e).getMessage().contains("Ban Members"));
        verify(appeals).reopen(GUILD, 11);
    }

    @Test
    void rejectingNeedsAReasonAndThePermissionAndCantHappenTwice() {
        when(appeals.find(GUILD, 11)).thenReturn(Optional.of(appeal(11, Status.OPEN)));
        when(staff.hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS)).thenReturn(false);
        assertEquals(403, assertThrows(AppealService.AppealProblem.class, () -> service.reject(guild, staff, 11, "no")).status());
        when(staff.hasPermission(net.dv8tion.jda.api.Permission.BAN_MEMBERS)).thenReturn(true);
        assertEquals(400, assertThrows(AppealService.AppealProblem.class, () -> service.reject(guild, staff, 11, " ")).status());
        when(appeals.decide(GUILD, 11, Status.REJECTED, STAFF, "Not convinced")).thenReturn(false);
        assertEquals(409, assertThrows(AppealService.AppealProblem.class, () -> service.reject(guild, staff, 11, "Not convinced")).status(), "someone else decided first");
        when(appeals.find(GUILD, 12)).thenReturn(Optional.of(appeal(12, Status.REJECTED)));
        assertEquals(409, assertThrows(AppealService.AppealProblem.class, () -> service.reject(guild, staff, 12, "again")).status());
    }
}
