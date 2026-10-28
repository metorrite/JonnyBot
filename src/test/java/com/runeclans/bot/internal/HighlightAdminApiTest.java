package com.runeclans.bot.internal;

import com.runeclans.bot.highlight.HighlightRepository;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Highlights settings and members' words over a real database (skipped without one). */
class HighlightAdminApiTest {
    private static final long G = 999_000_000_000_000_201L;
    private static ConnectionSupplier supplier;
    private HighlightRepository repository;
    private HighlightAdminApi api;
    private Guild guild;
    private Member admin;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new HighlightRepository(supplier);
        api = new HighlightAdminApi(repository);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        Mockito.when(guild.getGuildChannelById(555L)).thenReturn(Mockito.mock(GuildChannel.class));
        Mockito.when(guild.getRoleById(777L)).thenReturn(Mockito.mock(Role.class));
        admin = Mockito.mock(Member.class);
        Mockito.when(admin.getId()).thenReturn("1");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"highlight_config", "highlight_keyword", "highlight_ignore"}) {
            try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id = ?")) {
                s.setLong(1, G);
                s.executeUpdate();
            }
        }
    }

    private DataObject put(String json) {
        return api.route(guild, admin, "PUT", new String[]{"highlights"}, () -> DataObject.fromJson(json));
    }

    @Test
    void isOffByDefaultAndSavesWholeSettings() {
        DataObject fresh = api.route(guild, admin, "GET", new String[]{"highlights"}, DataObject::empty);
        assertFalse(fresh.getBoolean("enabled"));
        assertEquals(10, fresh.getInt("maxKeywords"));

        DataObject saved = put("{\"enabled\":true,\"maxKeywords\":5,\"cooldownSeconds\":120,\"excludedChannels\":[\"555\"],\"allowedRoles\":[\"777\"]}");
        assertTrue(saved.getBoolean("enabled"));
        assertEquals(5, saved.getInt("maxKeywords"));
        assertEquals("555", saved.getArray("excludedChannels").getString(0));
        assertEquals("777", saved.getArray("allowedRoles").getString(0));
    }

    @Test
    void refusesBadValuesWithoutStoringAnything() {
        ApiError error = assertThrows(ApiError.class, () -> put("{\"enabled\":true,\"maxKeywords\":99,\"cooldownSeconds\":1,\"excludedChannels\":[\"404\"]}"));
        assertEquals(400, error.status);
        assertFalse(repository.config(G).enabled());
    }

    @Test
    void wordsAreUniquePerMemberIgnoringCaseAndCanBeRemoved() {
        assertTrue(repository.addKeyword(G, 5, "Zulrah", true));
        assertFalse(repository.addKeyword(G, 5, "zulrah", false));
        assertTrue(repository.addKeyword(G, 6, "zulrah", true));
        assertEquals(1, repository.keywordsOf(G, 5).size());
        assertTrue(repository.removeKeyword(G, 5, null, "ZULRAH"));
        assertEquals(0, repository.keywordsOf(G, 5).size());
        assertEquals(1, repository.keywordsOf(G, 6).size());

        repository.setIgnored(G, 6, 555L, true);
        assertTrue(repository.ignoredBy(G, 6).contains(555L));
        repository.forgetMember(G, 6);
        assertEquals(0, repository.keywordsOf(G, 6).size());
        assertTrue(repository.ignoredBy(G, 6).isEmpty());
    }
}
