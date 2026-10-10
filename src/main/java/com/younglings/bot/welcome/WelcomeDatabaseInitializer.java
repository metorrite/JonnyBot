package com.younglings.bot.welcome;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class WelcomeDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(WelcomeDatabaseInitializer.class);

    public WelcomeDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "welcome", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // One row per server. A missing row means the welcome has never been set up, which reads as the
                // defaults with the feature off. Embed fields are kept as a JSON array in one column: they are
                // only ever read and written together with the rest of the message.
                """
                CREATE TABLE IF NOT EXISTS younglings.welcome_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    message_type TEXT NOT NULL DEFAULT 'EMBED_TEXT',
                    channel_id BIGINT NULL,
                    also_dm BOOLEAN NOT NULL DEFAULT FALSE,
                    content TEXT NOT NULL DEFAULT '',
                    embed_color INTEGER NULL,
                    embed_title TEXT NOT NULL DEFAULT '',
                    embed_title_url TEXT NOT NULL DEFAULT '',
                    embed_description TEXT NOT NULL DEFAULT '',
                    author_name TEXT NOT NULL DEFAULT '',
                    author_icon_url TEXT NOT NULL DEFAULT '',
                    thumbnail_url TEXT NOT NULL DEFAULT '',
                    image_url TEXT NOT NULL DEFAULT '',
                    footer_text TEXT NOT NULL DEFAULT '',
                    footer_icon_url TEXT NOT NULL DEFAULT '',
                    fields_json TEXT NOT NULL DEFAULT '[]',
                    link_button BOOLEAN NOT NULL DEFAULT FALSE,
                    link_button_label TEXT NOT NULL DEFAULT 'Link your RuneScape name',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,

                // The link button's colour: primary, secondary, success or danger.
                "ALTER TABLE younglings.welcome_config ADD COLUMN IF NOT EXISTS link_button_style TEXT NOT NULL DEFAULT 'primary';",

                // How a container shows its footer: small, normal or bold.
                "ALTER TABLE younglings.welcome_config ADD COLUMN IF NOT EXISTS footer_style TEXT NOT NULL DEFAULT 'small';"
        ));
    }
}
