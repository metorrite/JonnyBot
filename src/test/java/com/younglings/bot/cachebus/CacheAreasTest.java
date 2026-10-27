package com.younglings.bot.cachebus;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The caches of docs/SCALING-STATE.md section 4.3 (and the admin reads) answer to the bus, each under an area of its own. */
class CacheAreasTest {
    static final List<String> SUBSCRIBED = List.of(
            "automod.AutomodRepository", "autoreply.AutoReplyRepository", "customtag.CustomTagRepository", "highlight.HighlightRepository",
            "chatlevel.ChatLevelRepository", "servertag.ServerTagRepository", "honeypot.HoneypotRepository", "autodelete.AutoDeleteRepository",
            "messagelog.MessageLogRepository", "points.PointsExtrasRepository", "rolerules.RoleRuleService", "commandchannel.CommandChannelService",
            "permission.PermissionGroupService", "hub.HubService", "i18n.Messages", "afk.AfkService", "clansite.BotlessSiteRepository",
            "internal.AdminReadsCache");

    @Test
    void everyListedCacheHasAnAreaOfItsOwn() throws Exception {
        Map<String, String> owners = new HashMap<>();
        for (String name : SUBSCRIBED) {
            Class<?> type = Class.forName("com.younglings.bot." + name);
            assertTrue(CacheInvalidator.class.isAssignableFrom(type), name + " should implement CacheInvalidator");
            Field field = type.getDeclaredField("CACHE_AREA");
            field.setAccessible(true);
            String area = (String) field.get(null);
            assertTrue(CacheSignal.validArea(area), name + " has a bad area " + area);
            assertNull(owners.put(area, name), "two caches share the area " + area);
        }
        assertEquals(SUBSCRIBED.size(), owners.size());
    }
}
