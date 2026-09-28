package com.younglings.bot.announcement;

import com.younglings.bot.configure.GuildSettingsDatabaseInitializer;
import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class AnnouncementDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementDatabaseInitializer.class);

    // Depends on GuildSettingsDatabaseInitializer purely to guarantee guild_settings already exists
    // before the one-time migration statement below reads/drops its rules_* columns — same
    // dependency-for-ordering trick TrackingRepository uses on TrackingDatabaseInitializer.
    public AnnouncementDatabaseInitializer(ConnectionSupplier connectionSupplier, GuildSettingsDatabaseInitializer guildSettingsInitializer) {
        SchemaBootstrapper.run(connectionSupplier, log, "announcement", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // preset_key is one of AnnouncementPreset's enum names, not a foreign key — same
                // "fixed set in code" reasoning as tracking_group_config. No row for a given
                // guild+preset means "no text set yet".
                """
                CREATE TABLE IF NOT EXISTS younglings.announcement_preset (
                    guild_id BIGINT NOT NULL,
                    preset_key TEXT NOT NULL,
                    text TEXT NULL,
                    PRIMARY KEY (guild_id, preset_key)
                );
                """,

                // One row per destination, same shape as tracking_destination — but this table also
                // remembers the posted message's id per channel, since "Post / Update" needs to edit
                // that exact message in place rather than post a fresh one every time.
                """
                CREATE TABLE IF NOT EXISTS younglings.announcement_destination (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    preset_key TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NULL,
                    UNIQUE (guild_id, preset_key, channel_id)
                );
                """,

                """
                CREATE INDEX IF NOT EXISTS announcement_destination_lookup_idx
                ON younglings.announcement_destination (guild_id, preset_key);
                """,

                // One-time migration of the old single-channel Rules columns on guild_settings into
                // the generalized tables above, then the columns are dropped. Guarded by an explicit
                // information_schema check (rather than a plain DROP COLUMN IF EXISTS at the end)
                // because this whole statement list re-runs on every boot with no migration-tracking
                // table (see SchemaBootstrapper) — without the guard, the SELECT ... FROM
                // guild_settings.rules_text would fail outright on the second boot, once the first
                // boot has already dropped that column.
                """
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'younglings' AND table_name = 'guild_settings' AND column_name = 'rules_text'
                    ) THEN
                        INSERT INTO younglings.announcement_preset (guild_id, preset_key, text)
                        SELECT guild_id, 'RULES', rules_text FROM younglings.guild_settings WHERE rules_text IS NOT NULL
                        ON CONFLICT (guild_id, preset_key) DO NOTHING;

                        INSERT INTO younglings.announcement_destination (guild_id, preset_key, channel_id, message_id)
                        SELECT guild_id, 'RULES', rules_channel_id, rules_message_id FROM younglings.guild_settings WHERE rules_channel_id IS NOT NULL
                        ON CONFLICT (guild_id, preset_key, channel_id) DO NOTHING;

                        ALTER TABLE younglings.guild_settings
                            DROP COLUMN IF EXISTS rules_channel_id,
                            DROP COLUMN IF EXISTS rules_message_id,
                            DROP COLUMN IF EXISTS rules_text;
                    END IF;
                END $$;
                """
        ));
    }
}
