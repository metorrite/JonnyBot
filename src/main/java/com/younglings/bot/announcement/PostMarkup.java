package com.younglings.bot.announcement;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text an admin pastes into an Embedded Post into a Components V2 container: ordinary text
 * (markdown and all) becomes text blocks, and small tags between {@code ~} marks, each on its own
 * line, become dividers, images, a side-bar color, or buttons. Text with no tags posts exactly as it
 * always did — one text block.
 * <p>
 * The tag syntax is deliberately narrow ({@code ~<...>~} for layout, {@code ~B-X_Label|action~} for
 * buttons) so ordinary markdown strikethrough ({@code ~~like this~~}) is never mistaken for one; anything
 * that merely looks tag-like but isn't a valid tag is reported rather than silently posted.
 * <p>
 * Parsing never throws: it collects {@link Problem}s. An {@link Severity#ERROR} blocks posting (the
 * result would be wrong or dead); a {@link Severity#WARNING} is posted anyway and mentioned.
 */
public final class PostMarkup {
    private PostMarkup() {}

    public enum Severity { ERROR, WARNING }

    public record Problem(Severity severity, int line, String message) {}

    /** What a button's {@code action} can be. Add an entry here and handle it in {@code PostActionListener} to teach the buttons a new trick. */
    public static final List<String> ACTIONS = List.of("rs", "citadel");

    /** Discord's own limits for one container message. */
    static final int MAX_TEXT_CHARS = 4000;
    static final int MAX_COMPONENTS = 40;
    static final int MAX_BUTTONS_PER_ROW = 5;
    static final int MAX_BUTTON_LABEL = 80;

    /** Custom id prefix of every action button; {@code postbtn:<n>:<action>} — {@code n} keeps ids unique within one message. */
    public static final String BUTTON_ID_PREFIX = "postbtn:";

    // A tag is "~<...>~" or "~B-X_..~", with exactly one tilde each side — "~~strikethrough~~" has two and never matches.
    private static final Pattern TAG = Pattern.compile("(?<!~)~(<[^~<>\\n]+>|B-[A-Za-z]_[^~\\n]+)~(?!~)");
    private static final Pattern HEX = Pattern.compile("#?([0-9A-Fa-f]{6})");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    public record Parsed(List<ContainerChildComponent> children, Color accent, List<Problem> problems) {
        public boolean hasErrors() {
            return problems.stream().anyMatch(p -> p.severity() == Severity.ERROR);
        }

        public List<Problem> errors() {
            return problems.stream().filter(p -> p.severity() == Severity.ERROR).toList();
        }

        public Container toContainer(Color defaultAccent) {
            return card(accent != null ? accent : defaultAccent, children);
        }

        /** One line per problem, ready to show an admin. */
        public String problemsText() {
            StringBuilder out = new StringBuilder();
            for (Problem p : problems) {
                out.append(p.severity() == Severity.ERROR ? "❌ " : "⚠️ ")
                        .append("Line ").append(p.line()).append(": ").append(p.message()).append("\n");
            }
            return out.toString().trim();
        }
    }

    private static Container card(Color accent, List<ContainerChildComponent> children) {
        return Container.of(children).withAccentColor(accent);
    }

    /**
     * @param interactive {@code false} renders every button disabled — what Preview uses, so nothing in a
     *                    preview can be clicked into doing something.
     */
    public static Parsed parse(String text, boolean interactive) {
        List<ContainerChildComponent> children = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        Color[] accent = {null};
        int[] buttonIndex = {0};
        int[] textChars = {0};
        int[] components = {1}; // the container itself

        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String line = lines[i];
            String trimmed = line.strip();

            Matcher matcher = TAG.matcher(trimmed);
            if (!matcher.find()) {
                paragraph.append(line).append('\n');
                continue;
            }

            String leftover = TAG.matcher(trimmed).replaceAll("").strip();
            if (!leftover.isEmpty()) {
                problems.add(new Problem(Severity.ERROR, lineNo, "tags must sit on their own line — there's other text next to one here"));
                paragraph.append(line).append('\n');
                continue;
            }

            flushParagraph(paragraph, children, problems, textChars, components, lineNo);

            List<Button> row = new ArrayList<>();
            matcher.reset();
            while (matcher.find()) {
                String body = matcher.group(1);
                if (body.startsWith("<")) {
                    flushRow(row, children, problems, components, lineNo);
                    layoutTag(body.substring(1, body.length() - 1).strip(), lineNo, children, problems, accent, components);
                } else {
                    Button button = parseButton(body, lineNo, buttonIndex, interactive, problems);
                    if (button != null) row.add(button);
                }
            }
            flushRow(row, children, problems, components, lineNo);
        }
        flushParagraph(paragraph, children, problems, textChars, components, lines.length);

        if (textChars[0] > MAX_TEXT_CHARS) {
            problems.add(new Problem(Severity.ERROR, 1, "the post has " + textChars[0] + " characters of text — Discord allows " + MAX_TEXT_CHARS + " per message"));
        }
        if (components[0] > MAX_COMPONENTS) {
            problems.add(new Problem(Severity.ERROR, 1, "the post needs " + components[0] + " components — Discord allows " + MAX_COMPONENTS
                    + " per message (every text block, divider, image and button counts, and a row of buttons counts too)"));
        }
        return new Parsed(children, accent[0], problems);
    }

    private static void flushParagraph(StringBuilder paragraph, List<ContainerChildComponent> children, List<Problem> problems,
                                       int[] textChars, int[] components, int lineNo) {
        String text = paragraph.toString().strip();
        paragraph.setLength(0);
        if (text.isEmpty()) return;
        textChars[0] += text.length();
        components[0] += 1;
        children.add(TextDisplay.of(text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text)); // the length error is reported once, in total
    }

    private static void flushRow(List<Button> row, List<ContainerChildComponent> children, List<Problem> problems, int[] components, int lineNo) {
        if (row.isEmpty()) return;
        if (row.size() > MAX_BUTTONS_PER_ROW) {
            problems.add(new Problem(Severity.ERROR, lineNo, "a row holds at most " + MAX_BUTTONS_PER_ROW + " buttons — this line has " + row.size() + "; put the rest on the next line"));
        } else {
            children.add(ActionRow.of(new ArrayList<>(row)));
            components[0] += 1 + row.size();
        }
        row.clear();
    }

    private static void layoutTag(String tag, int lineNo, List<ContainerChildComponent> children, List<Problem> problems,
                                  Color[] accent, int[] components) {
        String upper = tag.toUpperCase(Locale.ROOT);
        switch (upper) {
            case "LS" -> { children.add(Separator.createDivider(Separator.Spacing.SMALL)); components[0]++; }
            case "LS-L" -> { children.add(Separator.createDivider(Separator.Spacing.LARGE)); components[0]++; }
            case "LS-N" -> { children.add(Separator.createInvisible(Separator.Spacing.SMALL)); components[0]++; }
            case "LS-NL" -> { children.add(Separator.createInvisible(Separator.Spacing.LARGE)); components[0]++; }
            default -> {
                if (upper.startsWith("COLOR-")) {
                    Matcher hex = HEX.matcher(tag.substring("COLOR-".length()).strip());
                    if (hex.matches()) accent[0] = new Color(Integer.parseInt(hex.group(1), 16));
                    else problems.add(new Problem(Severity.ERROR, lineNo, "~<" + tag + ">~ needs a 6-digit hex color, like ~<COLOR-5865F2>~"));
                } else if (upper.startsWith("IMG-")) {
                    String url = tag.substring("IMG-".length()).strip();
                    if (URL.matcher(url).matches()) {
                        children.add(MediaGallery.of(MediaGalleryItem.fromUrl(url)));
                        components[0] += 2;
                    } else {
                        problems.add(new Problem(Severity.ERROR, lineNo, "~<" + tag + ">~ needs an image link starting with http:// or https://"));
                    }
                } else {
                    problems.add(new Problem(Severity.ERROR, lineNo, "unknown tag ~<" + tag + ">~ — open the Legend to see what's available"));
                }
            }
        }
    }

    private static Button parseButton(String body, int lineNo, int[] buttonIndex, boolean interactive, List<Problem> problems) {
        char style = Character.toUpperCase(body.charAt(2));
        String rest = body.substring(4); // after "B-X_"
        int bar = rest.indexOf('|');
        String label = (bar < 0 ? rest : rest.substring(0, bar)).strip();
        String action = bar < 0 ? "" : rest.substring(bar + 1).strip();

        if (label.isEmpty()) {
            problems.add(new Problem(Severity.ERROR, lineNo, "a button needs a label — ~B-" + style + "_Label|action~"));
            return null;
        }
        if (label.length() > MAX_BUTTON_LABEL) {
            problems.add(new Problem(Severity.ERROR, lineNo, "the button label \"" + label.substring(0, 20) + "…\" is longer than " + MAX_BUTTON_LABEL + " characters"));
            return null;
        }
        if ("PSDGL".indexOf(style) < 0) {
            problems.add(new Problem(Severity.ERROR, lineNo, "unknown button style \"" + body.charAt(2) + "\" in ~" + body + "~ — use P (blue), S (gray), D (red), G (green) or L (link)"));
            return null;
        }

        if (style == 'L') {
            if (!URL.matcher(action).matches()) {
                problems.add(new Problem(Severity.ERROR, lineNo, "the link button \"" + label + "\" needs a link after the | that starts with http:// or https://"));
                return null;
            }
            return Button.link(action, label);
        }

        String normalized = action.toLowerCase(Locale.ROOT);
        boolean hasAction = !normalized.isEmpty();
        if (hasAction && !ACTIONS.contains(normalized)) {
            problems.add(new Problem(Severity.ERROR, lineNo, "unknown action \"" + action + "\" on the button \"" + label + "\" — available: " + String.join(", ", ACTIONS)));
            return null;
        }
        if (!hasAction) {
            problems.add(new Problem(Severity.WARNING, lineNo, "the button \"" + label + "\" has no action, so it's posted disabled — add one after a |, like ~B-" + style + "_" + label + "|rs~"));
        }

        String id = BUTTON_ID_PREFIX + (buttonIndex[0]++) + ":" + (hasAction ? normalized : "none");
        Button button = switch (style) {
            case 'P' -> Button.primary(id, label);
            case 'S' -> Button.secondary(id, label);
            case 'D' -> Button.danger(id, label);
            default -> Button.success(id, label);
        };
        return (interactive && hasAction) ? button : button.asDisabled();
    }

    /** The cheat sheet shown from the post editor's Legend button. */
    public static final String LEGEND = """
            ### Post tags
            Write your post as normal text (markdown works). Put a tag on **its own line**, between `~` marks.

            **Layout**
            `~<LS>~` divider line   ·   `~<LS-L>~` bigger divider
            `~<LS-N>~` small gap   ·   `~<LS-NL>~` bigger gap
            `~<COLOR-5865F2>~` side-bar color (6-digit hex)
            `~<IMG-https://…>~` an image

            **Buttons** — several on one line share a row (max 5)
            `~B-P_Label|action~`
            Style: `P` blue · `S` gray · `D` red · `G` green · `L` link
            Actions: `rs` starts the /rs link flow · `citadel` shows this week's Citadel totals
            Link button: `~B-L_Our site|https://example.com~`
            A button with no action is posted disabled.

            **Example**
            ```
            Welcome! Link your RuneScape name to get started.
            ~<LS>~
            ~B-P_Link my RSN|rs~ ~B-S_Citadel this week|citadel~
            ```
            Use **Preview** to see the result before you post it.""";
}
