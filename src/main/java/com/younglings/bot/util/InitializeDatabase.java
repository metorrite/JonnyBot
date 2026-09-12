package com.younglings.bot.util;

import com.younglings.bot.coffer.CofferDatabaseInitializer;
import com.younglings.bot.config.BotConfig;
import com.younglings.bot.database.DatabaseSource;
import com.younglings.bot.poll.PollDatabaseInitializer;
import com.younglings.bot.signup.SignupDatabaseInitializer;

/**
 * Standalone entry point to set up the database schema without starting the bot itself — e.g. to
 * provision a fresh database ahead of first boot. The bot also runs all three initializers
 * automatically on startup ({@code @BService} construction order), so running this manually is
 * optional; it exists for cases where you want schema setup as a separate, explicit step.
 */
public class InitializeDatabase {
    public static void main(String[] args) {
        BotConfig config = BotConfig.getInstance();

        DatabaseSource databaseSource = new DatabaseSource(config);

        new SignupDatabaseInitializer(databaseSource);
        new PollDatabaseInitializer(databaseSource);
        new CofferDatabaseInitializer(databaseSource);

        System.out.println("Database initialized successfully.");
    }
}
