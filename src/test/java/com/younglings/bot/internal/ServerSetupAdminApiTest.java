package com.younglings.bot.internal;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.runescape.ClanVerificationService;
import com.younglings.bot.runescape.PlayerLinkService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServerSetupAdminApiTest {
    private static final long GUILD = 10L;
    private static final long ACTOR = 77L;

    private GuildSettingsService settings;
    private ClanVerificationService clanVerification;
    private PlayerLinkService links;
    private DashboardAccess access;
    private ServerSetupAdminApi api;
    private Guild guild;
    private Member actor;
    private SelfMember self;

    @BeforeEach
    void setUp() {
        settings = mock(GuildSettingsService.class);
        clanVerification = mock(ClanVerificationService.class);
        links = mock(PlayerLinkService.class);
        access = mock(DashboardAccess.class);
        api = new ServerSetupAdminApi(settings, clanVerification, links, access);

        guild = mock(Guild.class);
        actor = mock(Member.class);
        self = mock(SelfMember.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getSelfMember()).thenReturn(self);
        when(actor.getIdLong()).thenReturn(ACTOR);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.ADMIN);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, null, null, null, null, null, null, null, true, null, null, null));
    }

    private Role role(long id, boolean managed, boolean interactable) {
        Role role = mock(Role.class);
        when(role.isManaged()).thenReturn(managed);
        when(role.getName()).thenReturn("Role" + id);
        when(guild.getRoleById(id)).thenReturn(role);
        when(self.canInteract(role)).thenReturn(interactable);
        return role;
    }

    // ---------- reading the request ----------

    @Test
    void anIdFieldIsAbsentClearedOrSet() {
        var problems = new ArrayList<String>();
        DataObject body = DataObject.fromJson("{\"a\": null, \"b\": \"\", \"c\": \"123\"}");

        assertEquals(false, ServerSetupAdminApi.id(body, "missing", problems).present());
        assertEquals(new ServerSetupAdminApi.Field(true, null, null, false), ServerSetupAdminApi.id(body, "a", problems));
        assertEquals(new ServerSetupAdminApi.Field(true, null, null, false), ServerSetupAdminApi.id(body, "b", problems));
        assertEquals(123L, ServerSetupAdminApi.id(body, "c", problems).value());
        assertTrue(problems.isEmpty());
    }

    @Test
    void anIdThatIsNotDigitsIsAProblemNotACrash() {
        var problems = new ArrayList<String>();

        ServerSetupAdminApi.id(DataObject.fromJson("{\"x\": \"<@&5>\"}"), "x", problems);

        assertEquals(1, problems.size());
    }

    @Test
    void aBlankClanNameClearsAndALongOneIsRefused() {
        var problems = new ArrayList<String>();

        assertNull(ServerSetupAdminApi.text(DataObject.fromJson("{\"clanName\": \"  \"}"), "clanName", problems).text());
        assertEquals(0, problems.size());
        ServerSetupAdminApi.text(DataObject.fromJson("{\"clanName\": \"" + "x".repeat(31) + "\"}"), "clanName", problems);
        assertEquals(1, problems.size());
    }

    // ---------- saving ----------

    @Test
    void roleAndChannelChoicesAreSavedTogetherWhenEverythingChecksOut() {
        role(500, false, true);
        role(600, false, true);

        api.save(guild, actor, DataObject.fromJson("{\"verifiedClanRoleId\": \"500\", \"onboardingRoleId\": \"600\"}"));

        verify(settings).updateVerificationRoleSettings(GUILD, 500L, null, null);
        verify(settings).updateOnboardingRole(GUILD, 600L);
    }

    @Test
    void aRoleJonnyBotCannotGiveOutRefusesTheWholeSaveAndChangesNothing() {
        role(500, false, true);
        role(700, false, false); // above JonnyBot's own role

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"verifiedClanRoleId\": \"500\", \"unverifiedRoleId\": \"700\"}")));

        assertEquals(400, thrown.status);
        assertTrue(thrown.problems.getFirst().contains("Move JonnyBot's own role above"));
        verify(settings, never()).updateVerificationRoleSettings(anyLong(), any(), any(), any());
    }

    @Test
    void aRoleThatDoesNotExistInThisServerIsRefused() {
        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"adminRoleId\": \"999\"}")));

        assertTrue(thrown.problems.getFirst().contains("doesn't exist in this server"));
    }

    @Test
    void theDeveloperTierCannotChangeWhoIsStaff() {
        role(500, false, true);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"adminRoleId\": \"500\"}")));

        assertEquals(403, thrown.status);
        verify(settings, never()).updateAdminRole(anyLong(), any());
    }

    @Test
    void theDeveloperTierCanStillSaveTheFormWhenTheStaffRolesAreUnchanged() {
        role(500, false, true);
        when(access.tierOf(guild, actor)).thenReturn(DashboardAccess.Tier.DEVELOPER);
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, null, 400L, null, null, null, null, null, null, true, null, null, null));
        role(400, false, true);

        api.save(guild, actor, DataObject.fromJson("{\"adminRoleId\": \"400\", \"onboardingRoleId\": \"500\"}"));

        verify(settings).updateOnboardingRole(GUILD, 500L);
    }

    @Test
    void aClanTheAskerDoesNotHelpRunIsRefusedWithEveryUnmetRequirement() {
        var checks = List.of(
                new ClanVerificationService.Check("The clan **Foo** exists", true, "10 members found."),
                new ClanVerificationService.Check("You hold the Admin rank or higher", false, "**Bar** is a Recruit — that's below Admin."));
        when(clanVerification.verify(GUILD, ACTOR, "Foo")).thenReturn(new ClanVerificationService.Result(checks));

        var thrown = assertThrows(TicketAdminApi.ApiError.class, () ->
                api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"Foo\"}")));

        assertEquals(1, thrown.problems.size());
        assertTrue(thrown.problems.getFirst().contains("Admin rank"));
        verify(settings, never()).updateClanName(anyLong(), anyString());
    }

    @Test
    void aVerifiedClanIsSavedAndSwitchedOn() {
        var checks = List.of(new ClanVerificationService.Check("ok", true, "fine"));
        when(clanVerification.verify(GUILD, ACTOR, "Foo")).thenReturn(new ClanVerificationService.Result(checks));

        api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"Foo\"}"));

        verify(links).adoptAccounts(GUILD, ACTOR);
        verify(settings).updateClanName(GUILD, "Foo");
        verify(settings).setClanEnabled(GUILD, true);
    }

    @Test
    void savingTheSameClanAgainIsNotCheckedAgain() {
        when(settings.getEffective(GUILD)).thenReturn(new GuildSettings(GUILD, "Foo", null, null, null, null, null, null, null, true, "Foo", null, null));

        api.save(guild, actor, DataObject.fromJson("{\"clanName\": \"foo\"}"));

        verify(clanVerification, never()).verify(anyLong(), anyLong(), anyString());
        verify(settings).updateClanName(GUILD, "foo");
    }
}
