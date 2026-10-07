package com.younglings.bot.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementModels.Requirement;
import com.younglings.bot.combat.CombatAchievementModels.Tier;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads and reloads the Combat Mastery achievement tables. Every method opens its own connection, so nothing is held between calls. */
@BService
public class CombatAchievementRepository {
    private static final String COLUMNS = """
            id, name, wiki_title, wiki_url, description, members, members_icon, subcategory, subcategory_url, subsubcategory, subsubcategory_url,
            tier_number, tier, tier_icon, combat_score, combat_score_icon, rune_score, rune_score_icon, wiki_summary, wiki_text, infobox
            """;
    private static final String HASH_KEY = "catalog_hash";

    private final ConnectionSupplier connectionSupplier;

    public CombatAchievementRepository(ConnectionSupplier connectionSupplier, CombatAchievementDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    // ================= reloading =================

    /** A fingerprint of the catalogue that was last loaded, or {@code null} if none was. */
    public String loadedHash() {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT value FROM younglings.combat_achievement_meta WHERE key = ?")) {
            s.setString(1, HASH_KEY);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't read which combat achievement catalogue is loaded", e);
        }
    }

    /**
     * Makes the tables hold exactly this catalogue, in one transaction: tiers and achievements are added or updated, achievements the wiki no longer
     * lists are removed, and every achievement's list of required achievements is rewritten. A failure leaves the previous catalogue untouched.
     */
    public void replaceAll(List<Tier> tiers, List<Achievement> achievements, String hash) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (Tier t : tiers) {
                    try (PreparedStatement s = c.prepareStatement("""
                            INSERT INTO younglings.combat_achievement_tier (number, name, icon, wiki_url, reward, combat_score_per, achievement_count)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (number) DO UPDATE SET name = EXCLUDED.name, icon = EXCLUDED.icon, wiki_url = EXCLUDED.wiki_url, reward = EXCLUDED.reward,
                                combat_score_per = EXCLUDED.combat_score_per, achievement_count = EXCLUDED.achievement_count
                            """)) {
                        s.setInt(1, t.number());
                        s.setString(2, t.name());
                        s.setString(3, t.icon());
                        s.setString(4, t.wikiUrl());
                        s.setString(5, t.reward());
                        s.setInt(6, t.combatScorePer());
                        s.setInt(7, t.achievementCount());
                        s.executeUpdate();
                    }
                }

                String upsert = """
                        INSERT INTO younglings.combat_achievement (id, name, wiki_title, wiki_url, description, members, members_icon, subcategory, subcategory_url,
                            subsubcategory, subsubcategory_url, tier_number, tier, tier_icon, combat_score, combat_score_icon, rune_score, rune_score_icon,
                            wiki_summary, wiki_text, infobox, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())
                        ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, wiki_title = EXCLUDED.wiki_title, wiki_url = EXCLUDED.wiki_url,
                            description = EXCLUDED.description, members = EXCLUDED.members, members_icon = EXCLUDED.members_icon,
                            subcategory = EXCLUDED.subcategory, subcategory_url = EXCLUDED.subcategory_url, subsubcategory = EXCLUDED.subsubcategory,
                            subsubcategory_url = EXCLUDED.subsubcategory_url, tier_number = EXCLUDED.tier_number, tier = EXCLUDED.tier,
                            tier_icon = EXCLUDED.tier_icon, combat_score = EXCLUDED.combat_score, combat_score_icon = EXCLUDED.combat_score_icon,
                            rune_score = EXCLUDED.rune_score, rune_score_icon = EXCLUDED.rune_score_icon, wiki_summary = EXCLUDED.wiki_summary,
                            wiki_text = EXCLUDED.wiki_text, infobox = EXCLUDED.infobox, updated_at = NOW()
                        """;
                try (PreparedStatement s = c.prepareStatement(upsert)) {
                    for (Achievement a : achievements) {
                        int i = 1;
                        s.setLong(i++, a.id());
                        s.setString(i++, a.name());
                        s.setString(i++, a.wikiTitle());
                        s.setString(i++, a.wikiUrl());
                        s.setString(i++, a.description());
                        s.setBoolean(i++, a.members());
                        s.setString(i++, a.membersIcon());
                        s.setString(i++, a.subcategory());
                        s.setString(i++, a.subcategoryUrl());
                        s.setString(i++, a.subsubcategory());
                        s.setString(i++, a.subsubcategoryUrl());
                        s.setInt(i++, a.tierNumber());
                        s.setString(i++, a.tier());
                        s.setString(i++, a.tierIcon());
                        s.setInt(i++, a.combatScore());
                        s.setString(i++, a.combatScoreIcon());
                        s.setInt(i++, a.runeScore());
                        s.setString(i++, a.runeScoreIcon());
                        s.setString(i++, a.wikiSummary());
                        s.setString(i++, a.wikiText());
                        s.setString(i, infoboxJson(a.infobox()));
                        s.addBatch();
                    }
                    s.executeBatch();
                }

                // Achievements the wiki dropped, then every list of required achievements rewritten from scratch.
                Set<Long> ids = achievements.stream().map(Achievement::id).collect(Collectors.toSet());
                try (PreparedStatement s = c.prepareStatement("SELECT id FROM younglings.combat_achievement"); ResultSet rs = s.executeQuery()) {
                    List<Long> stale = new ArrayList<>();
                    while (rs.next()) if (!ids.contains(rs.getLong(1))) stale.add(rs.getLong(1));
                    for (long id : stale) {
                        try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.combat_achievement WHERE id = ?")) {
                            delete.setLong(1, id);
                            delete.executeUpdate();
                        }
                    }
                }
                try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.combat_achievement_requirement")) {
                    s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.combat_achievement_requirement (achievement_id, position, required_id, required_name) VALUES (?, ?, ?, ?)")) {
                    for (Achievement a : achievements) {
                        for (Requirement r : a.requirements()) {
                            s.setLong(1, a.id());
                            s.setInt(2, r.position());
                            if (r.achievementId() == null) s.setNull(3, Types.BIGINT);
                            else s.setLong(3, r.achievementId());
                            s.setString(4, r.name());
                            s.addBatch();
                        }
                    }
                    s.executeBatch();
                }

                try (PreparedStatement s = c.prepareStatement("""
                        INSERT INTO younglings.combat_achievement_meta (key, value) VALUES (?, ?)
                        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value
                        """)) {
                    s.setString(1, HASH_KEY);
                    s.setString(2, hash);
                    s.executeUpdate();
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't load the combat achievement catalogue", e);
        }
    }

    private static String infoboxJson(Map<String, String> infobox) {
        DataObject json = DataObject.empty();
        infobox.forEach(json::put);
        return json.toString();
    }

    // ================= reading =================

    public int count() {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.combat_achievement"); ResultSet rs = s.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't count the combat achievements", e);
        }
    }

    public List<Tier> tiers() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT number, name, icon, wiki_url, reward, combat_score_per, achievement_count FROM younglings.combat_achievement_tier ORDER BY number");
             ResultSet rs = s.executeQuery()) {
            List<Tier> tiers = new ArrayList<>();
            while (rs.next()) tiers.add(new Tier(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7)));
            return tiers;
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't read the combat achievement tiers", e);
        }
    }

    /**
     * The bosses and activities achievements belong to (the wiki's "subcategory", such as Amascut or Boss Dungeon: Sanctum of Rebirth), sorted by name.
     * A dungeon is one entry; the bosses inside it are only the achievements' subsubcategory and are not listed here.
     */
    public List<String> bosses() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT DISTINCT subcategory FROM younglings.combat_achievement WHERE subcategory IS NOT NULL ORDER BY subcategory");
             ResultSet rs = s.executeQuery()) {
            List<String> bosses = new ArrayList<>();
            while (rs.next()) bosses.add(rs.getString(1));
            bosses.sort(String.CASE_INSENSITIVE_ORDER);
            return bosses;
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't read the combat achievement bosses", e);
        }
    }

    /** Every achievement name, for autocomplete. */
    public List<String> names() {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT name FROM younglings.combat_achievement ORDER BY LOWER(name)");
             ResultSet rs = s.executeQuery()) {
            List<String> names = new ArrayList<>();
            while (rs.next()) names.add(rs.getString(1));
            return names;
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't read the combat achievement names", e);
        }
    }

    /** An achievement by its exact name or wiki page title, ignoring case. */
    public Optional<Achievement> findByName(String name) {
        List<Achievement> found = query("WHERE LOWER(name) = LOWER(?) OR LOWER(wiki_title) = LOWER(?)", 1, name.strip(), name.strip());
        return found.stream().findFirst();
    }

    public Optional<Achievement> findById(long id) {
        return query("WHERE id = ?", 1, id).stream().findFirst();
    }

    /**
     * Achievements whose name contains {@code text} (any if blank), optionally in one tier ({@code tierNumber}, 1 to 6) and/or a boss or activity
     * ({@code subcategory}, matched ignoring case), name-ordered.
     */
    public List<Achievement> search(String text, Integer tierNumber, String subcategory, int limit) {
        StringBuilder where = new StringBuilder("WHERE TRUE");
        List<Object> params = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            where.append(" AND LOWER(name) LIKE ?");
            params.add("%" + text.strip().toLowerCase().replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (tierNumber != null) {
            where.append(" AND tier_number = ?");
            params.add(tierNumber);
        }
        if (subcategory != null && !subcategory.isBlank()) {
            where.append(" AND LOWER(subcategory) = LOWER(?)");
            params.add(subcategory.strip());
        }
        return query(where + " ORDER BY LOWER(name)", limit, params.toArray());
    }

    private List<Achievement> query(String whereAndOrder, int limit, Object... params) {
        String sql = "SELECT " + COLUMNS + " FROM younglings.combat_achievement " + whereAndOrder + (whereAndOrder.contains("ORDER BY") ? "" : " ORDER BY LOWER(name)") + " LIMIT " + Math.max(1, limit);
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) s.setObject(i + 1, params[i]);
            List<Achievement> rows = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) rows.add(map(rs));
            }
            return withRequirements(c, rows);
        } catch (SQLException e) {
            throw new RuntimeException("Couldn't read combat achievements", e);
        }
    }

    private static List<Achievement> withRequirements(Connection c, List<Achievement> rows) throws SQLException {
        if (rows.isEmpty()) return rows;
        Map<Long, List<Requirement>> byAchievement = new HashMap<>();
        String ids = rows.stream().map(a -> String.valueOf(a.id())).collect(Collectors.joining(","));
        try (PreparedStatement s = c.prepareStatement("SELECT achievement_id, position, required_id, required_name FROM younglings.combat_achievement_requirement WHERE achievement_id IN (" + ids + ") ORDER BY achievement_id, position");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) byAchievement.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(new Requirement(rs.getInt(2), (Long) rs.getObject(3), rs.getString(4)));
        }
        return rows.stream().map(a -> new Achievement(a.id(), a.name(), a.wikiTitle(), a.wikiUrl(), a.description(), a.members(), a.membersIcon(),
                a.subcategory(), a.subcategoryUrl(), a.subsubcategory(), a.subsubcategoryUrl(), a.tierNumber(), a.tier(), a.tierIcon(), a.combatScore(),
                a.combatScoreIcon(), a.runeScore(), a.runeScoreIcon(), a.wikiSummary(), a.wikiText(), a.infobox(), byAchievement.getOrDefault(a.id(), List.of()))).toList();
    }

    private static Achievement map(ResultSet rs) throws SQLException {
        Map<String, String> infobox = new LinkedHashMap<>();
        DataObject json = DataObject.fromJson(rs.getString("infobox"));
        for (String key : json.keys()) infobox.put(key, json.getString(key));
        return new Achievement(rs.getLong("id"), rs.getString("name"), rs.getString("wiki_title"), rs.getString("wiki_url"), rs.getString("description"),
                rs.getBoolean("members"), rs.getString("members_icon"), rs.getString("subcategory"), rs.getString("subcategory_url"),
                rs.getString("subsubcategory"), rs.getString("subsubcategory_url"), rs.getInt("tier_number"), rs.getString("tier"), rs.getString("tier_icon"),
                rs.getInt("combat_score"), rs.getString("combat_score_icon"), rs.getInt("rune_score"), rs.getString("rune_score_icon"),
                rs.getString("wiki_summary"), rs.getString("wiki_text"), infobox, List.of());
    }
}
