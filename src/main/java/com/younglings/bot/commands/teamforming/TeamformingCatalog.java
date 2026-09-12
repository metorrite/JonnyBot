package com.younglings.bot.commands.teamforming;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Static definition of every teamforming role, grouped into the toggle button(s) shown at the
 * top of the panel and the per-boss dropdown sections below it. This is the single source of
 * truth for role names: {@link TeamformingService} uses it to create/find/delete roles, and
 * {@link TeamformingCommand} / {@link TeamformingInteractionListener} use it to build the panel
 * and route interactions back to a role.
 * <p>
 * To add, rename, or remove a teamforming tag, edit this file only — nothing else needs to
 * change. Note the total component budget: the panel is built as a single Components V2 message
 * (see {@link TeamformingService#buildPanelMessage()}), capped by Discord at 40 components in the
 * tree. Each section costs ~3 (header text + a select-menu row), so there's room for a handful
 * more sections/options before that limit needs to be worked around (e.g. splitting into two
 * messages).
 */
public final class TeamformingCatalog {

    public static final List<TeamformingToggle> TOGGLES = List.of(
            new TeamformingToggle("🐗", "Monthly Mass", "Monthly Mass")
    );

    public static final List<TeamformingSection> SECTIONS = List.of(
            new TeamformingSection(
                    "amascut", "🪲", "Amascut, the Devourer",
                    "Select the matchmaking tag(s) for teamforming to face the Devourer:",
                    "Pick an Amascut Role",
                    List.of(
                            new TeamformingOption("Amascut",
                                    "General teamforming tag for Amascut, the Devourer.", "Amascut"),
                            new TeamformingOption("Amascut Low Enrage",
                                    "Teams for 100%, 500% or 750% enrage version of this boss.", "Amascut Low Enrage"),
                            new TeamformingOption("Amascut High Enrage",
                                    "Teams for 1,000%, 2,000% or 4,000% version of the boss.", "Amascut High Enrage")
                    )
            ),
            new TeamformingSection(
                    "raids", "🏛️", "Liberations of Mazcab",
                    "Select the matchmaking tag(s) for teamforming to face Yakamaru and Beastmaster Durzag:",
                    "Pick a Raids Role",
                    List.of(
                            new TeamformingOption("Full Raids",
                                    "Teams for a Full Raid of these bosses.", "Full Raids"),
                            new TeamformingOption("Raids Farm",
                                    "Teams for Pet Hunting hours.", "Raids Farm")
                    )
            ),
            new TeamformingSection(
                    "aod", "🐉", "AOD",
                    "Select the matchmaking tag for AOD:",
                    "Pick an AOD Role",
                    List.of(
                            new TeamformingOption("AOD",
                                    "General teamforming tag for AOD.", "AOD")
                    )
            ),
            new TeamformingSection(
                    "solak", "🌳", "Solak, Guardian of the Grove",
                    "Select the matchmaking tag(s) for Solak, Guardian of the Grove:",
                    "Pick a Solak Role",
                    List.of(
                            new TeamformingOption("Solak",
                                    "General teamforming tag for Solak, Guardian of the Grove.", "Solak")
                    )
            ),
            new TeamformingSection(
                    "vorago", "👹", "Vorago",
                    "Select the matchmaking tag(s) for Vorago:",
                    "Pick a Vorago Role",
                    List.of(
                            new TeamformingOption("Vorago",
                                    "General teamforming tag for Vorago.", "Vorago"),
                            new TeamformingOption("Vorago HM",
                                    "General teamforming tag for Hard Mode.", "Vorago HM"),
                            new TeamformingOption("Vorago Duo",
                                    "A teamforming tag for teams of two at Vorago.", "Vorago Duo")
                    )
            ),
            new TeamformingSection(
                    "zamorak", "🔥", "Zamorak, Lord of Chaos",
                    "Select matchmaking tag(s) for Zamorak, Lord of Chaos:",
                    "Pick a Zamorak tag",
                    List.of(
                            new TeamformingOption("Zamorak",
                                    "Normal Mode or Lower Enrages.", "Zamorak"),
                            new TeamformingOption("Zamorak 500%",
                                    "500% Enrage at Zamorak, Lord of Chaos.", "Zamorak 500%"),
                            new TeamformingOption("Zamorak 1000%",
                                    "1,000% Enrage at Zamorak, Lord of Chaos.", "Zamorak 1000%"),
                            new TeamformingOption("Zamorak High Enrage",
                                    "High Enrage kills above 1,000%.", "Zamorak High Enrage")
                    )
            ),
            new TeamformingSection(
                    "group", "⚔️", "Group Encounters",
                    "Select the matchmaking tag(s) for other group encounters:",
                    "Pick a Group Boss Role",
                    List.of(
                            new TeamformingOption("Elite Dungeons",
                                    "Teamforming for Elite Dungeon(s) 1-3.", "Elite Dungeons"),
                            new TeamformingOption("Kalphite King",
                                    "Teamforming for Kalphite King.", "Kalphite King"),
                            new TeamformingOption("ROTS",
                                    "Teamforming for Barrows: Rise of the Six.", "ROTS"),
                            new TeamformingOption("Croesus",
                                    "Skilling Boss tag for Croesus and the Gate of Elidinis.", "Croesus"),
                            new TeamformingOption("Sanctum of Rebirth",
                                    "Teamforming for the Sanctum of Rebirth.", "Sanctum of Rebirth")
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
