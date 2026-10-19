package com.younglings.bot.internal;

import com.younglings.bot.customtag.CustomTag;
import com.younglings.bot.customtag.CustomTagRepository;
import com.younglings.bot.customtag.CustomTagVariables;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom tags over a real database (skipped without one), and what the variables do. */
class CustomTagAdminApiTest {
    private static final long G = 999_000_000_000_000_501L;
    private static ConnectionSupplier supplier;
    private CustomTagRepository repository;
    private CustomTagAdminApi api;
    private Guild guild;
    private Member admin;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new CustomTagRepository(supplier);
        api = new CustomTagAdminApi(repository);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Role role = Mockito.mock(Role.class);
        Mockito.when(guild.getRoleById(700L)).thenReturn(role);
        admin = Mockito.mock(Member.class);
        Mockito.when(admin.getId()).thenReturn("1");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"custom_tag", "custom_tag_config"}) {
            try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, G);
                s.executeUpdate();
            }
        }
    }

    private DataObject call(String method, String json, String... parts) {
        return api.route(guild, admin, method, parts, () -> DataObject.fromJson(json));
    }

    @Test
    void isOffByDefaultAndTheSwitchSaves() {
        assertFalse(call("GET", "{}", "custom-tags").getBoolean("enabled"));
        assertTrue(call("PUT", "{\"enabled\":true}", "custom-tags", "settings").getBoolean("enabled"));
        assertTrue(repository.enabled(G));
    }

    @Test
    void aTagIsCreatedChangedAndDeletedAndNamesAreUnique() {
        DataObject made = call("POST", "{\"name\":\"Rules\",\"content\":\"Be kind, {user}\",\"embed\":true,\"embedTitle\":\"Rules\",\"embedColor\":\"#5865f2\",\"delivery\":\"PRIVATE\",\"allowedRoles\":[\"700\"],\"cooldownSeconds\":30}", "custom-tags", "tags");
        assertEquals("rules", made.getString("name"), "names are lower case");
        assertEquals("#5865f2", made.getString("embedColor"));
        assertEquals("PRIVATE", made.getString("delivery"));
        assertEquals("700", made.getArray("allowedRoles").getString(0));

        ApiError duplicate = assertThrows(ApiError.class, () -> call("POST", "{\"name\":\"RULES\",\"content\":\"x\"}", "custom-tags", "tags"));
        assertEquals(400, duplicate.status);

        String id = made.getString("id");
        DataObject changed = call("PUT", "{\"name\":\"rules\",\"content\":\"New text\",\"enabled\":false}", "custom-tags", "tags", id);
        assertEquals("New text", changed.getString("content"));
        assertFalse(changed.getBoolean("enabled"));
        assertEquals(0, call("DELETE", "{}", "custom-tags", "tags", id).getArray("tags").length());
    }

    @Test
    void badTagsAreRefusedWithReasons() {
        ApiError error = assertThrows(ApiError.class, () -> call("POST", "{\"name\":\"bad name!\",\"content\":\"\",\"embedColor\":\"red\",\"delivery\":\"SHOUT\",\"cooldownSeconds\":-5,\"allowedRoles\":[\"404\"]}", "custom-tags", "tags"));
        assertTrue(error.problems.size() >= 5, error.problems.toString());
        assertEquals(0, repository.forGuild(G).size());
    }

    @Test
    void variablesFillInAndNeverLeaveHoles() {
        CustomTagVariables.Context c = new CustomTagVariables.Context("<@1>", "Ryan", "Younglings", "<#2>", "  zulrah  the snake ");
        assertEquals("Hi <@1> (Ryan) in Younglings <#2>: zulrah the snake", CustomTagVariables.fill("Hi {user} ({username}) in {server} {channel}: {args}", new CustomTagVariables.Context("<@1>", "Ryan", "Younglings", "<#2>", "zulrah the snake")));
        assertEquals("zulrah|the|snake|", CustomTagVariables.fill("{arg1}|{arg2}|{arg3}|{arg4}", c));
        assertEquals(2000, CustomTagVariables.fill("x".repeat(3000), c).length());
    }

    @Test
    void colourAndNameHelpers() {
        assertEquals(0x5865F2, CustomTag.parseColour("#5865f2"));
        assertEquals(0x5865F2, CustomTag.parseColour("5865F2"));
        assertNull(CustomTag.parseColour("blue"));
        assertEquals("#00ff00", CustomTag.hex(0x00FF00));
        assertTrue(CustomTag.validName("boss-guide_2"));
        assertFalse(CustomTag.validName("-bad"));
        assertFalse(CustomTag.validName("has space"));
    }
}
