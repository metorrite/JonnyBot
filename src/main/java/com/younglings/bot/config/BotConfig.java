package com.younglings.bot.config;

import java.util.Properties;
import io.github.cdimascio.dotenv.Dotenv;

public class BotConfig {
    private static final Properties properties = new Properties();
    private static final Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();
    static {
        try {
            properties.load(
                    BotConfig.class.getClassLoader()
                            .getResourceAsStream("config.properties")
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config", e);
        }
    }

    public static String getToken() {
        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            token = dotenv.get("DISCORD_TOKEN");
        }

        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Missing DISCORD_TOKEN. Add it to environment variables or server .env file.");
        }

        return token;
    }

    public static String getActivity() {
        return properties.getProperty("bot.activity", "Online");
    }
}
