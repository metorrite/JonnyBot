package com.runeclans.bot.ban;

import com.runeclans.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class BanAnnouncementDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(BanAnnouncementDatabaseInitializer.class);

    public BanAnnouncementDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "ban announcement", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",

                // One row per server: the welcome's message columns without the DM copy and the link button (they mean nothing for a ban).
                // A missing row reads as the defaults with the feature off.
                """
                CREATE TABLE IF NOT EXISTS younglings.ban_announcement_config (
                    guild_id BIGINT PRIMARY KEY,
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    message_type TEXT NOT NULL DEFAULT 'EMBED',
                    channel_id BIGINT NULL,
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
                    footer_style TEXT NOT NULL DEFAULT 'small',
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """
        ));
    }
}
