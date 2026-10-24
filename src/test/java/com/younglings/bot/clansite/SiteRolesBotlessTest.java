package com.younglings.bot.clansite;

import com.younglings.bot.clansite.SiteRoles.Action;
import com.younglings.bot.clansite.SiteRoles.Role;
import com.younglings.bot.clansite.SiteRoles.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Site roles for a server the bot isn't in: owner and admin from the website's signed facts, editors by Discord user id. */
class SiteRolesBotlessTest {
    private static final long OWNER = 101;
    private static final long ADMIN = 102;
    private static final long EDITOR = 103;
    private static final long SOMEONE = 104;
    private static final Settings SETTINGS = new Settings(List.of(), List.of(), List.of(), false, null, List.of(EDITOR));

    @Test
    void ownerAndAdminComeFromTheFacts() {
        assertEquals(Role.OWNER, SiteRoles.resolve(SiteRoleGuard.factsWithoutBot(OWNER, true, false), SETTINGS));
        assertEquals(Role.ADMIN, SiteRoles.resolve(SiteRoleGuard.factsWithoutBot(ADMIN, false, true), SETTINGS));
        assertTrue(SiteRoles.allows(Role.ADMIN, Action.PUBLISH, SETTINGS));
        assertFalse(SiteRoles.allows(Role.ADMIN, Action.OWN, SETTINGS));
    }

    @Test
    void editorsAreNamedByUserIdAndNobodyElseCanEdit() {
        assertEquals(Role.EDITOR, SiteRoles.resolve(SiteRoleGuard.factsWithoutBot(EDITOR, false, false), SETTINGS));
        Role someone = SiteRoles.resolve(SiteRoleGuard.factsWithoutBot(SOMEONE, false, false), SETTINGS);
        assertFalse(SiteRoles.allows(someone, Action.EDIT, SETTINGS));
        assertFalse(SiteRoles.allows(Role.EDITOR, Action.PUBLISH, SETTINGS), "editors publish only with the switch on");
        assertTrue(SiteRoles.allows(Role.EDITOR, Action.PUBLISH, new Settings(List.of(), List.of(), List.of(), true, null, List.of(EDITOR))));
    }

    @Test
    void editorsByUserIdWorkWhereTheBotIsInTheServerToo() {
        SiteRoles.Facts inServer = new SiteRoles.Facts(EDITOR, true, false, false, java.util.Set.of(), null);
        assertEquals(Role.EDITOR, SiteRoles.resolve(inServer, SETTINGS));
        assertEquals(Role.GUEST, SiteRoles.resolve(new SiteRoles.Facts(EDITOR, false, false, false, java.util.Set.of(), null), SETTINGS),
                "not in the server: still a guest, as before");
    }

    @Test
    void thePublicSiteKnowsOnlyTheClaimantAndTheEditors() {
        Settings claimed = new Settings(List.of(), List.of(), List.of(), false, OWNER, List.of(EDITOR));
        assertEquals(Role.OWNER, SiteRoles.resolveWithoutBot(OWNER, claimed));
        assertEquals(Role.EDITOR, SiteRoles.resolveWithoutBot(EDITOR, claimed));
        assertEquals(Role.GUEST, SiteRoles.resolveWithoutBot(SOMEONE, claimed), "nobody can tell who is in the server without the bot");
        assertEquals(Role.VISITOR, SiteRoles.resolveWithoutBot(null, claimed));
    }

    @Test
    void theOldFiveFieldSettingsHaveNoEditorsByUserId() {
        assertEquals(List.of(), new Settings(List.of(), List.of(), List.of(), false, null).editorUserIds());
        assertEquals(List.of(), Settings.DEFAULT.editorUserIds());
    }
}
