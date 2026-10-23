package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.clansite.SiteRoles.Action;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which site role each clan website route needs (T-368), and the viewer answer's shape. */
class ClanSiteRoleRoutesTest {
    private static Action action(String method, String path) {
        return ClanSiteAdminApi.actionFor(method, path.split("/"));
    }

    @Test
    void everyRouteNeedsTheRightRole() {
        assertEquals(Action.EDIT, action("GET", "clan-site"));
        assertEquals(Action.SETTINGS, action("PUT", "clan-site"), "the site's own settings");
        assertEquals(Action.SETTINGS, action("PUT", "clan-site/blocks/home"));
        assertEquals(Action.EDIT, action("GET", "clan-site/layout"));
        assertEquals(Action.EDIT, action("PUT", "clan-site/layout"), "saving the draft");
        assertEquals(Action.PUBLISH, action("POST", "clan-site/layout/publish"));
        assertEquals(Action.EDIT, action("GET", "clan-site/layout/versions"));
        assertEquals(Action.EDIT, action("GET", "clan-site/layout/versions/12"));
        assertEquals(Action.PUBLISH, action("POST", "clan-site/layout/versions/12/restore"), "rolling back is publishing");
        assertEquals(Action.PUBLISH, action("POST", "clan-site/publish"));
        assertEquals(Action.PUBLISH, action("POST", "clan-site/unpublish"));
        assertEquals(Action.SETTINGS, action("GET", "clan-site/export"));
        assertEquals(Action.SETTINGS, action("GET", "clan-site/roles"));
        assertEquals(Action.SETTINGS, action("PUT", "clan-site/roles"));
        assertEquals(Action.OWN, action("POST", "clan-site/roles/hand-over"));
        assertEquals(Action.OWN, action("POST", "clan-site/delete"));
        assertNull(action("POST", "clan-site/takedown"), "the bot's owner decides takedowns, not the site");
        assertNull(action("POST", "clan-site/restore"));
        assertNull(action("GET", "polls"), "other features' routes aren't this class's");
    }

    @Test
    void anUnknownSubRouteNeedsAdmin() {
        assertEquals(Action.SETTINGS, action("POST", "clan-site/something-new"), "fails closed: a new route is admin-only until it says otherwise");
    }

    @Test
    void theViewerAnswer() {
        DataObject visitor = SiteViewerRoutes.json(null);
        assertEquals("visitor", visitor.getString("role"));
        assertFalse(visitor.getBoolean("canEdit"));
        assertEquals(0, visitor.getArray("linkedRsns").length());

        DataObject editor = SiteViewerRoutes.json(new SiteRoleGuard.Viewer(SiteRoles.Role.EDITOR, true, true, true, false, List.of("Zezima")));
        assertEquals("editor", editor.getString("role"));
        assertTrue(editor.getBoolean("inGuild"));
        assertTrue(editor.getBoolean("staff"));
        assertTrue(editor.getBoolean("canEdit"));
        assertFalse(editor.getBoolean("canPublish"));
        assertEquals("Zezima", editor.getArray("linkedRsns").getString(0));
        assertFalse(editor.toString().matches(".*[0-9]{15,}.*"), "no Discord ids in the answer");
    }

    @Test
    void tenViewerAnswersASecondPerSite() {
        RateLimiter limiter = new RateLimiter(10, 100);
        long now = 1_000_000;
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire("site", now));
        assertFalse(limiter.tryAcquire("site", now), "the 11th in the same instant waits");
        assertTrue(limiter.tryAcquire("other", now), "each site has its own allowance");
        assertTrue(limiter.tryAcquire("site", now + 100), "one more every 100 ms");
    }
}
