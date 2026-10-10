package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a welcome as a container (Components V2): the same parts as an embed, laid out as blocks so a button can sit inside it.
 * <p>
 * Order: author (small grey line), title (a heading, a link if it has an address), description, the fields one under another, the
 * image, then the footer as small grey text. The thumbnail sits at the right of the first text block. Everything is ordinary markdown
 * text, so links, bold and the rest work in every part, and the {@code {rs_button}} variable puts the link button on the line where
 * it is written (the line's text sits beside the button). With no {@code {rs_button}} the button goes at the bottom.
 * <p>
 * A container has no inline fields and no author or footer icons: those are things only an embed can draw.
 */
final class ContainerWelcome {
    /** Where the link button goes. Written as a variable, but never filled in like the others: it marks a place. */
    static final String BUTTON_MARKER = "{rs_button}";

    /** Discord needs some text in every block; this blank-looking character stands in when the button is on a line of its own. */
    private static final String BLANK = "⠀";

    private ContainerWelcome() {}

    /** A text split around the line that holds the button marker. */
    record Pieces(String before, String line, String after, boolean hasButton) {}

    /** Splits {@code text} at the first line with the marker; every other marker is dropped. Without one, the text is just {@code before}. */
    static Pieces split(String text) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains(BUTTON_MARKER)) continue;
            String before = String.join("\n", List.of(lines).subList(0, i));
            String line = lines[i].replace(BUTTON_MARKER, "").strip();
            String after = String.join("\n", List.of(lines).subList(i + 1, lines.length)).replace(BUTTON_MARKER, "");
            return new Pieces(before.strip(), line, after.strip(), true);
        }
        return new Pieces(text.strip(), "", "", false);
    }

    static Container build(WelcomeConfig c, WelcomeTemplate.Lookup lookup, Button button) {
        List<ContainerChildComponent> children = new ArrayList<>();
        boolean[] buttonPlaced = {false};

        // ---- header: author, title, description, with the thumbnail beside it
        StringBuilder header = new StringBuilder();
        String author = text(c.authorName(), lookup);
        if (!author.isEmpty()) header.append("-# ").append(clip(author, 256)).append('\n');
        String title = text(c.title(), lookup);
        if (!title.isEmpty()) {
            String link = safeUrl(WelcomeTemplate.renderPlain(c.titleUrl(), lookup).strip());
            header.append("## ").append(link == null ? clip(title, 256) : "[" + clip(title, 256) + "](" + link + ")").append('\n');
        }
        String description = text(c.description(), lookup);
        if (!description.isEmpty()) header.append(clip(description, 3900));

        String thumbnail = safeUrl(WelcomeTemplate.renderPlain(c.thumbnailUrl(), lookup).strip());
        blocks(children, header.toString().strip(), button, buttonPlaced, thumbnail, NO_WRAP);

        // ---- fields: stacked, name in bold. Neighbours with no button between them share one text block.
        StringBuilder fields = new StringBuilder();
        for (EmbedField field : c.fields()) {
            String name = text(field.name(), lookup);
            String value = text(field.value(), lookup);
            if (name.isEmpty() && value.isEmpty()) continue;
            // A container needs only one of the two: a name alone is a single bold line (a link on its own, say), a value alone is plain text.
            String block = name.isEmpty() ? clip(value, 1024) : value.isEmpty() ? "**" + clip(name, 256) + "**" : "**" + clip(name, 256) + "**\n" + clip(value, 1024);
            if (block.contains(BUTTON_MARKER) && button != null && !buttonPlaced[0]) {
                blocks(children, fields.toString().strip(), null, buttonPlaced, null, NO_WRAP);
                fields.setLength(0);
                blocks(children, block, button, buttonPlaced, null, NO_WRAP);
            } else {
                if (fields.length() > 0) fields.append("\n\n");
                fields.append(block.replace(BUTTON_MARKER, ""));
            }
        }
        blocks(children, fields.toString().strip(), null, buttonPlaced, null, NO_WRAP);

        // ---- image
        String image = safeUrl(WelcomeTemplate.renderPlain(c.imageUrl(), lookup).strip());
        if (image != null) children.add(MediaGallery.of(MediaGalleryItem.fromUrl(image)));

        // ---- footer: small grey text, where the button may also go
        String footer = text(c.footerText(), lookup);
        if (!footer.isEmpty()) {
            if (!children.isEmpty()) children.add(Separator.createInvisible(Separator.Spacing.SMALL));
            blocks(children, clip(footer, 2048), button, buttonPlaced, null, footerWrap(c.footerStyle()));
        }

        if (button != null && !buttonPlaced[0]) children.add(ActionRow.of(button));

        Container container = Container.of(children);
        return c.color() == null ? container : container.withAccentColor(new Color(c.color()));
    }

    /**
     * Adds {@code text} as blocks. Where {@code text} holds the button marker (and a button is wanted and not yet placed), the line with
     * it becomes a section with the button beside it, with the text before and after as blocks of their own.
     *
     * @param thumbnail goes beside the first block, if there is one
     * @param wrap      put around every line: the footer's look (small grey, normal or bold)
     */
    private static void blocks(List<ContainerChildComponent> out, String text, Button button, boolean[] buttonPlaced, String thumbnail, Wrap wrap) {
        if (text.isBlank()) return;
        boolean wantButton = button != null && !buttonPlaced[0];
        Pieces pieces = wantButton ? split(text) : new Pieces(text.replace(BUTTON_MARKER, "").strip(), "", "", false);
        boolean[] thumbnailUsed = {thumbnail == null};

        if (!pieces.hasButton()) {
            addText(out, styled(pieces.before(), wrap), thumbnail, thumbnailUsed);
            return;
        }

        buttonPlaced[0] = true;
        if (!pieces.before().isBlank()) addText(out, styled(pieces.before(), wrap), thumbnail, thumbnailUsed);
        String line = pieces.line().isEmpty() ? BLANK : styled(pieces.line(), wrap);
        out.add(Section.of(button, TextDisplay.of(line)));
        if (!pieces.after().isBlank()) addText(out, styled(pieces.after(), wrap), null, thumbnailUsed);
        if (!thumbnailUsed[0]) addText(out, BLANK, thumbnail, thumbnailUsed); // a thumbnail with nothing before the button still needs a block to sit beside
    }

    private static void addText(List<ContainerChildComponent> out, String text, String thumbnail, boolean[] thumbnailUsed) {
        if (text.isBlank()) return;
        if (thumbnail != null && !thumbnailUsed[0]) {
            out.add(Section.of(Thumbnail.fromUrl(thumbnail), TextDisplay.of(text)));
            thumbnailUsed[0] = true;
        } else {
            out.add(TextDisplay.of(text));
        }
    }

    /** Text put before and after each line: {@code -# } for small grey text, {@code **} either side for bold. */
    record Wrap(String before, String after) {}

    static final Wrap NO_WRAP = new Wrap("", "");

    static Wrap footerWrap(String style) {
        return switch (style == null ? "small" : style) {
            case "normal" -> NO_WRAP;
            case "bold" -> new Wrap("**", "**");
            default -> new Wrap("-# ", "");
        };
    }

    private static String styled(String text, Wrap wrap) {
        if (wrap == NO_WRAP) return text;
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(wrap.before()).append(line.strip()).append(wrap.after());
        }
        return out.toString();
    }

    static Button linkButton(WelcomeConfig c, String id) {
        String label = c.linkButtonLabel().isBlank() ? "Link your RuneScape name" : c.linkButtonLabel();
        ButtonStyle style = switch (c.linkButtonStyle() == null ? "primary" : c.linkButtonStyle()) {
            case "secondary" -> ButtonStyle.SECONDARY;
            case "success" -> ButtonStyle.SUCCESS;
            case "danger" -> ButtonStyle.DANGER;
            default -> ButtonStyle.PRIMARY;
        };
        return Button.of(style, id, clip(label, 80));
    }

    /** A part's text with the variables filled in (mentions included: every block of a container draws them). */
    private static String text(String template, WelcomeTemplate.Lookup lookup) {
        return WelcomeTemplate.render(template, lookup).strip();
    }

    private static String safeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String lower = url.toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://") ? url : null;
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
