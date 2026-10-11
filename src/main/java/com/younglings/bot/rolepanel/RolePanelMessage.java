package com.younglings.bot.rolepanel;

import com.younglings.bot.rolepanel.RolePanel.RoleButton;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/** Builds the message a role panel posts: a card with its text, and its buttons in rows of five. */
public final class RolePanelMessage {
    private RolePanelMessage() {}

    public static final String ID_PREFIX = "rr:";
    private static final int PER_ROW = 5;

    public static String buttonId(long panelId, long roleId) {
        return ID_PREFIX + panelId + ":" + roleId;
    }

    /** Splits the buttons into rows of five, which is as many as Discord allows in one. */
    public static <T> List<List<T>> rows(List<T> buttons) {
        List<List<T>> rows = new ArrayList<>();
        for (int i = 0; i < buttons.size(); i += PER_ROW) rows.add(buttons.subList(i, Math.min(i + PER_ROW, buttons.size())));
        return rows;
    }

    public static ButtonStyle style(String name) {
        return switch (name == null ? "" : name.toLowerCase()) {
            case "secondary" -> ButtonStyle.SECONDARY;
            case "success" -> ButtonStyle.SUCCESS;
            case "danger" -> ButtonStyle.DANGER;
            default -> ButtonStyle.PRIMARY;
        };
    }

    /** {@code roleName} supplies the words for a button that has no label of its own. */
    public static MessageCreateData build(RolePanel panel, LongFunction<String> roleName) {
        EmbedBuilder embed = new EmbedBuilder();
        if (!panel.title().isBlank()) embed.setTitle(panel.title());
        embed.setDescription(panel.description().isBlank() && panel.title().isBlank() ? "Press a button to take or drop a role." : panel.description().isBlank() ? null : panel.description());
        if (panel.color() != null) embed.setColor(new Color(panel.color()));

        MessageCreateBuilder message = new MessageCreateBuilder().setEmbeds(embed.build());
        List<ActionRow> actionRows = new ArrayList<>();
        for (List<RoleButton> row : rows(panel.buttons())) {
            List<Button> buttons = new ArrayList<>();
            for (RoleButton b : row) {
                String label = b.label() == null || b.label().isBlank() ? roleName.apply(b.roleId()) : b.label();
                Button button = Button.of(style(b.style()), buttonId(panel.id(), b.roleId()), label);
                Emoji emoji = emoji(b.emoji());
                buttons.add(emoji == null ? button : button.withEmoji(emoji));
            }
            actionRows.add(ActionRow.of(buttons));
        }
        if (!actionRows.isEmpty()) message.setComponents(actionRows);
        return message.build();
    }

    /** An emoji as typed (a character, or {@code <:name:id>} for a custom one), or null if it isn't one. */
    public static Emoji emoji(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Emoji.fromFormatted(raw.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
