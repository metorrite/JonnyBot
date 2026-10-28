package com.runeclans.bot.internal;

import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.messagelog.MessageLogConfig;
import com.runeclans.bot.messagelog.MessageLogMessage;
import com.runeclans.bot.messagelog.MessageLogRepository;
import com.runeclans.bot.messagelog.MessageLogRepository.Cached;
import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Message log settings and the remembered-text cache over a real database (skipped without one), plus how entries read. */
class MessageLogAdminApiTest {
    private static final long G = 999_000_000_000_000_301L;
    private static final long OTHER = 999_000_000_000_000_302L;
    private static ConnectionSupplier supplier;
    private MessageLogRepository repository;
    private MessageLogAdminApi api;
    private Guild guild;
    private Member admin;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() {
        repository = new MessageLogRepository(supplier);
        api = new MessageLogAdminApi(repository);
        guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(G);
        TextChannel log = Mockito.mock(TextChannel.class);
        Mockito.when(log.canTalk()).thenReturn(true);
        Mockito.when(log.getIdLong()).thenReturn(555L);
        Mockito.when(guild.getChannelById(GuildMessageChannel.class, 555L)).thenReturn(log);
        Mockito.when(guild.getGuildChannelById(600L)).thenReturn(Mockito.mock(GuildChannel.class));
        admin = Mockito.mock(Member.class);
        Mockito.when(admin.getId()).thenReturn("1");
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"message_log_config", "message_log_cache"}) {
            try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, G);
                s.setLong(2, OTHER);
                s.executeUpdate();
            }
        }
    }

    private DataObject put(String json) {
        return api.route(guild, admin, "PUT", new String[]{"message-logs"}, () -> DataObject.fromJson(json));
    }

    @Test
    void isOffByDefaultAndNeedsAChannelToSwitchOn() {
        DataObject fresh = api.route(guild, admin, "GET", new String[]{"message-logs"}, DataObject::empty);
        assertFalse(fresh.getBoolean("enabled"));
        assertEquals(7, fresh.getInt("retentionDays"));
        ApiError error = assertThrows(ApiError.class, () -> put("{\"enabled\":true}"));
        assertEquals(400, error.status);
        DataObject saved = put("{\"enabled\":true,\"retentionDays\":14,\"editChannelId\":\"555\",\"excludedChannels\":[\"600\"]}");
        assertTrue(saved.getBoolean("enabled"));
        assertEquals(14, saved.getInt("retentionDays"));
        assertEquals("555", saved.getString("editChannelId"));
        assertThrows(ApiError.class, () -> put("{\"retentionDays\":90}"));
    }

    @Test
    void purgeRemovesOldTextAndTextOfServersWithTheFeatureOff() throws Exception {
        repository.saveConfig(new MessageLogConfig(G, true, 7, Set.of(), 555L, null, null));
        repository.put(new Cached(9_000_000_001L, G, 1, 2, "fresh", ""));
        repository.put(new Cached(9_000_000_002L, G, 1, 2, "old", ""));
        repository.put(new Cached(9_000_000_003L, OTHER, 1, 2, "no settings at all", ""));
        try (Connection c = supplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.message_log_cache SET created_at = NOW() - INTERVAL '8 days' WHERE message_id = 9000000002")) {
            s.executeUpdate();
        }
        repository.purge();
        assertTrue(repository.get(9_000_000_001L).isPresent(), "inside the 7 days");
        assertTrue(repository.get(9_000_000_002L).isEmpty(), "older than the retention");
        assertTrue(repository.get(9_000_000_003L).isEmpty(), "a server that never switched it on keeps nothing");
    }

    @Test
    void switchingOffForgetsTheTextAndClearRemovesEverything() {
        put("{\"enabled\":true,\"deleteChannelId\":\"555\"}");
        repository.put(new Cached(9_000_000_011L, G, 1, 2, "hello", "a.png: https://x/a.png"));
        assertEquals(1, repository.countFor(G));
        put("{\"enabled\":false}");
        assertEquals(0, repository.countFor(G), "switching it off removes what was kept");
        put("{\"enabled\":true,\"deleteChannelId\":\"555\"}");
        repository.put(new Cached(9_000_000_012L, G, 1, 2, "again", ""));
        api.route(guild, admin, "POST", new String[]{"message-logs", "clear"}, DataObject::empty);
        assertEquals(0, repository.countFor(G));
    }

    @Test
    void takeReturnsWhatWasRememberedAndForgetsIt() {
        repository.put(new Cached(9_000_000_021L, G, 1, 2, "one", ""));
        List<Cached> found = repository.take(List.of(9_000_000_021L, 9_000_000_022L));
        assertEquals(1, found.size());
        assertTrue(repository.get(9_000_000_021L).isEmpty());
    }

    @Test
    void entriesNameTheAuthorAndClipLongText() {
        var embed = MessageLogMessage.edited(2, 1, 3, "7", "x".repeat(3000), "after", "");
        assertEquals("Message edited", embed.getTitle());
        assertTrue(embed.getFields().stream().allMatch(f -> f.getValue().length() <= 1024));
        assertTrue(MessageLogMessage.voice(5, null, 8L).getDescription().contains("joined"));
        assertTrue(MessageLogMessage.voice(5, 8L, null).getDescription().contains("left"));
        assertTrue(MessageLogMessage.voice(5, 8L, 9L).getDescription().contains("moved"));
    }
}
