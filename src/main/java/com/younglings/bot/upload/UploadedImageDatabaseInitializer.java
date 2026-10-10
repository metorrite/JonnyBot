package com.younglings.bot.upload;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class UploadedImageDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(UploadedImageDatabaseInitializer.class);

    public UploadedImageDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "uploaded image", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // Pictures a server uploads for its own messages (a welcome embed's thumbnail, say). One row per place a picture is used,
                // so uploading again replaces it instead of piling up. They live in the database rather than on disk because the host's
                // disk is wiped on every deploy. token is what the public address names: it stays the same while the picture is replaced.
                """
                CREATE TABLE IF NOT EXISTS younglings.uploaded_image (
                    guild_id BIGINT NOT NULL,
                    scope TEXT NOT NULL,
                    slot TEXT NOT NULL,
                    token TEXT NOT NULL UNIQUE,
                    content_type TEXT NOT NULL,
                    data BYTEA NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (guild_id, scope, slot)
                );
                """
        ));
    }
}
