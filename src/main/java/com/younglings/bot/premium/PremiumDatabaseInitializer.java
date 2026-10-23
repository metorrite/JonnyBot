package com.younglings.bot.premium;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class PremiumDatabaseInitializer {
    private static final Logger log = LoggerFactory.getLogger(PremiumDatabaseInitializer.class);

    public PremiumDatabaseInitializer(ConnectionSupplier connectionSupplier) {
        SchemaBootstrapper.run(connectionSupplier, log, "premium", List.of(
                """
                CREATE SCHEMA IF NOT EXISTS younglings;
                """,

                // The servers on a plan above the free one: their clan's players are refreshed more often than a standard server's, and so on
                // (see PremiumFeatures). Bot-wide, set by the bot's owner. The label is only a reminder of whose server it is.
                """
                CREATE TABLE IF NOT EXISTS younglings.premium_guild (
                    guild_id BIGINT PRIMARY KEY,
                    label TEXT NOT NULL DEFAULT '',
                    added_by BIGINT NULL,
                    added_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                // Which plan, until when, and why. Every row that existed before these columns is the 'premium' tier with no end, so nothing changes.
                "ALTER TABLE younglings.premium_guild ADD COLUMN IF NOT EXISTS tier TEXT NOT NULL DEFAULT 'premium';",
                "ALTER TABLE younglings.premium_guild ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;",
                "ALTER TABLE younglings.premium_guild ADD COLUMN IF NOT EXISTS note TEXT NULL;",
                "ALTER TABLE younglings.premium_guild ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'manual';",

                // Who granted, changed, revoked or let a plan run out, and when. Kept after the grant itself is gone.
                """
                CREATE TABLE IF NOT EXISTS younglings.premium_grant_log (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    action TEXT NOT NULL,
                    tier TEXT NULL,
                    expires_at TIMESTAMPTZ NULL,
                    by_user BIGINT NULL,
                    note TEXT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,
                "CREATE INDEX IF NOT EXISTS premium_grant_log_guild_idx ON younglings.premium_grant_log (guild_id, at DESC);",

                // A server's paid subscription as the website last forwarded it (BillingRules, T-366): the newest Stripe event wins, so
                // event_created / event_id say which event this state came from. Ids are Stripe's own (cus_..., sub_...), never card details.
                """
                CREATE TABLE IF NOT EXISTS younglings.premium_subscription (
                    guild_id BIGINT PRIMARY KEY,
                    customer_id TEXT NOT NULL,
                    subscription_id TEXT NOT NULL,
                    status TEXT NOT NULL,
                    current_period_end TIMESTAMPTZ NULL,
                    cancel_at_period_end BOOLEAN NOT NULL DEFAULT FALSE,
                    event_created TIMESTAMPTZ NOT NULL,
                    event_id TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,

                // Every billing event the bot has taken, so a replay (Stripe retries, the website retries) is answered without doing anything twice.
                """
                CREATE TABLE IF NOT EXISTS younglings.billing_event (
                    event_id TEXT PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    outcome TEXT NOT NULL,
                    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """
        ));
    }
}
