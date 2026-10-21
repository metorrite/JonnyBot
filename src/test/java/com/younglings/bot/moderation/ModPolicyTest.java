package com.younglings.bot.moderation;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.SelfMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The who-can-be-touched rules: nothing here talks to Discord, JDA objects are mocks. */
class ModPolicyTest {
    private static final long OWNER = 1, BOT = 2, MOD = 3, TARGET = 4, PROTECTED_ROLE = 50;

    private Guild guild;
    private SelfMember bot;
    private Member mod;
    private Member target;

    @BeforeEach
    void setUp() {
        guild = mock(Guild.class);
        bot = mock(SelfMember.class);
        when(bot.getIdLong()).thenReturn(BOT);
        mod = member(MOD);
        target = member(TARGET);
        when(guild.getSelfMember()).thenReturn(bot);
        when(guild.getOwnerIdLong()).thenReturn(OWNER);
        // by default: the bot has every permission, outranks the target, and the moderator outranks the target too
        for (Permission p : Permission.values()) when(bot.hasPermission(p)).thenReturn(true);
        when(bot.canInteract(target)).thenReturn(true);
        when(mod.canInteract(target)).thenReturn(true);
        when(target.getRoles()).thenReturn(List.of());
    }

    private static Member member(long id) {
        Member m = mock(Member.class);
        when(m.getIdLong()).thenReturn(id);
        return m;
    }

    private String check(ModAction action, Member actor, long targetId, Member targetMember, boolean actorAdmin, boolean targetAdmin) {
        return ModPolicy.refusal(action, guild, actor, targetId, targetMember, Set.of(PROTECTED_ROLE), actorAdmin, targetAdmin);
    }

    @Test
    void anOrdinaryActionAgainstALowerMemberIsAllowed() {
        for (ModAction a : List.of(ModAction.BAN, ModAction.KICK, ModAction.TIMEOUT, ModAction.SOFTBAN)) assertNull(check(a, mod, TARGET, target, false, false), a.name());
    }

    @Test
    void nobodyCanActOnThemselvesTheBotOrTheOwner() {
        assertEquals("You can't do that to yourself.", check(ModAction.BAN, mod, MOD, mod, false, false));
        assertEquals("I won't do that to myself.", check(ModAction.KICK, mod, BOT, bot, false, false));
        assertEquals("The server owner can't be moderated.", check(ModAction.BAN, mod, OWNER, member(OWNER), false, false));
        // also when the owner is not a cached member (a ban by id)
        assertEquals("The server owner can't be moderated.", check(ModAction.BAN, mod, OWNER, null, true, false));
    }

    @Test
    void aModeratorMustOutrankTheTarget() {
        when(mod.canInteract(target)).thenReturn(false);
        String why = check(ModAction.BAN, mod, TARGET, target, false, false);
        assertNotNull(why);
        assertTrue(why.contains("below yours"), why);
    }

    @Test
    void theBotMustOutrankTheTargetToo() {
        when(bot.canInteract(target)).thenReturn(false);
        assertTrue(check(ModAction.KICK, mod, TARGET, target, true, false).contains("below mine"));
    }

    @Test
    void theBotNeedsTheDiscordPermissionForTheAction() {
        when(bot.hasPermission(Permission.BAN_MEMBERS)).thenReturn(false);
        assertEquals("I need the Ban Members permission to do that.", check(ModAction.BAN, mod, TARGET, target, false, false));
        assertEquals("I need the Ban Members permission to do that.", check(ModAction.SOFTBAN, mod, TARGET, target, false, false));
        assertNull(check(ModAction.KICK, mod, TARGET, target, false, false), "kicking needs a different permission");
        when(bot.hasPermission(Permission.MODERATE_MEMBERS)).thenReturn(false);
        assertTrue(check(ModAction.TIMEOUT, mod, TARGET, target, false, false).contains("Timeout Members"));
    }

    @Test
    void aMemberHoldingAProtectedRoleCanNeverBeTouchedEvenByAnAdmin() {
        Role protectedRole = mock(Role.class);
        when(protectedRole.getIdLong()).thenReturn(PROTECTED_ROLE);
        when(protectedRole.getName()).thenReturn("Founders");
        when(target.getRoles()).thenReturn(List.of(protectedRole));
        for (ModAction a : List.of(ModAction.BAN, ModAction.KICK, ModAction.TIMEOUT, ModAction.SOFTBAN)) {
            String why = check(a, mod, TARGET, target, true, false);
            assertNotNull(why, a.name());
            assertTrue(why.contains("Founders"), why);
        }
        // the bot's own automatic actions are held to it as well
        assertNotNull(check(ModAction.BAN, null, TARGET, target, false, false));
    }

    @Test
    void undoingSomethingIsNotBlockedByAProtectedRole() {
        Role protectedRole = mock(Role.class);
        when(protectedRole.getIdLong()).thenReturn(PROTECTED_ROLE);
        when(target.getRoles()).thenReturn(List.of(protectedRole));
        when(bot.canInteract(target)).thenReturn(true);
        assertNull(check(ModAction.UNTIMEOUT, mod, TARGET, target, false, false));
        assertNull(check(ModAction.UNBAN, mod, TARGET, null, false, false));
    }

    @Test
    void onlyAnAdminCanModerateAnotherAdmin() {
        assertEquals("Only an Admin can moderate another Admin.", check(ModAction.KICK, mod, TARGET, target, false, true));
        assertNull(check(ModAction.KICK, mod, TARGET, target, true, true));
    }

    @Test
    void aTargetWhoIsNotInTheServerSkipsTheRankChecksButNotTheRest() {
        assertNull(check(ModAction.BAN, mod, 999L, null, false, false));
        when(bot.hasPermission(Permission.BAN_MEMBERS)).thenReturn(false);
        assertNotNull(check(ModAction.BAN, mod, 999L, null, false, false));
    }

    @Test
    void anAdministratorCannotBeTimedOutBecauseDiscordRefusesIt() {
        when(target.hasPermission(Permission.ADMINISTRATOR)).thenReturn(true);
        assertTrue(check(ModAction.TIMEOUT, mod, TARGET, target, true, false).contains("Administrator"));
        assertNull(check(ModAction.KICK, mod, TARGET, target, true, false));
    }

    @Test
    void theBotActingAloneSkipsOnlyTheModeratorRankRule() {
        when(mod.canInteract(target)).thenReturn(false);
        assertNull(check(ModAction.KICK, null, TARGET, target, false, false), "no moderator to compare with");
        when(bot.canInteract(target)).thenReturn(false);
        assertNotNull(check(ModAction.KICK, null, TARGET, target, false, false), "the bot still has to outrank them");
    }
}
