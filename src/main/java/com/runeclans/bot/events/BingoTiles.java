package com.runeclans.bot.events;

import com.runeclans.bot.internal.WikiCatalog;
import com.runeclans.bot.internal.WikiCatalog.Boss;
import com.runeclans.bot.internal.WikiCatalog.Drop;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knows what a "Boss: item" bingo tile is: which boss, which item, and the item's picture. Tiles stay plain labels (so an organiser can still type
 * anything), and this works the boss and item out of the label with the wiki boss catalogue, the same one behind the website's boss pages. It also
 * lists every boss with the items in its loot table, for the dropdowns on the board editor.
 */
@BService
public class BingoTiles {
    /** What a tile label turned out to mean. {@code boss} and {@code item} are the catalogue's spelling; {@code iconKey} is null when there is no picture. */
    public record Ref(String boss, String item, String iconKey) {}

    private static final Map<String, Optional<BufferedImage>> ICONS = new ConcurrentHashMap<>();

    private final WikiCatalog catalog;
    private final Map<String, Drop> dropsByName = new HashMap<>();

    public BingoTiles(WikiCatalog catalog) {
        this.catalog = catalog;
        for (Boss boss : catalog.bosses()) {
            for (Drop drop : boss.drops()) dropsByName.putIfAbsent(drop.item().toLowerCase(Locale.ROOT), drop);
        }
    }

    /** The label a boss and item make. */
    public static String label(String boss, String item) {
        return boss + ": " + item;
    }

    /** Works out the boss and item of a label like {@code "Nex: Torva boots"}. Never null; a label that isn't a drop has a null boss and no icon. */
    public Ref resolve(String label) {
        String text = label == null ? "" : label.strip();
        int colon = text.indexOf(": ");
        String bossRaw = colon > 0 && colon < text.length() - 2 ? text.substring(0, colon) : null;
        String itemRaw = bossRaw == null ? text : text.substring(colon + 2).strip();
        Optional<Boss> boss = bossRaw == null ? Optional.empty() : catalog.canonical(bossRaw);
        Drop drop = boss.flatMap(b -> b.drops().stream().filter(d -> d.item().equalsIgnoreCase(itemRaw)).findFirst()).orElse(dropsByName.get(itemRaw.toLowerCase(Locale.ROOT)));
        String key = drop != null ? drop.key() : WikiCatalog.slug(itemRaw);
        return new Ref(boss.map(Boss::name).orElse(bossRaw), drop != null ? drop.item() : itemRaw, hasIcon(key) ? key : null);
    }

    /** Whether some boss in the catalogue drops an item with this name (any case): the bingo editor's "auto with RuneSync" hint (T-141). */
    public boolean knowsItem(String item) {
        return item != null && dropsByName.containsKey(item.strip().toLowerCase(Locale.ROOT));
    }

    public String iconKeyFor(String label) {
        return resolve(label).iconKey();
    }

    // ---------- pictures ----------

    static boolean hasIcon(String key) {
        return key != null && !key.isBlank() && ICONS.computeIfAbsent(key, BingoTiles::load).isPresent();
    }

    /** The item's picture, when there is one. Pictures are the wiki's inventory icons, kept under {@code images/items} (or {@code images/drops}). */
    public static Optional<BufferedImage> icon(String key) {
        return key == null || key.isBlank() ? Optional.empty() : ICONS.computeIfAbsent(key, BingoTiles::load);
    }

    private static Optional<BufferedImage> load(String key) {
        if (!key.matches("[a-z0-9_]{1,80}")) return Optional.empty();
        for (String dir : new String[]{"/images/items/", "/images/drops/"}) {
            try (InputStream in = BingoTiles.class.getResourceAsStream(dir + key + ".png")) {
                if (in != null) return Optional.ofNullable(ImageIO.read(in));
            } catch (IOException | RuntimeException ignored) {
                // a picture that can't be read is the same as no picture
            }
        }
        return Optional.empty();
    }

    // ---------- the boss and item choices ----------

    /** Every boss with the items in its loot table (guaranteed drops left out), for the board editor's dropdowns. */
    public DataObject choices() {
        DataArray bosses = DataArray.empty();
        for (Boss boss : catalog.bosses()) {
            DataArray items = DataArray.empty();
            for (Drop drop : boss.drops()) {
                if (drop.guaranteed()) continue;
                items.add(DataObject.empty().put("name", drop.item()).put("key", drop.key()).put("rarity", drop.rarity() == null ? "" : drop.rarity()).put("icon", hasIcon(drop.key())));
            }
            if (items.length() == 0) continue;
            bosses.add(DataObject.empty().put("name", boss.name()).put("image", boss.image()).put("items", items));
        }
        return DataObject.empty().put("bosses", bosses);
    }
}
