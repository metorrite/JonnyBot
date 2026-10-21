package com.younglings.bot.rsnlink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Creates and upgrades the link database's schema (LINK-SYSTEM.md section 6). Each entry of {@link #MIGRATIONS} runs once, in order,
 * inside a transaction, and is recorded in {@code schema_version}; a new change is a new entry at the end, never an edit of an old one.
 * Statements use {@code IF NOT EXISTS} as well, the house pattern, so a half-finished manual setup can't break a start.
 * <p>
 * Differences from the spec's table names: {@code character} is a Postgres type keyword, so the table is {@code game_character}; and
 * {@code block} is {@code link_block}. {@code dispute} is new (the 2026-10-20 research: two tracking ids claiming one name are frozen for
 * review instead of the 30-day rule locking the newcomer out).
 */
public final class LinkSchema {
    private static final Logger log = LoggerFactory.getLogger(LinkSchema.class);

    /** {@code %1$s} is the schema name. */
    static final List<String> MIGRATIONS = List.of(
            // v1: everything in section 6, plus disputes
            """
            CREATE TABLE IF NOT EXISTS %1$s.discord_user (
                id uuid PRIMARY KEY,
                discord_lookup bytea NOT NULL UNIQUE,
                discord_enc bytea NOT NULL,
                key_version smallint NOT NULL,
                name_enc bytea,
                dm_optout_until timestamptz,
                created_at timestamptz NOT NULL
            );
            CREATE TABLE IF NOT EXISTS %1$s.game_character (
                id uuid PRIMARY KEY,
                tracking_lookup bytea UNIQUE,
                rsn_display text NOT NULL,
                rsn_lookup text NOT NULL,
                last_seen_at timestamptz NOT NULL,
                created_at timestamptz NOT NULL
            );
            CREATE INDEX IF NOT EXISTS game_character_rsn ON %1$s.game_character (rsn_lookup);
            CREATE TABLE IF NOT EXISTS %1$s.link (
                id uuid PRIMARY KEY,
                character_id uuid NOT NULL REFERENCES %1$s.game_character (id) ON DELETE CASCADE,
                discord_user_id uuid NOT NULL REFERENCES %1$s.discord_user (id) ON DELETE CASCADE,
                status text NOT NULL,
                method text NOT NULL,
                verification text NOT NULL DEFAULT 'implicit',
                verification_data jsonb,
                verified_at timestamptz,
                created_at timestamptz NOT NULL,
                ended_at timestamptz
            );
            CREATE UNIQUE INDEX IF NOT EXISTS link_one_active ON %1$s.link (character_id) WHERE status = 'active';
            CREATE INDEX IF NOT EXISTS link_discord ON %1$s.link (discord_user_id);
            CREATE TABLE IF NOT EXISTS %1$s.link_token (
                id uuid PRIMARY KEY,
                link_id uuid NOT NULL REFERENCES %1$s.link (id) ON DELETE CASCADE,
                token_hash bytea NOT NULL UNIQUE,
                client text,
                created_at timestamptz NOT NULL,
                last_used_at timestamptz,
                revoked_at timestamptz
            );
            CREATE INDEX IF NOT EXISTS link_token_link ON %1$s.link_token (link_id);
            CREATE TABLE IF NOT EXISTS %1$s.pairing (
                id uuid PRIMARY KEY,
                method text NOT NULL,
                code_lookup bytea NOT NULL,
                device_secret_hash bytea NOT NULL,
                rsn_display text NOT NULL,
                rsn_lookup text NOT NULL,
                tracking_lookup bytea,
                target_discord_lookup bytea,
                discord_user_id uuid REFERENCES %1$s.discord_user (id) ON DELETE CASCADE,
                browser_session_hash bytea,
                status text NOT NULL,
                outcome text,
                attempts smallint NOT NULL DEFAULT 0,
                poll_interval integer NOT NULL,
                last_poll_at timestamptz,
                link_id uuid REFERENCES %1$s.link (id) ON DELETE SET NULL,
                client text,
                ip_lookup bytea,
                created_at timestamptz NOT NULL,
                expires_at timestamptz NOT NULL,
                ended_at timestamptz
            );
            CREATE UNIQUE INDEX IF NOT EXISTS pairing_code_pending ON %1$s.pairing (code_lookup) WHERE status = 'pending';
            CREATE TABLE IF NOT EXISTS %1$s.rsn_history (
                character_id uuid NOT NULL REFERENCES %1$s.game_character (id) ON DELETE CASCADE,
                rsn_display text NOT NULL,
                seen_from timestamptz NOT NULL,
                seen_to timestamptz NOT NULL
            );
            CREATE INDEX IF NOT EXISTS rsn_history_character ON %1$s.rsn_history (character_id);
            CREATE TABLE IF NOT EXISTS %1$s.abuse_event (
                id bigserial PRIMARY KEY,
                at timestamptz NOT NULL,
                kind text NOT NULL,
                subject_kind text NOT NULL,
                subject_lookup bytea,
                detail jsonb
            );
            CREATE INDEX IF NOT EXISTS abuse_event_subject ON %1$s.abuse_event (subject_kind, subject_lookup, at);
            CREATE INDEX IF NOT EXISTS abuse_event_at ON %1$s.abuse_event (at);
            CREATE TABLE IF NOT EXISTS %1$s.link_block (
                id bigserial PRIMARY KEY,
                subject_kind text NOT NULL,
                subject_lookup bytea NOT NULL,
                reason text NOT NULL,
                until timestamptz NOT NULL,
                created_by text NOT NULL,
                created_at timestamptz NOT NULL
            );
            CREATE INDEX IF NOT EXISTS link_block_subject ON %1$s.link_block (subject_kind, subject_lookup, until);
            CREATE TABLE IF NOT EXISTS %1$s.admin_audit (
                id bigserial PRIMARY KEY,
                at timestamptz NOT NULL,
                actor text NOT NULL,
                action text NOT NULL,
                target text
            );
            CREATE TABLE IF NOT EXISTS %1$s.dispute (
                id uuid PRIMARY KEY,
                rsn_lookup text NOT NULL,
                rsn_display text NOT NULL,
                incumbent_link_id uuid REFERENCES %1$s.link (id) ON DELETE SET NULL,
                claimant_link_id uuid REFERENCES %1$s.link (id) ON DELETE SET NULL,
                status text NOT NULL,
                opened_at timestamptz NOT NULL,
                resolved_at timestamptz,
                resolved_by text,
                note text
            );
            CREATE INDEX IF NOT EXISTS dispute_open ON %1$s.dispute (status, opened_at);
            """
    );

    private LinkSchema() {}

    /** Brings {@code schema} up to the latest version. */
    public static void migrate(Connection connection, String schema) throws SQLException {
        if (!schema.matches("[a-z_][a-z0-9_]{0,40}")) throw new IllegalArgumentException("Bad schema name");
        boolean autoCommit = connection.getAutoCommit();
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            s.execute("CREATE TABLE IF NOT EXISTS " + schema + ".schema_version (version integer PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
        }
        int current = 0;
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT coalesce(max(version), 0) FROM " + schema + ".schema_version")) {
            if (rs.next()) current = rs.getInt(1);
        }
        for (int v = current + 1; v <= MIGRATIONS.size(); v++) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement();
                 PreparedStatement mark = connection.prepareStatement("INSERT INTO " + schema + ".schema_version (version) VALUES (?)")) {
                s.execute(MIGRATIONS.get(v - 1).formatted(schema));
                mark.setInt(1, v);
                mark.executeUpdate();
                connection.commit();
                log.info("Link database: schema {} is now at version {}", schema, v);
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public static int latestVersion() {
        return MIGRATIONS.size();
    }
}
