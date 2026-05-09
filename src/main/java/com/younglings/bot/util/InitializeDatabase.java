package com.younglings.bot.util;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.database.DatabaseSource;
import com.younglings.bot.signup.SignupDatabaseInitializer;

public class InitializeDatabase {
    public static void main(String[] args) {
        BotConfig config = BotConfig.getInstance();

        DatabaseSource databaseSource = new DatabaseSource(config);

        new SignupDatabaseInitializer(databaseSource);

        System.out.println("Database initialized successfully.");
    }
}
