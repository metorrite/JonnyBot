package com.younglings.bot.internal;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small set of self-assignable "name color" roles offered on the website's profile page.
 * Purely cosmetic — Discord doesn't let a member pick their own name color directly, only their
 * highest-positioned colored role controls that, so this mirrors what many Discord communities do
 * with a bot-managed set of color roles instead. Picking a new color replaces whichever one you
 * already had, since holding more than one is pointless (only the top one shows).
 * <p>
 * To add/remove/recolor an option, edit {@link #COLORS} only.
 */
public final class ColorRoleCatalog {

    /**
     * Every role this catalog owns is named "{@value}&lt;key&gt;" — e.g. "Color: Red" — so
     * {@link #isColorRoleName} can recognize and clear an existing one without needing to
     * enumerate every possible name.
     */
    public static final String ROLE_PREFIX = "Color: ";

    private static final Map<String, Color> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put("Red", new Color(0xE7, 0x4C, 0x3C));
        COLORS.put("Orange", new Color(0xE6, 0x7E, 0x22));
        COLORS.put("Yellow", new Color(0xF1, 0xC4, 0x0F));
        COLORS.put("Green", new Color(0x2E, 0xCC, 0x71));
        COLORS.put("Teal", new Color(0x1A, 0xBC, 0x9C));
        COLORS.put("Blue", new Color(0x34, 0x98, 0xDB));
        COLORS.put("Purple", new Color(0x9B, 0x59, 0xB6));
        COLORS.put("Pink", new Color(0xE9, 0x1E, 0x8C));
    }

    private ColorRoleCatalog() {}

    public static boolean isValidKey(String colorKey) {
        return COLORS.containsKey(colorKey);
    }

    public static Color colorFor(String colorKey) {
        return COLORS.get(colorKey);
    }

    public static String roleName(String colorKey) {
        return ROLE_PREFIX + colorKey;
    }

    public static boolean isColorRoleName(String roleName) {
        return roleName != null && roleName.startsWith(ROLE_PREFIX);
    }

    /** The color key (e.g. "Red") from one of this catalog's role names, or null if it isn't one. */
    public static String keyFromRoleName(String roleName) {
        if (!isColorRoleName(roleName)) return null;
        String key = roleName.substring(ROLE_PREFIX.length());
        return isValidKey(key) ? key : null;
    }
}
