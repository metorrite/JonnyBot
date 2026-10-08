package com.younglings.bot.tracking;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Named bosses the Boss Kills group recognizes a kill/defeat for — matched against an activity's raw
 * text by substring, same approach as {@link DropItemCatalog}. {@code key} is this boss's icon lookup key
 * (see {@link TrackingIconCatalog#mentionForBoss}) and the file name its picture is saved under;
 * {@code name} is both the substring matched against and the name shown in the posted message, so it
 * must stay exactly what RuneMetrics' own text uses.
 * <p>
 * The list comes from {@code catalog/boss_icons.json}, which {@code tools/boss_icons.py} builds from the
 * website's boss catalogue plus the other named bosses that can show up in a kill line, and which the
 * website's Clan Activity reads the same way, so a boss has the same picture in both places. Each name a
 * boss goes by is its own entry, longest first, so "Telos, the Warden" is tried before "Telos" and a kill
 * line is matched to the most specific name. If the file can't be read the original short list below is
 * used, so kills are still recognized, just with fewer bosses.
 */
public final class BossCatalog {
    private static final Logger log = LoggerFactory.getLogger(BossCatalog.class);
    static final String RESOURCE = "/catalog/boss_icons.json";

    public record Boss(String key, String name) {}

    /** The bosses the bot knew before the shared file existed; the fallback when it can't be read. */
    private static final List<Boss> FALLBACK = List.of(
            new Boss("tztok_jad", "TzTok-Jad"),
            new Boss("tzkal_zuk", "TzKal-Zuk"),
            new Boss("telos", "Telos"),
            new Boss("vorago", "Vorago"),
            new Boss("nex", "Nex"),
            new Boss("kalphite_king", "Kalphite King"),
            new Boss("kalphite_queen", "Kalphite Queen"),
            new Boss("queen_black_dragon", "Queen Black Dragon"),
            new Boss("corporeal_beast", "Corporeal Beast"),
            new Boss("general_graardor", "General Graardor"),
            new Boss("kreearra", "Kree'arra"),
            new Boss("kril_tsutsaroth", "K'ril Tsutsaroth"),
            new Boss("commander_zilyana", "Commander Zilyana"),
            new Boss("vindicta_gorvek", "Vindicta"),
            new Boss("helwyr", "Helwyr"),
            new Boss("gregorovic", "Gregorovic"),
            new Boss("har_aken", "Har-Aken"),
            new Boss("the_magister", "The Magister"),
            new Boss("araxxi", "Araxxi"),
            new Boss("the_twin_furies", "Nymora"),
            new Boss("the_twin_furies", "Avaryss"),
            new Boss("arch_glacor", "Arch-Glacor")
    );

    private static final List<Boss> BOSSES = load();

    private BossCatalog() {}

    /** Every name a boss goes by, each with its boss's key, longest name first (so the first match is the most specific). */
    public static List<Boss> all() {
        return BOSSES;
    }

    /** One key per boss, in the order they first appear: what there is a picture for. */
    public static List<String> keys() {
        return new ArrayList<>(new LinkedHashSet<>(BOSSES.stream().map(Boss::key).toList()));
    }

    private static List<Boss> load() {
        try (InputStream stream = BossCatalog.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                log.warn("{} isn't in the build, so only the {} bosses the bot always knew are recognised. Run tools/boss_icons.py.", RESOURCE, FALLBACK.size());
                return FALLBACK;
            }
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.error("Couldn't read {}; using the short built-in list of bosses", RESOURCE, e);
            return FALLBACK;
        }
    }

    /** The boss names in the file's JSON, longest first. */
    static List<Boss> parse(String json) {
        List<Boss> bosses = new ArrayList<>();
        DataArray array = DataObject.fromJson(json).getArray("bosses");
        for (int i = 0; i < array.length(); i++) {
            DataObject boss = array.getObject(i);
            DataArray aliases = boss.getArray("aliases");
            for (int a = 0; a < aliases.length(); a++) bosses.add(new Boss(boss.getString("key"), aliases.getString(a)));
        }
        bosses.sort(Comparator.comparingInt((Boss b) -> b.name().length()).reversed());
        return List.copyOf(bosses);
    }
}
