package com.younglings.bot.announcement;

/**
 * A named "paste text, post/update it as the bot in one or more channels" slot. {@link #RULES}
 * replaces the old single-channel Rules panel with the same idea generalized to any number of
 * destinations; {@link #RANKS} and {@link #VERIFICATION} are the same mechanism for two other
 * texts admins keep re-posting; {@link #CUSTOM} is the free-form slot for anything that doesn't
 * deserve its own preset.
 * <p>
 * Fixed in code rather than admin-defined, same reasoning as {@link com.younglings.bot.tracking.TrackingGroup}
 * — a small known set is simpler to build panels for than an arbitrary admin-managed list, and a
 * fifth named preset is one enum value away if it's ever needed.
 */
public enum AnnouncementPreset {
    RULES("Rules"),
    RANKS("Ranks"),
    VERIFICATION("Verification"),
    CUSTOM("Custom Announcement");

    private final String displayName;

    AnnouncementPreset(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
