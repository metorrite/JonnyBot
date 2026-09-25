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
     * Fallback clan name used when a guild hasn't set its own via {@code /configure} — see
     * {@code GuildSettingsService#getEffective}. {@code null} if unset, in which case a guild with
     * no per-guild override either has no clan name at all until configured.
     */
    public String getClanName() {
        String raw = System.getenv("RUNESCAPE_CLAN_NAME");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_CLAN_NAME");
        }

        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }

    /**
     * ID of the channel {@code RsnRenameService} posts possible-rename alerts to, with Confirm/
     * Reject buttons for an admin to resolve — see {@code rsn_rename_candidate}. {@code null} if
     * unset, in which case a detected rename is logged but nobody is notified.
     */
    public Long getRenameAlertChannelId() {
        String raw = System.getenv("RSN_RENAME_ALERT_CHANNEL_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RSN_RENAME_ALERT_CHANNEL_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the channel a new {@code /rs} link request is posted to for an admin to Approve/Reject —
     * see {@code RsInteractionListener#postForAdminReview}. {@code null} if unset, in which case a
     * submitted request has nobody to notify (logged, but otherwise stuck pending until an admin
     * finds it another way, e.g. Player Lookup).
     */
    public Long getVerificationReviewChannelId() {
        String raw = System.getenv("RSN_VERIFICATION_CHANNEL_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RSN_VERIFICATION_CHANNEL_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the role granted to a verified member who <em>is</em> a current clan member — picked
     * from a dropdown of the guild's roles under {@code /configure}'s Verification section (see
     * {@code VerificationRoleSyncService}), not typed by name. {@code null} if unset, in which case
     * nothing is granted for this slot rather than guessing a role.
     */
    public Long getVerifiedClanRoleId() {
        String raw = System.getenv("VERIFIED_CLAN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("VERIFIED_CLAN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /**
     * ID of the role granted to a verified member who is <em>not</em> a current clan member.
     * Typically a role something else (e.g. a join flow) already grants — this is only a
     * supplemental assign for the case where the bot verifies someone who doesn't have it yet.
     * {@code null} if unset.
     */
    public Long getVerifiedNonClanRoleId() {
        String raw = System.getenv("VERIFIED_NON_CLAN_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("VERIFIED_NON_CLAN_ROLE_ID");
        }

        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Long.parseLong(raw.trim());
    }

    /** ID of the role removed on verification (either clan-member or not). {@code null} if unset. */
    public Long getUnverifiedRoleId() {
        String raw = System.getenv("UNVERIFIED_ROLE_ID");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("UNVERIFIED_ROLE_ID");
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
     * Shared secret the internal API (see {@code com.younglings.bot.internal.InternalApiServer})
     * requires on every request, via the {@code X-Internal-Secret} header. Returns {@code null} if
     * unset, in which case the internal API server does not start at all — it's opt-in, not
     * required for the bot's core functionality.
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
     * Whether {@code RuneScapeStatsScheduler} and {@code ClanSyncScheduler} poll automatically at
     * all. Defaults to {@code true} now that the storage format has settled — clan members poll
     * hourly, non-clan (but still linked) members every 6 hours, and the full clan roster syncs
     * once a day at 00:00 UTC. Every poll only actually writes to the database when something
     * changed (see {@code RuneScapeStatsService}). Set {@code RUNESCAPE_AUTO_POLL_ENABLED=false} to
     * go back to manual-only (the admin panel's "Update"/"Update All" buttons, or {@code /rsadmin}'s
     * Sync Clan) while iterating on something that touches the storage format again.
     */
    public boolean getRunescapeAutoPollEnabled() {
        String raw = System.getenv("RUNESCAPE_AUTO_POLL_ENABLED");

        if (raw == null || raw.isBlank()) {
            raw = dotenv.get("RUNESCAPE_AUTO_POLL_ENABLED");
        }

        if (raw == null || raw.isBlank()) {
            return true;
        }

        return Boolean.parseBoolean(raw);
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
