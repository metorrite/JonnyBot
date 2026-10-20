package com.younglings.bot.events;

import net.dv8tion.jda.api.utils.data.DataObject;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws a bingo board as a PNG: a square grid of numbered tiles, finished tiles filled and ticked. Pure Java2D with no Discord in it, so it can be
 * tested and reused (the live board message, a player's own board). A tile shows its number (what players type to submit proof for it), its text
 * (a "Boss: item" label is split so the boss reads small above the item), its points, and, on a shared board, how many have completed it.
 */
public final class BingoBoardImage {
    private static final Color BACKGROUND = new Color(0x15171c);
    private static final Color TILE = new Color(0x23262e);
    private static final Color TILE_BORDER = new Color(0x343845);
    private static final Color DONE = new Color(0x245c3d);
    private static final Color GOLD = new Color(0xd4af37);
    private static final Color TEXT = new Color(0xeceff4);
    private static final Color MUTED = new Color(0x9aa3b2);

    /**
     * The colours a grid board is drawn in. {@link #LEGACY} is the plain dark board every grid had before looks existed; the others go with a painted
     * backdrop ({@link BingoBackdrops}) and add a panel behind the tiles, numbered badges, and a framed title.
     */
    private record Palette(Color tile, Color tileBorder, Color done, Color doneBorder, Color text, Color muted, Color accent, boolean badge, Color badgeFill, Color badgeText,
                           Color panelFill, Color panelBorder, Color titleMain, Color titleShadow, Color subtitle, boolean plaque, Color plaqueFill, Color plaqueBorder) {}

    private static Color rgb(int rgb) {
        return new Color(rgb);
    }

    private static Color rgba(int rgb, int alpha) {
        return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, alpha);
    }

    private static final Palette LEGACY = new Palette(TILE, TILE_BORDER, DONE, GOLD, TEXT, MUTED, GOLD, false, GOLD, BACKGROUND, null, null, null, null, null, false, null, null);
    private static final Palette CLASSIC = new Palette(rgb(0x101a1f), rgb(0xc9a85c), rgb(0x1d4a35), rgb(0xf0d078), rgb(0xefe6c8), rgb(0x9fb3b3), rgb(0xe0bf6a), true, rgb(0xc7a35f), rgb(0x1b1b1b),
            rgba(0x050a0d, 150), rgba(0xc9a85c, 170), rgb(0xf4e2a8), rgba(0x000000, 150), rgb(0xa9bcbc), false, null, null);
    private static final Palette RUNESCAPE = new Palette(rgb(0x2a2017), rgb(0xb88a4a), rgb(0x2c4a2e), rgb(0xe6c878), rgb(0xf4e8c8), rgb(0xbfa57a), rgb(0xe6c878), true, rgb(0xe6c878), rgb(0x2a1a08),
            rgba(0x120d09, 222), rgb(0xc29a4c), rgb(0xf6e3ad), rgba(0x000000, 160), rgb(0xe3d3ab), true, rgba(0x2a1d12, 240), rgb(0xc29a4c));

    /**
     * One tile as drawn. {@code count} of {@code of} have completed it on a shared board; {@code of} 0 means a personal board with no counts.
     * {@code iconKey} names the item picture to draw above the text (see {@link BingoTiles}), or is null for a tile without one.
     */
    public record TileView(int number, String label, long points, boolean done, int count, int of, String iconKey, String by, boolean locked) {
        public TileView(int number, String label, long points, boolean done, int count, int of) {
            this(number, label, points, done, count, of, null, null, false);
        }

        public TileView(int number, String label, long points, boolean done, int count, int of, String iconKey) {
            this(number, label, points, done, count, of, iconKey, null, false);
        }
    }

    private BingoBoardImage() {}

    /** The side of one tile in pixels for a board of this size: bigger boards get smaller tiles so the picture stays a sensible size. */
    static int tileSize(int boardSize) {
        return switch (boardSize) {
            case 3 -> 200;
            case 4 -> 172;
            case 5 -> 150;
            case 6 -> 132;
            default -> 118;
        };
    }

    public static byte[] render(String title, String subtitle, int size, List<TileView> tiles, String footer) throws IOException {
        return renderPlain(title, subtitle, size, tiles, footer, BingoBoardOptions.NONE);
    }

    private static byte[] renderPlain(String title, String subtitle, int size, List<TileView> tiles, String footer, BingoBoardOptions options) throws IOException {
        if (tiles.size() != size * size) throw new IllegalArgumentException("A " + size + "x" + size + " board needs " + size * size + " tiles.");
        int tile = tileSize(size);
        int gap = 8;
        int pad = 20;
        int header = 64;
        int footerHeight = footer == null || footer.isBlank() ? 12 : 40;
        int width = pad * 2 + size * tile + (size - 1) * gap;
        int height = header + size * tile + (size - 1) * gap + footerHeight + pad;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, width, height);

            g.setColor(TEXT);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            g.drawString(fit(g, title, width - pad * 2), pad, 38);
            if (subtitle != null && !subtitle.isBlank()) {
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString(fit(g, subtitle, width - pad * 2), pad, 58);
            }

            for (int i = 0; i < tiles.size(); i++) {
                int x = pad + (i % size) * (tile + gap);
                int y = header + (i / size) * (tile + gap);
                drawTile(g, tiles.get(i), x, y, tile, size, accented(LEGACY, options), options);
            }

            if (footer != null && !footer.isBlank()) {
                g.setColor(MUTED);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g.drawString(fit(g, footer, width - pad * 2), pad, header + size * tile + (size - 1) * gap + 26);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** The palette with the accent colour of the board options in place of its own (gold, the original, changes nothing). */
    private static Palette accented(Palette p, BingoBoardOptions options) {
        if (BingoBoardOptions.ACCENT_GOLD.equals(options.accent())) return p;
        Color c = options.accentColour();
        return new Palette(p.tile(), p.tileBorder(), p.done(), c, p.text(), p.muted(), c, p.badge(), c, p.badgeText(), p.panelFill(), p.panelBorder(), p.titleMain(), p.titleShadow(),
                p.subtitle(), p.plaque(), p.plaqueFill(), p.plaqueBorder());
    }

    private static void drawTile(Graphics2D g, TileView t, int x, int y, int tile, int boardSize, Palette p, BingoBoardOptions options) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, tile, tile, 14, 14);
        g.setColor(t.done() ? p.done() : p.tile());
        g.fill(shape);
        g.setStroke(new BasicStroke(t.done() ? 2.5f : 1.5f));
        g.setColor(t.done() ? p.doneBorder() : p.tileBorder());
        g.draw(shape);

        int fontSize = switch (boardSize) {
            case 3 -> 19;
            case 4 -> 16;
            case 5 -> 14;
            case 6 -> 13;
            default -> 12;
        };
        int inner = tile - 20;

        // tile number, top left: what a player types to submit proof for this tile
        if (!options.numbers()) {
            // switched off in the board options: no number is drawn
        } else if (p.badge()) {
            g.setColor(p.badgeFill());
            g.fill(new Ellipse2D.Float(x - 3, y - 3, 26, 26));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(p.badgeText());
            String number = Integer.toString(t.number());
            FontMetrics nm = g.getFontMetrics();
            g.drawString(number, x + 10 - nm.stringWidth(number) / 2f, y + 10 + (nm.getAscent() - nm.getDescent()) / 2f);
        } else {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(11, fontSize - 2)));
            g.setColor(t.done() ? p.accent() : p.muted());
            g.drawString(Integer.toString(t.number()), x + 10, y + 8 + g.getFontMetrics().getAscent() - 2);
        }

        // a "Boss: item" label reads as a small boss name above the item
        String top = null;
        String main = t.label();
        int colon = t.label().indexOf(": ");
        if (colon > 0 && colon < t.label().length() - 2) {
            top = t.label().substring(0, colon);
            main = t.label().substring(colon + 2);
        }

        Font mainFont = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        Font topFont = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 3));
        g.setFont(mainFont);
        BufferedImage icon = BingoTiles.icon(t.iconKey()).orElse(null);
        List<String> lines = wrap(g.getFontMetrics(), main, inner, (top == null ? 5 : 4) - (icon == null ? 0 : 2));
        FontMetrics mm = g.getFontMetrics(mainFont);
        FontMetrics tm = g.getFontMetrics(topFont);
        int lineHeight = mm.getHeight();
        int textHeight = lines.size() * lineHeight + (top == null ? 0 : tm.getHeight() + 2);
        // the points and counts run along the bottom edge, so the text block and the picture share what is above that row
        int usable = tile - Math.max(24, fontSize + 10);
        int iconScale = 0;
        if (icon != null) {
            // the wiki's inventory icons are about 30 pixels: whole-number scaling with no smoothing keeps them crisp
            int room = Math.min(Math.round(tile * 0.4f), usable - textHeight - 14);
            iconScale = Math.min(room / Math.max(1, icon.getWidth()), room / Math.max(1, icon.getHeight()));
            if (iconScale < 1) {
                // no room for a picture beside this much text: give the text the space instead
                icon = null;
                lines = wrap(g.getFontMetrics(), main, inner, top == null ? 5 : 4);
                textHeight = lines.size() * lineHeight + (top == null ? 0 : tm.getHeight() + 2);
            }
        }
        int iconHeight = icon == null ? 0 : icon.getHeight() * iconScale;
        int blockHeight = textHeight + (icon == null ? 0 : iconHeight + 6);
        int cy = y + Math.max(6, (usable - blockHeight) / 2 + 4);
        if (icon != null) {
            int w = icon.getWidth() * iconScale;
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, x + (tile - w) / 2, cy, w, iconHeight, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : previous);
            cy += iconHeight + 6;
        }
        if (top != null) {
            g.setFont(topFont);
            g.setColor(p.muted());
            String shown = fit(g, top, inner);
            g.drawString(shown, x + (tile - tm.stringWidth(shown)) / 2, cy + tm.getAscent());
            cy += tm.getHeight() + 2;
        }
        g.setFont(mainFont);
        g.setColor(p.text());
        for (String line : lines) {
            g.drawString(line, x + (tile - mm.stringWidth(line)) / 2, cy + mm.getAscent());
            cy += lineHeight;
        }

        // points bottom right, completions bottom left
        Font small = new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, fontSize - 3));
        g.setFont(small);
        FontMetrics sm = g.getFontMetrics();
        g.setColor(t.done() ? p.accent() : p.muted());
        if (t.points() >= 0) {
            String points = t.points() + " pts";
            g.drawString(points, x + tile - 10 - sm.stringWidth(points), y + tile - 9);
        }
        if (options.names() && t.done() && t.by() != null && !t.by().isBlank()) {
            // who finished it first (and how many others did too), where the count would be
            g.setColor(p.accent());
            String who = t.of() > 0 && t.count() > 1 ? t.by() + " +" + (t.count() - 1) : t.by();
            g.drawString(ellipsize(sm, who, tile - 20 - (t.points() >= 0 ? sm.stringWidth(t.points() + " pts") + 6 : 0)), x + 10, y + tile - 9);
        } else if (t.of() > 0) {
            g.setColor(p.muted());
            g.drawString(t.count() + "/" + t.of(), x + 10, y + tile - 9);
        }

        if (t.done()) drawTick(g, x + tile - 26, y + 12, 16, p.accent());
    }

    // ---------- the grid in a look ----------

    /**
     * A board drawn from its settings: in its shape and look if it has a shape, otherwise as a square grid ({@code title} and {@code subtitle} head the grid;
     * a shape's picture has its own). A grid whose settings name no look is the plain dark board it always was.
     */
    public static byte[] render(DataObject config, String title, String subtitle, String footer, List<TileView> tiles) throws IOException {
        var layout = BingoTemplate.layoutOf(config);
        String look = BingoLooks.of(config).orElse(null);
        BingoBoardOptions options = BingoBoardOptions.of(config);
        // fog is applied to the tiles before anything is drawn, so nothing about a hidden tile can reach the picture
        List<TileView> shown = options.fog() ? BingoBoardOptions.fogged(tiles) : tiles;
        if (layout.isPresent() && layout.get().generated()) {
            return renderGenerated(layout.get(), look == null ? BingoLooks.CLASSIC : look, options.title().isEmpty() ? title : options.title(),
                    options.subtitle().isEmpty() ? subtitle : options.subtitle(), footer, shown, options);
        }
        if (layout.isPresent()) return render(layout.get(), look == null ? BingoLooks.CLASSIC : look, shown);
        return renderGrid(options.title().isEmpty() ? title : options.title(), options.subtitle().isEmpty() ? subtitle : options.subtitle(), BingoBoardView.boardSize(config), shown, footer, look, options);
    }

    /**
     * A square grid in a look ({@link BingoLooks}): the same tiles as the plain board, on a painted backdrop with a framed title and a panel behind the
     * tiles. A look that isn't known draws the plain board.
     */
    public static byte[] render(String title, String subtitle, int size, List<TileView> tiles, String footer, String look) throws IOException {
        return renderGrid(title, subtitle, size, tiles, footer, look, BingoBoardOptions.NONE);
    }

    private static byte[] renderGrid(String title, String subtitle, int size, List<TileView> tiles, String footer, String look, BingoBoardOptions options) throws IOException {
        if (!BingoLooks.valid(look)) return renderPlain(title, subtitle, size, tiles, footer, options);
        if (tiles.size() != size * size) throw new IllegalArgumentException("A " + size + "x" + size + " board needs " + size * size + " tiles.");
        Palette p = accented(BingoLooks.RUNESCAPE.equals(look) ? RUNESCAPE : CLASSIC, options);
        int tile = tileSize(size);
        int gap = 8;
        int side = size * tile + (size - 1) * gap;
        int width = Math.max(side + 2 * 70, 760);
        int height = BingoBackdrops.HEADER + side + BingoBackdrops.FOOTER;
        BufferedImage base = BingoBackdrops.grid(look, width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            int left = (width - side) / 2;
            int top = BingoBackdrops.HEADER;
            RoundRectangle2D panel = new RoundRectangle2D.Float(left - 12, top - 12, side + 24, side + 24, 22, 22);
            g.setColor(p.panelFill());
            g.fill(panel);
            g.setColor(p.panelBorder());
            g.setStroke(new BasicStroke(2.5f));
            g.draw(panel);
            drawHeader(g, width, title, subtitle, p);
            for (int i = 0; i < tiles.size(); i++) {
                drawTile(g, tiles.get(i), left + (i % size) * (tile + gap), top + (i / size) * (tile + gap), tile, size, p, options);
            }
            if (footer != null && !footer.isBlank()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                String shown = fit(g, footer, width - 160);
                FontMetrics fm = g.getFontMetrics();
                float x = (width - fm.stringWidth(shown)) / 2f;
                float y = top + side + 38;
                g.setColor(p.titleShadow());
                g.drawString(shown, x + 1, y + 1);
                g.setColor(p.subtitle());
                g.drawString(shown, x, y);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * The title in tracked capitals with the subtitle under it; the RuneScape look sets the title on a carved plaque and the template look puts a rule
     * under it. A title that doesn't fit on one line in a large size goes on two lines in a smaller one, and is cut only after that.
     */
    private static void drawHeader(Graphics2D g, int width, String title, String subtitle, Palette p) {
        String name = (title == null || title.isBlank() ? "Bingo" : title.strip()).toUpperCase(Locale.ROOT);
        float maxWidth = width - 2 * (p.plaque() ? 130 : 90);
        List<String> lines = new ArrayList<>(List.of(name));
        int size = 54;
        FontMetrics fm = g.getFontMetrics(new Font(Font.SERIF, Font.BOLD, size));
        float tracking = Math.max(2f, size * 0.16f);
        while (trackedWidth(fm, name, tracking) > maxWidth && size > 42) {
            size -= 2;
            fm = g.getFontMetrics(new Font(Font.SERIF, Font.BOLD, size));
            tracking = Math.max(2f, size * 0.16f);
        }
        if (trackedWidth(fm, name, tracking) > maxWidth) {
            lines = splitInTwo(name);
            size = lines.size() == 2 ? 34 : 42;
            fm = g.getFontMetrics(new Font(Font.SERIF, Font.BOLD, size));
            tracking = Math.max(2f, size * 0.14f);
            while (widest(fm, lines, tracking) > maxWidth && size > 22) {
                size -= 2;
                fm = g.getFontMetrics(new Font(Font.SERIF, Font.BOLD, size));
                tracking = Math.max(2f, size * 0.14f);
            }
            for (int i = 0; i < lines.size(); i++) {
                String cut = lines.get(i);
                if (trackedWidth(fm, cut, tracking) <= maxWidth) continue;
                while (cut.length() > 1 && trackedWidth(fm, cut + "...", tracking) > maxWidth) cut = cut.substring(0, cut.length() - 1);
                lines.set(i, cut.stripTrailing() + "...");
            }
        }
        Font font = new Font(Font.SERIF, Font.BOLD, size);
        float centre = width / 2f;
        float middle = 82;
        float lineHeight = size * 1.12f;
        float blockHeight = lineHeight * lines.size();
        if (p.plaque()) {
            float plaqueWidth = widest(fm, lines, tracking) + 90;
            float plaqueHeight = lines.size() == 1 ? 68 : blockHeight + 22;
            RoundRectangle2D plaque = new RoundRectangle2D.Float(centre - plaqueWidth / 2, middle - plaqueHeight / 2, plaqueWidth, plaqueHeight, 16, 16);
            g.setColor(p.plaqueFill());
            g.fill(plaque);
            g.setColor(p.plaqueBorder());
            g.setStroke(new BasicStroke(3f));
            g.draw(plaque);
            g.setColor(rgba(0xe6c878, 80));
            g.setStroke(new BasicStroke(1f));
            g.draw(new RoundRectangle2D.Float(centre - plaqueWidth / 2 + 6, middle - plaqueHeight / 2 + 6, plaqueWidth - 12, plaqueHeight - 12, 11, 11));
        }
        g.setFont(font);
        for (int i = 0; i < lines.size(); i++) {
            float baseline = middle - blockHeight / 2 + lineHeight * i + lineHeight / 2 + size * 0.36f;
            g.setColor(p.titleShadow());
            drawTracked(g, lines.get(i), centre + 2, baseline + 2, tracking);
            g.setColor(p.titleMain());
            drawTracked(g, lines.get(i), centre, baseline, tracking);
        }
        float subtitleBaseline = lines.size() == 1 ? 138 : middle + blockHeight / 2 + 30;
        if (!p.plaque()) {
            // the template look's rule with a diamond, between the title block and the board
            float y = subtitleBaseline + 20;
            g.setPaint(new LinearGradientPaint(width * 0.22f, 0, width * 0.78f, 0, new float[]{0f, 0.5f, 1f}, new Color[]{rgba(0xe0bf6a, 0), rgba(0xe0bf6a, 150), rgba(0xe0bf6a, 0)}));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Line2D.Float(width * 0.22f, y, width * 0.78f, y));
            g.setColor(rgba(0xe0bf6a, 220));
            Path2D diamond = new Path2D.Float();
            diamond.moveTo(centre, y - 8);
            diamond.lineTo(centre + 7, y);
            diamond.lineTo(centre, y + 8);
            diamond.lineTo(centre - 7, y);
            diamond.closePath();
            g.fill(diamond);
        }
        if (subtitle != null && !subtitle.isBlank()) {
            String line = subtitle.strip().toUpperCase(Locale.ROOT);
            Font small = new Font(Font.SANS_SERIF, Font.PLAIN, 17);
            FontMetrics sm = g.getFontMetrics(small);
            float spacing = 4f;
            if (trackedWidth(sm, line, spacing) > width - 140) spacing = 1.5f;
            if (trackedWidth(sm, line, spacing) > width - 140) {
                while (line.length() > 1 && trackedWidth(sm, line + "...", spacing) > width - 140) line = line.substring(0, line.length() - 1);
                line = line.stripTrailing() + "...";
            }
            g.setFont(small);
            g.setColor(p.titleShadow());
            drawTracked(g, line, centre + 1, subtitleBaseline + 1, spacing);
            g.setColor(p.subtitle());
            drawTracked(g, line, centre, subtitleBaseline, spacing);
        }
    }

    private static float widest(FontMetrics metrics, List<String> lines, float tracking) {
        float widest = 0;
        for (String line : lines) widest = Math.max(widest, trackedWidth(metrics, line, tracking));
        return widest;
    }

    /** The name in two lines, split at the space nearest its middle; one line when it has no space to split at. */
    private static List<String> splitInTwo(String name) {
        int best = -1;
        for (int i = 1; i < name.length() - 1; i++) {
            if (name.charAt(i) == ' ' && (best < 0 || Math.abs(i - name.length() / 2) < Math.abs(best - name.length() / 2))) best = i;
        }
        if (best < 0) return new ArrayList<>(List.of(name));
        return new ArrayList<>(List.of(name.substring(0, best).strip(), name.substring(best + 1).strip()));
    }

    private static float trackedWidth(FontMetrics metrics, String text, float tracking) {
        float total = 0;
        for (char ch : text.toCharArray()) total += metrics.charWidth(ch) + tracking;
        return total - tracking;
    }

    /** Draws {@code text} centred on {@code centre} with extra space between the letters. */
    private static void drawTracked(Graphics2D g, String text, float centre, float baseline, float tracking) {
        FontMetrics metrics = g.getFontMetrics();
        float x = centre - trackedWidth(metrics, text, tracking) / 2;
        for (char ch : text.toCharArray()) {
            g.drawString(String.valueOf(ch), x, baseline);
            x += metrics.charWidth(ch) + tracking;
        }
    }

    // ---------- the picture shapes ----------

    private static final Color INSET_FALLBACK = new Color(0x0a1015);
    private static final Color CENTRE = new Color(0x3b2230);
    private static final Color COMPLETE = new Color(0x35ce72);
    private static final Color BADGE = new Color(0xc7a35f);

    /**
     * Draws a board in one of the picture shapes: the shape's own picture with each tile's place filled in. The picture has printed placeholders
     * ("ITEM ICON", "PLAYER NAME") that are covered first, using the colour the picture itself has there. A finished tile is tinted green, shows
     * its item picture or its text, and under it who completed it first (and how many others did too); a tile not finished shows what it is
     * worth. On a personal board a tile that hasn't opened yet is dimmed. {@code tiles} are in the picture's numbering.
     */
    public static byte[] render(BingoLayout layout, List<TileView> tiles) throws IOException {
        return render(layout, BingoLayout.LOOK_CLASSIC, tiles);
    }

    /** As above in a look ({@link BingoLayout#looks}). */
    public static byte[] render(BingoLayout layout, String look, List<TileView> tiles) throws IOException {
        return render(layout.image(look), layout, tiles, BingoLayout.hasPlaceholders(look));
    }

    /**
     * As above on a given background picture (the places must be where {@code layout} says). {@code coverPlaceholders} is for a picture that has
     * "ITEM ICON" and "PLAYER NAME" printed in its places, as the original templates do; a clean background needs no covering.
     */
    public static byte[] render(BufferedImage base, BingoLayout layout, List<TileView> tiles, boolean coverPlaceholders) throws IOException {
        if (tiles.size() != layout.tiles()) throw new IllegalArgumentException("The " + layout.name() + " board needs " + layout.tiles() + " tiles.");
        BufferedImage image = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            for (int i = 0; i < tiles.size(); i++) drawPlace(g, base, layout.slots().get(i), tiles.get(i), coverPlaceholders);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * A shape drawn in code in a size of its own ({@link BingoShapes}): the look's backdrop at the size the shape needs, the title block, a line from each
     * tile to the tiles that open it, and each place framed and numbered (a painted picture has all of that printed in it). The tiles are then filled in the
     * way they are on a painted picture.
     */
    private static byte[] renderGenerated(BingoLayout layout, String look, String title, String subtitle, String footer, List<TileView> tiles, BingoBoardOptions options) throws IOException {
        if (tiles.size() != layout.tiles()) throw new IllegalArgumentException("The " + layout.name() + " board needs " + layout.tiles() + " tiles.");
        String usedLook = BingoLooks.valid(look) ? look : BingoLooks.CLASSIC;
        Palette p = accented(BingoLooks.RUNESCAPE.equals(usedLook) ? RUNESCAPE : CLASSIC, options);
        BufferedImage base = layout.image(usedLook);
        int width = base.getWidth();
        int height = base.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(base, 0, 0, null);
            drawHeader(g, width, title, subtitle, p);
            // the ways between tiles go underneath: from each tile to every tile that must be done before it
            var places = layout.places();
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(p.tileBorder().getRed(), p.tileBorder().getGreen(), p.tileBorder().getBlue(), 120));
            layout.requires().forEach((number, needs) -> {
                var to = places.get(number - 1);
                for (int need : needs) {
                    var from = places.get(need - 1);
                    g.draw(new Line2D.Double(from.x(), from.y(), to.x(), to.y()));
                }
            });
            for (BingoLayout.Slot slot : layout.slots()) drawFrame(g, slot, p);
            for (int i = 0; i < tiles.size(); i++) drawPlace(g, base, layout.slots().get(i), tiles.get(i), false);
            if (footer != null && !footer.isBlank()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                String shown = fit(g, footer, width - 160);
                FontMetrics fm = g.getFontMetrics();
                float x = (width - fm.stringWidth(shown)) / 2f;
                float y = height - BingoBackdrops.FOOTER + 38;
                g.setColor(p.titleShadow());
                g.drawString(shown, x + 1, y + 1);
                g.setColor(p.subtitle());
                g.drawString(shown, x, y);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** What a painted picture prints at each place: the tile's frame, an inset for the item picture and name, and the number badge. */
    private static void drawFrame(Graphics2D g, BingoLayout.Slot slot, Palette p) {
        int[] b = slot.bounds();
        int[] ib = slot.icon();
        int[] nb = slot.name();
        if (slot.round()) {
            Ellipse2D circle = new Ellipse2D.Float(b[0] + 6, b[1] + 6, b[2] - 12, b[3] - 12);
            g.setColor(CENTRE);
            g.fill(circle);
            g.setColor(p.tileBorder());
            g.setStroke(new BasicStroke(3f));
            g.draw(circle);
            return;
        }
        RoundRectangle2D frame = new RoundRectangle2D.Float(b[0], b[1], b[2], b[3], 18, 18);
        g.setColor(p.tile());
        g.fill(frame);
        g.setColor(p.tileBorder());
        g.setStroke(new BasicStroke(2.5f));
        g.draw(frame);
        g.setColor(INSET_FALLBACK);
        g.fillRoundRect(ib[0] - 3, ib[1] - 3, ib[2] + 6, ib[3] + 6, 8, 8);
        g.fillRoundRect(nb[0] - 1, nb[1] - 1, nb[2] + 2, nb[3] + 2, 6, 6);
        g.setColor(p.badgeFill());
        g.fill(new Ellipse2D.Float(b[0] - 3, b[1] - 3, 26, 26));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.setColor(p.badgeText());
        String number = Integer.toString(slot.number());
        FontMetrics nm = g.getFontMetrics();
        g.drawString(number, b[0] + 10 - nm.stringWidth(number) / 2f, b[1] + 10 + (nm.getAscent() - nm.getDescent()) / 2f);
    }

    private static Color colourAt(BufferedImage base, int x, int y, Color fallback) {
        if (x < 0 || y < 0 || x >= base.getWidth() || y >= base.getHeight()) return fallback;
        Color c = new Color(base.getRGB(x, y));
        return c.getAlpha() < 255 ? fallback : c;
    }

    private static void drawPlace(Graphics2D g, BufferedImage base, BingoLayout.Slot slot, TileView t, boolean coverPlaceholders) {
        int[] b = slot.bounds();
        int[] ib = slot.icon();
        int[] nb = slot.name();
        Shape shape = slot.round() ? new Ellipse2D.Float(b[0] + 6, b[1] + 6, b[2] - 12, b[3] - 12) : new RoundRectangle2D.Float(b[0], b[1], b[2], b[3], 18, 18);

        // cover what the picture printed there
        if (slot.round()) {
            if (coverPlaceholders) {
                g.setColor(CENTRE);
                g.fill(shape);
            }
        } else if (coverPlaceholders) {
            g.setColor(colourAt(base, ib[0] + 8, ib[1] + 8, INSET_FALLBACK));
            g.fillRoundRect(ib[0] - 3, ib[1] - 3, ib[2] + 6, ib[3] + 6, 8, 8);
            g.setColor(colourAt(base, nb[0] + 5, nb[1] + 9, INSET_FALLBACK));
            g.fillRoundRect(nb[0] - 1, nb[1] - 1, nb[2] + 2, nb[3] + 2, 6, 6);
        }
        if (t.done()) {
            g.setColor(new Color(COMPLETE.getRed(), COMPLETE.getGreen(), COMPLETE.getBlue(), 70));
            g.fill(shape);
        }

        drawLabel(g, ib, t, slot.round());
        drawUnder(g, nb, t);
        if (t.done()) drawTick(g, b[0] + b[2] - (slot.round() ? 36 : 26), b[1] + (slot.round() ? 16 : 10), 14);
        if (slot.round()) {
            // the centre has no printed number badge like the other places, so one is drawn on the circle's edge
            int cx = b[0] + b[2] / 2 - Math.round(b[2] * 0.354f);
            int cy = b[1] + b[3] / 2 - Math.round(b[3] * 0.354f);
            g.setColor(BADGE);
            g.fill(new Ellipse2D.Float(cx - 12, cy - 12, 24, 24));
            g.setColor(new Color(0x1b1b1b));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            String number = Integer.toString(t.number());
            FontMetrics nm = g.getFontMetrics();
            g.drawString(number, cx - nm.stringWidth(number) / 2, cy + (nm.getAscent() - nm.getDescent()) / 2);
        }
        if (t.locked() && !t.done()) {
            // dimmed, but the number badge stays readable: it is what a player types to submit proof for the tile
            Area dim = new Area(shape);
            dim.subtract(new Area(new Ellipse2D.Float(b[0] - 4, b[1] - 4, 27, 27)));
            g.setColor(new Color(6, 10, 14, 135));
            g.fill(dim);
        }
    }

    /** The tile's picture and name inside {@code box}: the item picture above two lines of text, or only text when there is no picture. */
    private static void drawLabel(Graphics2D g, int[] box, TileView t, boolean round) {
        String label = t.label();
        BufferedImage icon = BingoTiles.icon(t.iconKey()).orElse(null);
        // with a picture the boss is dropped from "Boss: item" to leave room; without one the whole label is the only clue
        int colon = label.indexOf(": ");
        String main = icon != null && colon > 0 && colon < label.length() - 2 ? label.substring(colon + 2) : label;
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, icon != null ? 10 : 12);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        int lineHeight = fm.getHeight() - 1;
        int maxLines = icon != null ? 2 : 5;
        List<String> lines = wrap(fm, main, box[2], maxLines);
        int iconHeight = icon == null ? 0 : Math.min(round ? 28 : 34, box[3] - lines.size() * lineHeight - 2);
        int blockHeight = iconHeight + (icon == null ? 0 : 2) + lines.size() * lineHeight;
        int y = box[1] + Math.max(0, (box[3] - blockHeight) / 2);
        if (icon != null && iconHeight > 8) {
            int scale = Math.max(1, Math.min(iconHeight / Math.max(1, icon.getHeight()), box[2] / Math.max(1, icon.getWidth())));
            int w = icon.getWidth() * scale;
            int h = icon.getHeight() * scale;
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(icon, box[0] + (box[2] - w) / 2, y + (iconHeight - h) / 2, w, h, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous == null ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : previous);
            y += iconHeight + 2;
        }
        g.setFont(font);
        g.setColor(t.done() ? TEXT : new Color(0xc9d0dc));
        for (String line : lines) {
            g.drawString(line, box[0] + (box[2] - fm.stringWidth(line)) / 2, y + fm.getAscent() - 1);
            y += lineHeight;
        }
    }

    /** The line under a tile: who completed it first (and how many others did too), or what it is worth while it isn't done. */
    private static void drawUnder(Graphics2D g, int[] box, TileView t) {
        String text;
        Color colour;
        if (t.done()) {
            String who = t.by() == null || t.by().isBlank() ? "Done" : t.by();
            text = t.count() > 1 ? who + " +" + (t.count() - 1) : who;
            colour = GOLD;
        } else {
            text = t.points() < 0 ? "" : t.points() + " pts";
            colour = MUTED;
        }
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, 11);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics(font);
        String shown = ellipsize(fm, text, box[2] - 8);
        g.setColor(colour);
        g.drawString(shown, box[0] + (box[2] - fm.stringWidth(shown)) / 2, box[1] + (box[3] + fm.getAscent() - fm.getDescent()) / 2);
    }

    private static void drawTick(Graphics2D g, int x, int y, int s) {
        drawTick(g, x, y, s, GOLD);
    }

    private static void drawTick(Graphics2D g, int x, int y, int s, Color colour) {
        g.setColor(colour);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(x, y + s / 2, x + s / 3, y + s - 2);
        g.drawLine(x + s / 3, y + s - 2, x + s, y + 1);
    }

    /** Breaks {@code text} into lines that fit {@code width}, at most {@code maxLines}, ending the last with an ellipsis if it had to be cut. */
    static List<String> wrap(FontMetrics metrics, String text, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.strip().split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            // a single word wider than the tile is cut down to fit
            while (metrics.stringWidth(word) > width && word.length() > 1) word = word.substring(0, word.length() - 1);
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width) {
                line = new StringBuilder(candidate);
                continue;
            }
            lines.add(line.toString());
            line = new StringBuilder(word);
            if (lines.size() == maxLines - 1) {
                // the rest goes on the last line, shortened
                StringBuilder rest = new StringBuilder(word);
                for (int j = i + 1; j < words.length; j++) rest.append(' ').append(words[j]);
                lines.add(ellipsize(metrics, rest.toString(), width));
                return lines;
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private static String ellipsize(FontMetrics metrics, String text, int width) {
        if (metrics.stringWidth(text) <= width) return text;
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "...") > width) cut = cut.substring(0, cut.length() - 1);
        return cut.stripTrailing() + "...";
    }

    private static String fit(Graphics2D g, String text, int width) {
        return ellipsize(g.getFontMetrics(), text, width);
    }
}
