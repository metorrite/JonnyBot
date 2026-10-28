package com.runeclans.bot.rolepanel;

import java.util.List;

/**
 * A message members use to take or drop a role (what Dyno calls reaction roles). Three things vary, independently:
 * <ul>
 *   <li>{@link Style}: how members choose: buttons, a drop-down, or emoji reactions (on a message of its own or an existing one).</li>
 *   <li>{@link Mode}: {@code TOGGLE} lets a member hold any number of the panel's roles; {@code UNIQUE} only one, so choosing another swaps it.</li>
 *   <li>{@link Rule}: {@code NORMAL} lets members take and drop; {@code ADD_ONLY} only take; {@code REMOVE_ONLY} only drop.</li>
 * </ul>
 * {@code attached} is a reactions panel that lives on a message somebody else wrote (found by its link): JonnyBot only adds its reactions
 * to it and never edits or deletes it.
 */
public record RolePanel(Long id, long guildId, String name, String title, String description, Integer color, Mode mode,
                        Long channelId, Long messageId, List<RoleButton> buttons, Style style, Rule rule, boolean attached) {
    public static final int MAX_BUTTONS = 20;
    public static final int MAX_PANELS = 25;
    /** Discord's limit for options in one select menu. */
    public static final int MAX_MENU_OPTIONS = 25;
    /** Discord's limit for different reactions on one message. */
    public static final int MAX_REACTIONS = 20;

    public enum Mode { TOGGLE, UNIQUE }

    public enum Style {
        BUTTONS(MAX_BUTTONS), DROPDOWN(MAX_MENU_OPTIONS), REACTIONS(MAX_REACTIONS);

        private final int maxItems;

        Style(int maxItems) {
            this.maxItems = maxItems;
        }

        /** How many roles a panel in this style can offer. */
        public int maxItems() {
            return maxItems;
        }
    }

    public enum Rule { NORMAL, ADD_ONLY, REMOVE_ONLY }

    /** One button / option / reaction: the role it gives, the words on it, an optional emoji (required for reactions), and its colour (buttons only). */
    public record RoleButton(long roleId, String label, String emoji, String style) {}

    /** A panel as they were before styles and rules: buttons, normal. */
    public RolePanel(Long id, long guildId, String name, String title, String description, Integer color, Mode mode,
                     Long channelId, Long messageId, List<RoleButton> buttons) {
        this(id, guildId, name, title, description, color, mode, channelId, messageId, buttons, Style.BUTTONS, Rule.NORMAL, false);
    }

    public boolean posted() {
        return channelId != null && messageId != null;
    }
}
