package com.younglings.bot.commands.teamforming;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static definition of every teamforming role, grouped into the toggle button(s) shown at the
 * top of the panel and the per-boss dropdown sections below it. This is the single source of
 * truth for role names and colors: {@link TeamformingService} uses it to create/find/delete/color
 * roles, and {@link TeamformingCommand} / {@link TeamformingInteractionListener} use it to build
 * the panels and route interactions back to a role.
 * <p>
 * {@link #SECTIONS} is kept in alphabetical order by {@link TeamformingSection#title()} — insert
 * new entries in the right place to keep it that way. To add, rename, recolor, or remove a
 * teamforming tag, edit this file only — nothing else needs to change. Note the total component
 * budget: the personal panel is built as a single Components V2 message (see
 * {@link TeamformingService#buildPersonalPanelComponents}), capped by Discord at 40 components in
 * the tree — there's limited room for more sections/options before that needs to be worked around
 * (e.g. splitting into two messages).
 */
public final class TeamformingCatalog {

    // Colors are a loose per-boss guideline, not exact/unique per option — see the "Group
    // Encounters" section below for cases sharing a color family with another section, nudged to
    // a distinct shade so they don't render identically in the member's role list.
    private static final Color AMASCUT_GREEN = new Color(0x2E, 0xCC, 0x71); // Emerald
    private static final Color RAIDS_YELLOW = new Color(0xF1, 0xC4, 0x0F);  // Sunflower
    private static final Color NEX_RED = new Color(0xD6, 0x28, 0x39);       // deep crimson (deeper/more visible than before)
    private static final Color SOLAK_GREEN = new Color(0x16, 0xA0, 0x85);   // sea green (distinct from Amascut)
    private static final Color VORAGO_ORANGE = new Color(0xE6, 0x7E, 0x22); // carrot orange
    private static final Color ZAMORAK_RED = new Color(0x8B, 0x00, 0x00);   // deep dark red (deeper/more visible than before, distinct from Nex)
    private static final Color ELITE_DUNGEONS_PURPLE = new Color(0x9B, 0x59, 0xB6); // amethyst
    private static final Color KALPHITE_KING_ORANGE = new Color(0xD3, 0x54, 0x00);  // burnt orange (distinct from Vorago)
    private static final Color ROTS_PURPLE = new Color(0x8E, 0x44, 0xAD);           // wisteria (distinct from Elite Dungeons)
    private static final Color CROESUS_GREEN = new Color(0x27, 0xAE, 0x60);         // nephritis (distinct from Amascut/Solak)
    private static final Color SANCTUM_BLUE = new Color(0x34, 0x98, 0xDB);          // peter river
    private static final Color MONTHLY_MASS_BROWN = new Color(0xA0, 0x52, 0x2D);    // sienna — not specified, our own pick

    public static final List<TeamformingToggle> TOGGLES = List.of(
            new TeamformingToggle("🐗", "Monthly Mass", "Monthly Mass", MONTHLY_MASS_BROWN)
    );

    public static final List<TeamformingSection> SECTIONS = List.of(
            new TeamformingSection(
                    "amascut", "🪲", "Amascut, the Devourer",
                    "Select the matchmaking tag(s) for teamforming to face the Devourer:",
                    "Pick an Amascut Role", AMASCUT_GREEN,
                    List.of(
                            new TeamformingOption("Amascut",
                                    "General teamforming tag for Amascut, the Devourer.", "Amascut", null),
                            new TeamformingOption("Amascut Low Enrage",
                                    "Teams for 100%, 500% or 750% enrage version of this boss.", "Amascut Low Enrage", null),
                            new TeamformingOption("Amascut High Enrage",
                                    "Teams for 1,000%, 2,000% or 4,000% version of the boss.", "Amascut High Enrage", null)
                    )
            ),
            new TeamformingSection(
                    "group", "⚔️", "Group Encounters",
                    "Select the matchmaking tag(s) for other group encounters:",
                    "Pick a Group Boss Role", null,
                    List.of(
                            new TeamformingOption("Elite Dungeons",
                                    "Teamforming for Elite Dungeon(s) 1-3.", "Elite Dungeons", ELITE_DUNGEONS_PURPLE),
                            new TeamformingOption("Kalphite King",
                                    "Teamforming for Kalphite King.", "Kalphite King", KALPHITE_KING_ORANGE),
                            new TeamformingOption("ROTS",
                                    "Teamforming for Barrows: Rise of the Six.", "ROTS", ROTS_PURPLE),
                            new TeamformingOption("Croesus",
                                    "Skilling Boss tag for Croesus and the Gate of Elidinis.", "Croesus", CROESUS_GREEN),
                            new TeamformingOption("Sanctum of Rebirth",
                                    "Teamforming for the Sanctum of Rebirth.", "Sanctum of Rebirth", SANCTUM_BLUE)
                    )
            ),
            new TeamformingSection(
                    "raids", "🏛️", "Liberations of Mazcab",
                    "Select the matchmaking tag(s) for teamforming to face Yakamaru and Beastmaster Durzag:",
                    "Pick a Raids Role", RAIDS_YELLOW,
                    List.of(
                            new TeamformingOption("Full Raids",
                                    "Teams for a Full Raid of these bosses.", "Full Raids", null),
                            new TeamformingOption("Raids Farm",
                                    "Teams for Pet Hunting hours.", "Raids Farm", null)
                    )
            ),
            new TeamformingSection(
                    // Display title is "Nex, Angel of Death" per the repo owner's request; the tag
                    // (role name), option label, and description all intentionally stay "AOD".
                    "aod", "🐉", "Nex, Angel of Death",
                    "Select the matchmaking tag for AOD:",
                    "Pick an AOD Role", NEX_RED,
                    List.of(
                            new TeamformingOption("AOD",
                                    "General teamforming tag for AOD.", "AOD", null)
                    )
            ),
            new TeamformingSection(
                    "solak", "🌳", "Solak, Guardian of the Grove",
                    "Select the matchmaking tag(s) for Solak, Guardian of the Grove:",
                    "Pick a Solak Role", SOLAK_GREEN,
                    List.of(
                            new TeamformingOption("Solak",
                                    "General teamforming tag for Solak, Guardian of the Grove.", "Solak", null)
                    )
            ),
            new TeamformingSection(
                    "vorago", "👹", "Vorago",
                    "Select the matchmaking tag(s) for Vorago:",
                    "Pick a Vorago Role", VORAGO_ORANGE,
                    List.of(
                            new TeamformingOption("Vorago",
                                    "General teamforming tag for Vorago.", "Vorago", null),
                            new TeamformingOption("Vorago HM",
                                    "General teamforming tag for Hard Mode.", "Vorago HM", null),
                            new TeamformingOption("Vorago Duo",
                                    "A teamforming tag for teams of two at Vorago.", "Vorago Duo", null)
                    )
            ),
            new TeamformingSection(
                    "zamorak", "🔥", "Zamorak, Lord of Chaos",
                    "Select matchmaking tag(s) for Zamorak, Lord of Chaos:",
                    "Pick a Zamorak tag", ZAMORAK_RED,
                    List.of(
                            new TeamformingOption("Zamorak",
                                    "Normal Mode or Lower Enrages.", "Zamorak", null),
                            new TeamformingOption("Zamorak 500%",
                                    "500% Enrage at Zamorak, Lord of Chaos.", "Zamorak 500%", null),
                            new TeamformingOption("Zamorak 1000%",
                                    "1,000% Enrage at Zamorak, Lord of Chaos.", "Zamorak 1000%", null),
                            new TeamformingOption("Zamorak High Enrage",
                                    "High Enrage kills above 1,000%.", "Zamorak High Enrage", null)
                    )
            )
    );

    private TeamformingCatalog() {}

    /** Finds the section with the given key, or {@code null} if none matches. */
    public static TeamformingSection sectionByKey(String key) {
        for (TeamformingSection section : SECTIONS) {
            if (section.key().equals(key)) return section;
        }
        return null;
    }

    /** The section that owns this role name, or {@code null} if it's a toggle role (or unknown). */
    public static TeamformingSection sectionForRoleName(String roleName) {
        for (TeamformingSection section : SECTIONS) {
            for (TeamformingOption option : section.options()) {
                if (option.roleName().equals(roleName)) return section;
            }
        }
        return null;
    }

    /** The color to create/recolor a role with, or {@code null} for Discord's default (no color). */
    public static Color colorForRoleName(String roleName) {
        for (TeamformingToggle toggle : TOGGLES) {
            if (toggle.roleName().equals(roleName)) return toggle.color();
        }
        for (TeamformingSection section : SECTIONS) {
            for (TeamformingOption option : section.options()) {
                if (option.roleName().equals(roleName)) {
                    return option.color() != null ? option.color() : section.color();
                }
            }
        }
        return null;
    }

    /** Every role name this feature owns, across the toggle buttons and every section's options. */
    public static Set<String> allRoleNames() {
        Set<String> names = new LinkedHashSet<>();
        for (TeamformingToggle toggle : TOGGLES) names.add(toggle.roleName());
        for (TeamformingSection section : SECTIONS) {
            for (TeamformingOption option : section.options()) {
                names.add(option.roleName());
            }
        }
        return names;
    }
}
