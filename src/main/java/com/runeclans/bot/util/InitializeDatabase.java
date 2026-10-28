package com.runeclans.bot.util;

import com.runeclans.bot.coffer.CofferDatabaseInitializer;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.database.DatabaseSource;
import com.runeclans.bot.embed.EmbedDatabaseInitializer;
import com.runeclans.bot.poll.PollDatabaseInitializer;
import com.runeclans.bot.runescape.RuneScapeDatabaseInitializer;
import com.runeclans.bot.signup.SignupDatabaseInitializer;

/**
 * Standalone entry point to set up the database schema without starting the bot itself — e.g. to
 * provision a fresh database ahead of first boot. The bot also runs every initializer automatically
 * on startup ({@code @BService} construction order), so running this manually is optional; it
 * exists for cases where you want schema setup as a separate, explicit step.
 */
public class InitializeDatabase {
    public static void main(String[] args) {
        BotConfig config = BotConfig.getInstance();

        DatabaseSource databaseSource = new DatabaseSource(config);

        new SignupDatabaseInitializer(databaseSource);
        new PollDatabaseInitializer(databaseSource);
        new CofferDatabaseInitializer(databaseSource);
        new EmbedDatabaseInitializer(databaseSource);
        new RuneScapeDatabaseInitializer(databaseSource);

        System.out.println("Database initialized successfully.");
    }
}
