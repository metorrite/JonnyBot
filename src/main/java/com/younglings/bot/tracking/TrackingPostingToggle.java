package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * One in-memory switch, checked by {@link TrackingEventRouter} before it ever sends anything —
 * flipped off by {@code /devtoggleposting} (dev-only) so a locally-running JonnyBot Dev can poll,
 * classify, and write to its own database exactly like normal without actually posting to the shared
 * Discord channels, which would otherwise double up whatever the real production bot already posts
 * there. Defaults to {@code true} (posting enabled) and is per-process — toggling it on a dev instance
 * has no effect on the separately-running production bot.
 */
@BService
public class TrackingPostingToggle {
    private volatile boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the new state, after flipping it. */
    public boolean toggle() {
        enabled = !enabled;
        return enabled;
    }
}
