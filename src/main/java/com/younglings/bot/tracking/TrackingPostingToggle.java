package com.younglings.bot.tracking;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One in-memory switch, checked by {@link TrackingEventRouter} before it ever sends anything —
 * flipped off by {@code /dev toggleposting} (dev-only) so a locally-running JonnyBot Dev can poll,
 * classify, and write to its own database exactly like normal without actually posting to the shared
 * Discord channels, which would otherwise double up whatever the real production bot already posts
 * there. Starts <b>off</b> on a dev instance and <b>on</b> in production ({@code LIVE_ENV}), so booting
 * the dev bot can never double-post by accident; {@code /dev toggleposting} turns it on deliberately for
 * the rest of that run. It's per-process — toggling it on a dev instance has no effect on the
 * separately-running production bot.
 */
@BService
public class TrackingPostingToggle {
    private static final Logger log = LoggerFactory.getLogger(TrackingPostingToggle.class);

    private volatile boolean enabled;

    public TrackingPostingToggle(BotConfig botConfig) {
        this.enabled = botConfig.getLiveEnvironment();
        log.info("Tracking posting starts {} ({} environment){}", enabled ? "ON" : "OFF", enabled ? "live" : "dev",
                enabled ? "" : " — use /dev toggleposting to turn it on for this run");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the new state, after flipping it. */
    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
