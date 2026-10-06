package com.younglings.bot.ticket;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The ticket system's tables. Everything a ticket needs to keep working across a restart lives here —
 * panel configuration, every ticket and its helpers, saved transcripts — so nothing about a ticket is held
 * only in memory. Every row is keyed by {@code guild_id} so a second server is a configuration matter, not a
 * schema change, even though the bot is linked to just one server today.
 */
@BService
public class TicketDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(TicketDatabaseInitializer.class);

    public TicketDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "ticket", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                // One row per server: where transcripts are logged, the running ticket counter, retention.
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_settings (
                    guild_id BIGINT PRIMARY KEY,
                    log_channel_id BIGINT NULL,
                    next_number INTEGER NOT NULL DEFAULT 1,
                    transcript_dm BOOLEAN NOT NULL DEFAULT TRUE,
                    close_delay_seconds INTEGER NOT NULL DEFAULT 10,
                    transcript_retention_days INTEGER NULL
                );
                """,

                // A panel is one kind of ticket someone can open (e.g. "Combat Achievement help").
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_panel (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT 'Open a ticket',
                    description TEXT NOT NULL DEFAULT '',
                    button_label TEXT NOT NULL DEFAULT 'Open a Ticket',
                    category_id BIGINT NULL,
                    channel_name_template TEXT NOT NULL DEFAULT 'ticket-{number}',
                    welcome_text TEXT NOT NULL DEFAULT '',
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    per_user_limit INTEGER NOT NULL DEFAULT 1,
                    default_ping_role_id BIGINT NULL,
                    helper_cap INTEGER NULL,
                    escalation_hours INTEGER NULL,
                    default_escalate_role_id BIGINT NULL,
                    posted_channel_id BIGINT NULL,
                    posted_message_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS ticket_panel_name_idx ON younglings.ticket_panel (guild_id, LOWER(name));",

                // The questions on a panel's form, in order (a Discord form holds at most 5).
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_panel_field (
                    id BIGSERIAL PRIMARY KEY,
                    panel_id BIGINT NOT NULL REFERENCES younglings.ticket_panel(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    required BOOLEAN NOT NULL DEFAULT TRUE,
                    placeholder TEXT NULL,
                    max_length INTEGER NULL
                );
                """,

                // A dropdown question's choices; a choice can carry the role to ping and the role to escalate to.
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_panel_option (
                    id BIGSERIAL PRIMARY KEY,
                    field_id BIGINT NOT NULL REFERENCES younglings.ticket_panel_field(id) ON DELETE CASCADE,
                    position INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    ping_role_id BIGINT NULL,
                    escalate_role_id BIGINT NULL
                );
                """,

                // HELPER roles can see every ticket on the panel and join as helpers (up to the cap);
                // STAFF roles can also close any ticket and are exempt from the cap.
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_panel_role (
                    panel_id BIGINT NOT NULL REFERENCES younglings.ticket_panel(id) ON DELETE CASCADE,
                    role_id BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    PRIMARY KEY (panel_id, role_id)
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.ticket (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    panel_id BIGINT NULL REFERENCES younglings.ticket_panel(id) ON DELETE SET NULL,
                    number INTEGER NOT NULL,
                    channel_id BIGINT NULL,
                    requester_id BIGINT NOT NULL,
                    status TEXT NOT NULL DEFAULT 'OPEN',
                    routing_option_id BIGINT NULL,
                    routing_label TEXT NULL,
                    answers TEXT NOT NULL DEFAULT '[]',
                    welcome_message_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    escalated_at TIMESTAMPTZ NULL,
                    closed_at TIMESTAMPTZ NULL,
                    closed_by BIGINT NULL,
                    close_reason TEXT NULL,
                    channel_deleted BOOLEAN NOT NULL DEFAULT FALSE
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS ticket_channel_idx ON younglings.ticket (channel_id) WHERE channel_id IS NOT NULL;",
                "CREATE INDEX IF NOT EXISTS ticket_guild_status_idx ON younglings.ticket (guild_id, status);",

                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_helper (
                    ticket_id BIGINT NOT NULL REFERENCES younglings.ticket(id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (ticket_id, user_id)
                );
                """,

                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_transcript (
                    ticket_id BIGINT PRIMARY KEY REFERENCES younglings.ticket(id) ON DELETE CASCADE,
                    content TEXT NOT NULL,
                    message_count INTEGER NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                // A requester's "that didn't go well" note on a closed ticket — for admins only.
                """
                CREATE TABLE IF NOT EXISTS younglings.ticket_flag (
                    id BIGSERIAL PRIMARY KEY,
                    ticket_id BIGINT NOT NULL REFERENCES younglings.ticket(id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    note TEXT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                // The line posted above a new ticket's embeds; {user} is replaced with a mention of whoever opened it.
                "ALTER TABLE younglings.ticket_panel ADD COLUMN IF NOT EXISTS opening_message TEXT NOT NULL DEFAULT '{user} Welcome';"
        ));
    }
}
