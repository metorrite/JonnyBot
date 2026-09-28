package com.younglings.bot.tracking;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * RuneMetrics' curated "worth announcing" drop list, compiled from
 * <a href="https://runescape.wiki/w/RuneMetrics/Adventurer%27s_Log">the wiki's Adventurer's Log
 * reference</a> — not every item in the game, just the ones RuneScape itself decides are notable
 * enough to log. Matched against an activity's raw text by substring: every phrasing the wiki
 * documents ("I found a &lt;item&gt;.", "I found an &lt;item&gt;", "Whilst plundering..., I looted
 * &lt;item&gt;") contains the item's exact display name somewhere in the sentence, so a substring
 * check works regardless of which template fired. Checked longest name first so, for example,
 * "Off-hand drygore longsword" matches before the shorter "Drygore longsword" would.
 * <p>
 * {@code champions_scrolls} and {@code necromancer_kit} have no icon (the wiki's file naming for
 * those two didn't match any pattern tried) — still classified, just posted without an icon prefix.
 * Two items ({@code dormant_staff_of_sliske}'s wiki-exact name has different casing than expected)
 * needed a manual filename match; noted here so a future re-sync of the icon set isn't surprised by it.
 */
public final class DropItemCatalog {
    public record DropItem(String key, String name) {}

    private static final List<DropItem> ITEMS_BY_LENGTH_DESC;

    static {
        List<DropItem> items = List.of(
                new DropItem("godsword_shard_1", "Godsword shard 1"),
                new DropItem("godsword_shard_2", "Godsword shard 2"),
                new DropItem("godsword_shard_3", "Godsword shard 3"),
                new DropItem("saradomin_hilt", "Saradomin hilt"),
                new DropItem("zamorak_hilt", "Zamorak hilt"),
                new DropItem("saradomin_sword", "Saradomin sword"),
                new DropItem("crest_of_seren", "Crest of Seren"),
                new DropItem("crest_of_sliske", "Crest of Sliske"),
                new DropItem("crest_of_zamorak", "Crest of Zamorak"),
                new DropItem("crest_of_zaros", "Crest of Zaros"),
                new DropItem("saradomins_hiss", "Saradomin's hiss"),
                new DropItem("saradomins_murmur", "Saradomin's murmur"),
                new DropItem("saradomins_whisper", "Saradomin's whisper"),
                new DropItem("armadyl_boots", "Armadyl boots"),
                new DropItem("armadyl_buckler", "Armadyl buckler"),
                new DropItem("armadyl_chainskirt", "Armadyl chainskirt"),
                new DropItem("armadyl_chestplate", "Armadyl chestplate"),
                new DropItem("armadyl_crossbow", "Armadyl crossbow"),
                new DropItem("armadyl_gloves", "Armadyl gloves"),
                new DropItem("armadyl_helmet", "Armadyl helmet"),
                new DropItem("armadyl_hilt", "Armadyl hilt"),
                new DropItem("bandos_boots", "Bandos boots"),
                new DropItem("bandos_chestplate", "Bandos chestplate"),
                new DropItem("bandos_gloves", "Bandos gloves"),
                new DropItem("bandos_helmet", "Bandos helmet"),
                new DropItem("bandos_hilt", "Bandos hilt"),
                new DropItem("bandos_tassets", "Bandos tassets"),
                new DropItem("bandos_warshield", "Bandos warshield"),
                new DropItem("torva_boots", "Torva boots"),
                new DropItem("torva_full_helm", "Torva full helm"),
                new DropItem("torva_gloves", "Torva gloves"),
                new DropItem("torva_platebody", "Torva platebody"),
                new DropItem("torva_platelegs", "Torva platelegs"),
                new DropItem("virtus_book", "Virtus book"),
                new DropItem("virtus_boots", "Virtus boots"),
                new DropItem("virtus_gloves", "Virtus gloves"),
                new DropItem("virtus_mask", "Virtus mask"),
                new DropItem("virtus_robe_legs", "Virtus robe legs"),
                new DropItem("virtus_robe_top", "Virtus robe top"),
                new DropItem("virtus_wand", "Virtus wand"),
                new DropItem("pernix_body", "Pernix body"),
                new DropItem("pernix_boots", "Pernix boots"),
                new DropItem("pernix_chaps", "Pernix chaps"),
                new DropItem("pernix_cowl", "Pernix cowl"),
                new DropItem("pernix_gloves", "Pernix gloves"),
                new DropItem("boots_of_subjugation", "Boots of subjugation"),
                new DropItem("garb_of_subjugation", "Garb of subjugation"),
                new DropItem("gloves_of_subjugation", "Gloves of subjugation"),
                new DropItem("gown_of_subjugation", "Gown of subjugation"),
                new DropItem("hood_of_subjugation", "Hood of subjugation"),
                new DropItem("ward_of_subjugation", "Ward of subjugation"),
                new DropItem("dormant_anima_core_body", "Dormant anima core body"),
                new DropItem("dormant_anima_core_helm", "Dormant anima core helm"),
                new DropItem("dormant_anima_core_legs", "Dormant anima core legs"),
                new DropItem("dormant_seren_godbow", "Dormant Seren godbow"),
                new DropItem("dormant_zaros_godsword", "Dormant Zaros godsword"),
                new DropItem("orb_of_corrupted_anima", "Orb of corrupted anima"),
                new DropItem("orb_of_pure_anima", "Orb of pure anima"),
                new DropItem("orb_of_volcanic_anima", "Orb of volcanic anima"),
                new DropItem("drygore_longsword", "Drygore longsword"),
                new DropItem("drygore_mace", "Drygore mace"),
                new DropItem("drygore_rapier", "Drygore rapier"),
                new DropItem("off_hand_drygore_longsword", "Off-hand drygore longsword"),
                new DropItem("off_hand_drygore_mace", "Off-hand drygore mace"),
                new DropItem("off_hand_drygore_rapier", "Off-hand drygore rapier"),
                new DropItem("draconic_visage", "Draconic visage"),
                new DropItem("dragon_boots", "Dragon boots"),
                new DropItem("dragon_chainbody", "Dragon chainbody"),
                new DropItem("dragon_claw", "Dragon claw"),
                new DropItem("dragon_full_helm", "Dragon full helm"),
                new DropItem("dragon_hatchet", "Dragon hatchet"),
                new DropItem("dragon_helm", "Dragon helm"),
                new DropItem("dragon_kiteshield", "Dragon kiteshield"),
                new DropItem("dragon_limbs", "Dragon limbs"),
                new DropItem("dragon_pickaxe", "Dragon pickaxe"),
                new DropItem("dragon_platelegs", "Dragon platelegs"),
                new DropItem("dragon_plateskirt", "Dragon plateskirt"),
                new DropItem("dragonbone_upgrade_kit", "Dragonbone upgrade kit"),
                new DropItem("ruined_dragon_armour_lump", "Ruined dragon armour lump"),
                new DropItem("ruined_dragon_armour_shard", "Ruined dragon armour shard"),
                new DropItem("ruined_dragon_armour_slice", "Ruined dragon armour slice"),
                new DropItem("crystal_triskelion_fragment_1", "Crystal triskelion fragment 1"),
                new DropItem("crystal_triskelion_fragment_2", "Crystal triskelion fragment 2"),
                new DropItem("crystal_triskelion_fragment_3", "Crystal triskelion fragment 3"),
                new DropItem("blood_necklace_shard", "Blood necklace shard"),
                new DropItem("shield_left_half", "Shield left half"),
                new DropItem("demon_slayer_boots", "Demon slayer boots"),
                new DropItem("demon_slayer_circlet", "Demon slayer circlet"),
                new DropItem("demon_slayer_crossbow", "Demon slayer crossbow"),
                new DropItem("demon_slayer_gloves", "Demon slayer gloves"),
                new DropItem("demon_slayer_skirt", "Demon slayer skirt"),
                new DropItem("demon_slayer_torso", "Demon slayer torso"),
                new DropItem("abyssal_orb", "Abyssal orb"),
                new DropItem("abyssal_wand", "Abyssal wand"),
                new DropItem("abyssal_whip", "Abyssal whip"),
                new DropItem("amulet_of_ranging", "Amulet of ranging"),
                new DropItem("araxxis_eye", "Araxxi's eye"),
                new DropItem("araxxis_fang", "Araxxi's fang"),
                new DropItem("araxxis_web", "Araxxi's web"),
                new DropItem("arcane_sigil", "Arcane sigil"),
                new DropItem("archers_ring", "Archers' ring"),
                new DropItem("ascension_grips", "Ascension grips"),
                new DropItem("berserker_ring", "Berserker ring"),
                new DropItem("blade_of_avaryss", "Blade of Avaryss"),
                new DropItem("blade_of_nymora", "Blade of Nymora"),
                new DropItem("celestial_handwraps", "Celestial handwraps"),
                new DropItem("cresbot", "Cresbot"),
                new DropItem("dark_bow", "Dark bow"),
                new DropItem("divine_sigil", "Divine sigil"),
                new DropItem("elysian_sigil", "Elysian sigil"),
                new DropItem("focus_sight", "Focus sight"),
                new DropItem("glaiven_boots", "Glaiven boots"),
                new DropItem("granite_legs", "Granite legs"),
                new DropItem("granite_maul", "Granite maul"),
                new DropItem("hexcrest", "Hexcrest"),
                new DropItem("kalgerion_battle_commendation", "Kal'gerion battle commendation"),
                new DropItem("leaf_bladed_sword", "Leaf-bladed sword"),
                new DropItem("malevolent_kiteshield", "Malevolent kiteshield"),
                new DropItem("merciless_kiteshield", "Merciless kiteshield"),
                new DropItem("pneumatic_gloves", "Pneumatic gloves"),
                new DropItem("ragefire_boots", "Ragefire boots"),
                new DropItem("razorback_gauntlets", "Razorback gauntlets"),
                new DropItem("reprisal_ability_codex", "Reprisal ability codex"),
                new DropItem("seers_ring", "Seers' ring"),
                new DropItem("seismic_singularity", "Seismic singularity"),
                new DropItem("seismic_wand", "Seismic wand"),
                new DropItem("spectral_sigil", "Spectral sigil"),
                new DropItem("spider_leg_top", "Spider leg top"),
                new DropItem("spider_leg_middle", "Spider leg middle"),
                new DropItem("spider_leg_bottom", "Spider leg bottom"),
                new DropItem("staff_of_light", "Staff of light"),
                new DropItem("starved_ancient_effigy", "Starved ancient effigy"),
                new DropItem("static_gloves", "Static gloves"),
                new DropItem("steadfast_boots", "Steadfast boots"),
                new DropItem("steam_battlestaff", "Steam battlestaff"),
                new DropItem("tracking_gloves", "Tracking gloves"),
                new DropItem("vengeful_kiteshield", "Vengeful kiteshield"),
                new DropItem("warrior_ring", "Warrior ring"),
                new DropItem("whip_vine", "Whip vine"),
                new DropItem("wyrm_scalp", "Wyrm scalp"),
                new DropItem("wyrm_spike", "Wyrm spike"),
                new DropItem("zaryte_bow", "Zaryte bow"),
                new DropItem("dormant_staff_of_sliske", "Dormant Staff of Sliske"),
                new DropItem("champions_scrolls", "Champion's scrolls"),
                new DropItem("necromancer_kit", "Necromancer kit")
        );
        ITEMS_BY_LENGTH_DESC = items.stream()
                .sorted(Comparator.comparingInt((DropItem i) -> i.name().length()).reversed())
                .toList();
    }

    private DropItemCatalog() {}

    /** The longest-matching catalog item whose name appears in {@code text}, if any. */
    public static Optional<DropItem> findIn(String text) {
        for (DropItem item : ITEMS_BY_LENGTH_DESC) {
            if (text.contains(item.name())) return Optional.of(item);
        }
        return Optional.empty();
    }

    public static List<DropItem> all() {
        return ITEMS_BY_LENGTH_DESC;
    }
}
