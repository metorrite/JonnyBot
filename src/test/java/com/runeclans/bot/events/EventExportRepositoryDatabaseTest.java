package com.runeclans.bot.events;

import com.runeclans.bot.testsupport.LocalDatabase;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-698: an event's finished export is kept in Postgres so any bot process can show and hand it out, until it expires. */
class EventExportRepositoryDatabaseTest {
    private static final long EVENT = Long.parseLong("6".repeat(18));
    private static final long OTHER = EVENT - 1;
    private static final long GUILD = Long.parseLong("5".repeat(18));
    private static final Instant MADE = Instant.parse("2026-10-28T12:00:00Z");
    private static final Instant EXPIRES = MADE.plus(Duration.ofMinutes(60));

    private ConnectionSupplier db;
    private EventExportRepository a;
    private EventExportRepository b;

    @BeforeEach
    void setUp() throws Exception {
        db = LocalDatabase.supplier();
        a = new EventExportRepository(db);
        b = new EventExportRepository(db);
        clean();
    }

    @AfterEach
    void clean() throws Exception {
        if (db == null) return;
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_export_file WHERE event_id IN (?, ?)")) {
            s.setLong(1, EVENT);
            s.setLong(2, OTHER);
            s.executeUpdate();
        }
    }

    private static EventWrapupOps.Export export(String text) {
        return new EventWrapupOps.Export("bingo.zip", text.getBytes(StandardCharsets.UTF_8), MADE, 4, 120, 3, 1);
    }

    @Test
    void anExportMadeOnOneProcessDownloadsFromAnother() {
        a.save(GUILD, EVENT, export("zip bytes"), EXPIRES);
        EventWrapupOps.Export got = b.get(EVENT, MADE.plusSeconds(5));
        assertArrayEquals("zip bytes".getBytes(StandardCharsets.UTF_8), got.data());
        assertEquals("bingo.zip", got.name());
        assertEquals(120, got.messages());
        EventExportRepository.Summary summary = b.summary(EVENT, MADE.plusSeconds(5));
        assertEquals(9, summary.size());
        assertEquals(4, summary.channels());
    }

    @Test
    void aNewExportReplacesTheOldOne() {
        a.save(GUILD, EVENT, export("first"), EXPIRES);
        a.save(GUILD, EVENT, export("second one"), EXPIRES);
        assertEquals(10, b.summary(EVENT, MADE).size());
    }

    @Test
    void theExpiryPassDeletesExpiredExportsWithoutAnotherSave() throws Exception {
        a.save(GUILD, OTHER, export("old"), Instant.now().minusSeconds(1));
        b.sweep(); // what the event-export-expiry lane runs, from start-up on
        assertEquals(0, rows(OTHER));
    }

    @Test
    void anExportOverTheCapIsRefused() throws Exception {
        byte[] big = new byte[EventExportRepository.MAX_BYTES + 1];
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> a.save(GUILD, EVENT, new EventWrapupOps.Export("big.zip", big, MADE, 1, 1, 0, 0), EXPIRES));
        assertTrue(refused.getMessage().contains("MB"));
        assertEquals(0, rows(EVENT));
    }

    @Test
    void aTableFromBeforeTheServerIdIsUpgradedInPlace() throws Exception {
        try (Connection c = db.getConnection(); var s = c.createStatement()) {
            s.execute("ALTER TABLE younglings.event_export_file DROP COLUMN guild_id");
            s.execute("INSERT INTO younglings.event_export_file (event_id, name, data, made_at, channels, messages, images, links, expires_at) VALUES ("
                    + OTHER + ", 'old.zip', '\\x00', now(), 0, 0, 0, 0, now() + interval '1 hour')");
        }
        EventExportRepository upgraded = new EventExportRepository(db);
        assertNull(upgraded.get(OTHER, MADE), "an hour's exports from before are let go");
        upgraded.save(GUILD, EVENT, export("new"), EXPIRES);
        assertEquals(3, upgraded.summary(EVENT, MADE).size());
    }

    @Test
    void retentionFindsTheExportsByServer() throws Exception {
        assertTrue(new com.runeclans.bot.retention.GuildRetentionRepository(db).serverTables().contains("event_export_file"),
                "a departed server's exports are deleted with its other data");
    }

    private int rows(long eventId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.event_export_file WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (var rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Test
    void anExpiredExportIsGoneAndTheNextSaveDropsIt() throws Exception {
        a.save(GUILD, OTHER, export("old"), MADE.minusSeconds(1));
        assertNull(b.get(OTHER, MADE));
        assertNull(b.summary(OTHER, MADE));
        a.save(GUILD, EVENT, export("new"), EXPIRES); // saving drops every expired export
        try (Connection c = db.getConnection(); PreparedStatement s = c.prepareStatement("SELECT count(*) FROM younglings.event_export_file WHERE event_id = ?")) {
            s.setLong(1, OTHER);
            try (var rs = s.executeQuery()) {
                rs.next();
                assertEquals(0, rs.getInt(1));
            }
        }
        assertNull(b.get(EVENT, EXPIRES), "at its expiry time it is gone");
    }
}
