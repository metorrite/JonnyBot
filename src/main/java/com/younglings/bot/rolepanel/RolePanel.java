package com.younglings.bot.rolepanel;

import java.util.List;

/**
 * A message of buttons members press to take or drop a role (what Dyno calls reaction roles). {@code TOGGLE} lets a member hold any
 * number of the panel's roles; {@code UNIQUE} lets them hold only one, so choosing another swaps it, like picking a team.
 */
public record RolePanel(Long id, long guildId, String name, String title, String description, Integer color, Mode mode,
                        Long channelId, Long messageId, List<RoleButton> buttons) {
    public static final int MAX_BUTTONS = 20;
    public static final int MAX_PANELS = 25;

    public enum Mode { TOGGLE, UNIQUE }

    /** One button: the role it gives, the words on it, an optional emoji, and its colour (primary, secondary, success or danger). */
    public record RoleButton(long roleId, String label, String emoji, String style) {}

    public boolean posted() {
        return channelId != null && messageId != null;
    }
}
