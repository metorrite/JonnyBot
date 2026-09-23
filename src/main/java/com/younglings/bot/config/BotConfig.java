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
     * ID of the "Admin" role (or equivalent) used to gate admin-tier commands — see
     * {@code com.younglings.bot.permission.AdminRoleFilter}, which treats this role and anything
     * ranked above it in the guild's role hierarchy as authorized. Returns {@code null} if unset,
     * in which case {@code AdminRoleFilter} fails closed (rejects everyone) rather than guessing.
     */
    public Long getAdminRoleId() {
        String raw = System.getenv("ADMIN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("ADMIN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * The dev/test guild ID — outside of production, commands are pushed here as guild commands
     * (near-instant sync) instead of globally, and {@code @Test}-annotated commands (e.g.
     * {@code /devsignups}) are only ever pushed here regardless of environment. Returns {@code
     * null} if unset, in which case dev falls back to global command registration and {@code
     * @Test} commands register nowhere at all.
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
     * When true, guild-scoped slash commands registered to {@link #getGuildId()} are cleared on
     * JVM shutdown — meant for the dev bot only (set via {@code REMOVE_COMMANDS_ON_SHUTDOWN}),
     * since it shares the same Discord server as production and would otherwise leave every
     * {@code @Test} command (and every ordinary command, while {@code LIVE_ENV} is false) sitting
     * on that server after the dev process exits, with no automatic cleanup. Defaults to false.
     */
    public boolean getRemoveCommandsOnShutdown() {
        String raw = System.getenv("REMOVE_COMMANDS_ON_SHUTDOWN");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("REMOVE_COMMANDS_ON_SHUTDOWN");
        }

        if (raw == null || raw.isBlank()) {
            return false;
        }

        return Boolean.parseBoolean(raw.trim());
    }

    /**
     * How often, in minutes, {@code RuneScapeStatsScheduler} re-polls every linked player's
     * profile. Defaults to 360 (6 hours) if unset — set {@code RUNESCAPE_POLL_INTERVAL_MINUTES}
     * to tune this without a code change or redeploy.
     */
    public long getRunescapePollIntervalMinutes() {
        String raw = System.getenv("RUNESCAPE_POLL_INTERVAL_MINUTES");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_POLL_INTERVAL_MINUTES");
        }

        if (raw == null || raw.isBlank()) {
            return 360;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * Delay, in seconds, between individual player polls within one sync pass — spaces out
     * requests against the RuneScape API instead of bursting them. Defaults to 2 if unset; set
     * {@code RUNESCAPE_POLL_DELAY_SECONDS} to tune this without a code change or redeploy.
     */
    public long getRunescapePollDelaySeconds() {
        String raw = System.getenv("RUNESCAPE_POLL_DELAY_SECONDS");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_POLL_DELAY_SECONDS");
        }

        if (raw == null || raw.isBlank()) {
            return 2;
        }

        return Long.parseLong(raw.trim());
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
