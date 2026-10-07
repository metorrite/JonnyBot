package com.younglings.bot.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementModels.Requirement;
import com.younglings.bot.combat.CombatAchievementModels.Tier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code catalog/combat_achievements.json} (built by {@code tools/ca_sync.py}) into the database when the bot starts, but only when the file has
 * changed since it was last loaded, so a restart is quick and a deploy with a refreshed catalogue updates the tables by itself. A problem is logged and
 * never stops the bot from starting; the tables keep whatever they held.
 */
@BService
public class CombatAchievementLoader {
    private static final Logger log = LoggerFactory.getLogger(CombatAchievementLoader.class);
    public static final String RESOURCE = "/catalog/combat_achievements.json";

    /** What the file holds. */
    public record Catalog(List<Tier> tiers, List<Achievement> achievements) {}

    public CombatAchievementLoader(CombatAchievementRepository repository) {
        try (InputStream stream = CombatAchievementLoader.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                log.warn("{} isn't in the build, so no combat achievements were loaded. Run tools/ca_sync.py to create it.", RESOURCE);
                return;
            }
            byte[] bytes = stream.readAllBytes();
            String hash = hashOf(bytes);
            if (hash.equals(repository.loadedHash())) {
                log.info("Combat achievement catalogue is up to date ({} achievements).", repository.count());
                return;
            }
            Catalog catalog = parse(bytes);
            repository.replaceAll(catalog.tiers(), catalog.achievements(), hash);
            log.info("Loaded the combat achievement catalogue: {} tiers, {} achievements.", catalog.tiers().size(), catalog.achievements().size());
        } catch (IOException | RuntimeException e) {
            log.error("Couldn't load the combat achievement catalogue", e);
        }
    }

    static String hashOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads the catalogue file. Throws if it isn't shaped as {@code tools/ca_sync.py} writes it. */
    public static Catalog parse(byte[] bytes) {
        DataObject root = DataObject.fromJson(new String(bytes, StandardCharsets.UTF_8));

        List<Tier> tiers = new ArrayList<>();
        DataArray tierArray = root.getArray("tiers");
        for (int i = 0; i < tierArray.length(); i++) {
            DataObject t = tierArray.getObject(i);
            tiers.add(new Tier(t.getInt("number"), t.getString("name"), text(t, "icon"), text(t, "wikiUrl"), text(t, "reward"), t.getInt("combatScorePer"), t.getInt("count")));
        }

        List<Achievement> achievements = new ArrayList<>();
        DataArray array = root.getArray("achievements");
        for (int i = 0; i < array.length(); i++) {
            DataObject a = array.getObject(i);

            Map<String, String> infobox = new LinkedHashMap<>();
            if (!a.isNull("infobox")) {
                DataObject box = a.getObject("infobox");
                for (String key : box.keys()) infobox.put(key, box.getString(key));
            }
            List<Requirement> requirements = new ArrayList<>();
            if (!a.isNull("listedAchievements")) {
                DataArray listed = a.getArray("listedAchievements");
                for (int r = 0; r < listed.length(); r++) {
                    DataObject item = listed.getObject(r);
                    requirements.add(new Requirement(r, item.isNull("id") ? null : item.getLong("id"), item.getString("name")));
                }
            }

            achievements.add(new Achievement(a.getLong("id"), a.getString("name"), a.getString("wikiTitle"), a.getString("wikiUrl"), a.getString("description"),
                    a.getBoolean("members"), text(a, "membersIcon"), text(a, "subcategory"), text(a, "subcategoryUrl"), text(a, "subsubcategory"), text(a, "subsubcategoryUrl"),
                    a.getInt("tierNumber"), a.getString("tier"), text(a, "tierIcon"), a.getInt("combatScore"), text(a, "combatScoreIcon"), a.getInt("runeScore"), text(a, "runeScoreIcon"),
                    text(a, "wikiSummary"), text(a, "wikiText"), infobox, requirements));
        }
        return new Catalog(tiers, achievements);
    }

    private static String text(DataObject json, String key) {
        return json.isNull(key) ? null : json.getString(key);
    }
}
