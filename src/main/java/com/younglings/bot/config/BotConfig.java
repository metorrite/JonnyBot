package com.younglings.bot.config;

import java.util.Properties;

public class BotConfig {
    private static final Properties properties = new Properties();

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
        String env = System.getenv("DISCORD_TOKEN");
        if (env != null && !env.isBlank()) return env;

        return properties.getProperty("discord.token");
    }

    public static String getActivity() {
        return properties.getProperty("bot.activity", "Online");
    }
}
