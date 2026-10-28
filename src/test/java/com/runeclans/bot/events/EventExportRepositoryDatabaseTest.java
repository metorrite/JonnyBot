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

/** T-698: an event's finished export is kept in Postgres so any bot process can show and hand it out, until it expires. */
class EventExportRepositoryDatabaseTest {
    private static final long EVENT = Long.parseLong("6".repeat(18));
    private static final long OTHER = EVENT - 1;
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
        a.save(EVENT, export("zip bytes"), EXPIRES);
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
        a.save(EVENT, export("first"), EXPIRES);
        a.save(EVENT, export("second one"), EXPIRES);
        assertEquals(10, b.summary(EVENT, MADE).size());
    }

    @Test
    void anExpiredExportIsGoneAndTheNextSaveDropsIt() throws Exception {
        a.save(OTHER, export("old"), MADE.minusSeconds(1));
        assertNull(b.get(OTHER, MADE));
        assertNull(b.summary(OTHER, MADE));
        a.save(EVENT, export("new"), EXPIRES); // saving drops every expired export
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
