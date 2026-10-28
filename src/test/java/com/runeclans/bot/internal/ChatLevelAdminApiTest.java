package com.runeclans.bot.internal;

import com.runeclans.bot.chatlevel.ChatLevelConfig;
import com.runeclans.bot.chatlevel.ChatLevelRepository;
import com.runeclans.bot.chatlevel.ChatLevels;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chat level settings, level roles and XP over a real database (skipped without one). */
class ChatLevelAdminApiTest {
    private static final long G = 999_000_000_000_000_401L;
    private static ConnectionSupplier supplier;
    private ChatLevelRepository repository;
    private ChatLevelAdminApi api;
    private Guild guild;
    private Member admin;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    private Role role(long id, boolean safe) {
        Role role = Mockito.mock(Role.class);
        Mockito.when(role.getIdLong()).thenReturn(id);
        Mockito.when(role.getName()).thenReturn("role" + id);
        Mockito.when(role.isPublicRole()).thenReturn(false);
        Mockito.when(role.isManaged()).thenReturn(false);
        Mockito.when(role.getPermissions()).thenReturn(safe ? EnumSet.noneOf(Permission.class) : EnumSet.of(Permission.ADMINISTRATOR));
        return role;
    }

    @BeforeEach
    void setUp() {
        repository = new ChatLevelRepository(supplier);
        api = new ChatLevelAdminApi(repository);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        net.dv8tion.jda.api.entities.SelfMember self = Mockito.mock(net.dv8tion.jda.api.entities.SelfMember.class);
        Mockito.when(self.canInteract(Mockito.any(Role.class))).thenReturn(true);
        Mockito.when(guild.getSelfMember()).thenReturn(self);
        Role r700 = role(700, true);
        Role r701 = role(701, true);
        Role r702 = role(702, false);
        Mockito.when(guild.getRoleById(700L)).thenReturn(r700);
        Mockito.when(guild.getRoleById(701L)).thenReturn(r701);
        Mockito.when(guild.getRoleById(702L)).thenReturn(r702);
        GuildChannel channel = Mockito.mock(GuildChannel.class);
        Mockito.when(guild.getGuildChannelById(600L)).thenReturn(channel);
        admin = Mockito.mock(Member.class);
        Mockito.when(admin.getId()).thenReturn("1");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"chat_level_config", "chat_level_member", "chat_level_role"}) {
            try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, G);
                s.executeUpdate();
            }
        }
    }

    private DataObject put(String json) {
        return api.route(guild, admin, "PUT", new String[]{"chat-levels"}, () -> DataObject.fromJson(json));
    }

    @Test
    void isOffByDefaultAndSavesSettingsWithLevelRoles() {
        DataObject fresh = api.route(guild, admin, "GET", new String[]{"chat-levels"}, DataObject::empty);
        assertFalse(fresh.getBoolean("enabled"));
        assertEquals(15, fresh.getInt("minXp"));

        DataObject saved = put("{\"enabled\":true,\"minXp\":10,\"maxXp\":20,\"cooldownSeconds\":30,\"announce\":true,\"announceMessage\":\"{user} is level {level}\","
                + "\"ignoredChannels\":[\"600\"],\"channelMultipliers\":[{\"channelId\":\"600\",\"multiplier\":2}],\"roleMultipliers\":[{\"roleId\":\"700\",\"multiplier\":1.5}],"
                + "\"levelRoles\":[{\"level\":10,\"roleId\":\"700\"},{\"level\":5,\"roleId\":\"701\"}]}");
        assertTrue(saved.getBoolean("enabled"));
        assertEquals(2, saved.getArray("levelRoles").length());
        assertEquals(5, saved.getArray("levelRoles").getObject(0).getInt("level"), "in level order");
        assertEquals(2.0, saved.getArray("channelMultipliers").getObject(0).getDouble("multiplier"));
    }

    @Test
    void refusesBadValuesAndUnsafeRolesWithoutStoringAnything() {
        ApiError error = assertThrows(ApiError.class, () -> put("{\"enabled\":true,\"minXp\":50,\"maxXp\":10,\"cooldownSeconds\":1,\"levelRoles\":[{\"level\":5,\"roleId\":\"702\"},{\"level\":0,\"roleId\":\"700\"}],"
                + "\"channelMultipliers\":[{\"channelId\":\"600\",\"multiplier\":99}]}"));
        assertEquals(400, error.status);
        assertTrue(error.problems.size() >= 4);
        assertFalse(repository.config(G).enabled());
        assertTrue(repository.roles(G).isEmpty());
    }

    @Test
    void xpAddsUpRanksAndResets() {
        assertEquals(20, repository.addXp(G, 1, 20));
        assertEquals(50, repository.addXp(G, 1, 30));
        repository.addXp(G, 2, 100);
        assertEquals(2, repository.rankOf(G, 50));
        assertEquals(1, repository.rankOf(G, 100));
        assertEquals(2, repository.standing(G, 1).orElseThrow().messages());
        assertEquals(2, repository.top(G, 10).size());
        assertEquals(2L, api.route(guild, admin, "GET", new String[]{"chat-levels"}, DataObject::empty).getLong("members"));

        assertTrue(repository.reset(G, 1));
        assertTrue(repository.standing(G, 1).isEmpty());
        assertEquals(1, repository.resetAll(G));
        assertThrows(ApiError.class, () -> api.route(guild, admin, "POST", new String[]{"chat-levels", "reset"}, DataObject::empty));
    }

    @Test
    void theCurveAndTheListenerRules() {
        assertEquals(0, ChatLevels.levelOf(99));
        assertEquals(1, ChatLevels.levelOf(100));
        assertEquals(100, ChatLevels.totalFor(1));
        assertEquals(255, ChatLevels.totalFor(2));
        assertEquals(2, ChatLevels.levelOf(ChatLevels.totalFor(2)));
        assertEquals(ChatLevels.MAX_LEVEL, ChatLevels.levelOf(Long.MAX_VALUE / 2));
        assertEquals("▰▰▰▰▰▰▱▱▱▱▱▱", ChatLevels.bar(100 + ChatLevels.xpToNext(1) / 2, 12));
        assertEquals("a is level 7 on s", ChatLevelConfig.fill("{user} is level {level} on {server}", "a", 7, "s"));
    }
}
