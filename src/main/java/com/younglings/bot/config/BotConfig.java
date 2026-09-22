package com.younglings.bot.config;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Activity;

import java.util.List;
import java.util.Properties;

public class BotConfig {
    private static final BotConfig INSTANCE = new BotConfig();

    private final Dotenv dotenv;
    private final Properties properties;

    private BotConfig() {
        this.dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();

        this.properties = new Properties();

        try {
            var inputStream = BotConfig.class.getClassLoader()
                    .getResourceAsStream("config.properties");

            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config.properties", e);
        }
    }

    public boolean getLiveEnvironment() {
        String isLive = System.getenv("LIVE_ENV");

        if (isLive == null || isLive.isBlank()) {
            isLive = dotenv.get("LIVE_ENV");
        }

        if (isLive == null || isLive.isBlank()) {
            throw new IllegalStateException(
                    "Missing LIVE_ENV. Add it to environment variables or .env file."
            );
        }

        return Boolean.parseBoolean(isLive);
    }

    public String getToken() {
        String token = System.getenv("DISCORD_TOKEN");

        if (token == null || token.isBlank()) {
            token = dotenv.get("DISCORD_TOKEN");
        }

        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Missing DISCORD_TOKEN. Add it to environment variables or .env file."
            );
        }

        return token;
    }

    public String getDatabaseUrl() {
        String databaseUrl = System.getenv("DATABASE_URL");

        if (databaseUrl == null || databaseUrl.isBlank()) {
            databaseUrl = dotenv.get("DATABASE_URL");
        }

        if (databaseUrl == null || databaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "Missing DATABASE_URL. Add it to environment variables or .env file."
            );
        }

        return databaseUrl;
    }

    public Activity getActivity() {
        return Activity.customStatus(properties.getProperty("bot.activity", "Online"));
    }

    /**
     * The Younglings guild ID, needed by the internal API (see {@code com.younglings.bot.internal})
     * to know which guild to read online members / scheduled events from. Returns {@code null} if
     * unset — callers that need it should treat that as "internal API not configured".
     */
    public Long getGuildId() {
        String raw = System.getenv("GUILD_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("GUILD_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * Shared secret the internal API requires on every request (via the {@code X-Internal-Secret}
     * header). Returns {@code null} if unset, in which case the internal API server does not start
     * at all — it's opt-in, not required for the bot's core functionality.
     */
    public String getInternalApiSecret() {
        String secret = System.getenv("INTERNAL_API_SECRET");

        if (secret == null || secret.isBlank()) {
            secret = dotenv.get("INTERNAL_API_SECRET");
        }

        return (secret == null || secret.isBlank()) ? null : secret;
    }

    /** Port the internal API listens on. Defaults to 8081 if unset. */
    public int getInternalApiPort() {
        String raw = System.getenv("INTERNAL_API_PORT");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("INTERNAL_API_PORT");
        }

        if (raw == null || raw.isBlank()) {
            return 8081;
        }

        return Integer.parseInt(raw.trim());
    }

    /**
     * Whether to remove every guild slash command on shutdown, so they don't linger in Discord's
     * command picker between dev runs. Defaults to {@code false} (safe default: if unset, or set
     * to anything that isn't exactly "true", commands are left alone) — for a final run before
     * stepping away, set this to {@code true} for a clean sweep on the way out.
     * <p>
     * Only ever takes effect outside of production ({@code !getLiveEnvironment()}), regardless of
     * this value — see {@code Bot#registerCommandCleanupShutdownHook}.
     */
    public boolean getRemoveCommandsOnShutdown() {
        String raw = System.getenv("REMOVE_COMMANDS_ON_SHUTDOWN");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("REMOVE_COMMANDS_ON_SHUTDOWN");
        }

        return Boolean.parseBoolean(raw); // null/blank/anything but "true" (case-insensitive) -> false
    }

    /**
     * Whether this run should register the guild-command-cleanup shutdown hook (see
     * {@code Bot#registerCommandCleanupShutdownHook}) that removes guild commands on JVM
     * shutdown. That hook sends a plain HTTPS request independent of JDA/BotCommands, so unlike
     * an earlier attempt, nothing here needs to touch either framework's own built-in shutdown
     * hook — both stay enabled, exactly as they ship by default.
     * <p>
     * True only outside of production, with {@link #getRemoveCommandsOnShutdown()} enabled and a
     * {@link #getGuildId()} set — a pure check with no side effects.
     */
    public boolean shouldManageOwnShutdown() {
        return !getLiveEnvironment() && getRemoveCommandsOnShutdown() && getGuildId() != null;
    }

    public List<Long> getOwnerIds() {
        String rawOwnerIds = System.getenv("OWNER_IDS");

        if (rawOwnerIds == null || rawOwnerIds.isBlank()) {
            rawOwnerIds = dotenv.get("OWNER_IDS");
        }

        if (rawOwnerIds == null || rawOwnerIds.isBlank()) {
            return List.of();
        }

        return List.of(rawOwnerIds.split(","))
                .stream()
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .map(Long::parseLong)
                .toList();
    }

    @BService
    public static BotConfig getInstance() {
        return INSTANCE;
    }
}
