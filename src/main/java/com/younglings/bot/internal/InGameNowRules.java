package com.younglings.bot.internal;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules behind the clan site's "In game now" widget (T-586), pure so they are tested without a database or Discord.
 * <ul>
 *   <li><b>Who counts as in the game</b>: RuneSync checked in within {@link #WINDOW}. RuneSync asks for its settings on login and then about
 *   every 10 minutes, and every batch it sends counts too; logging out (or closing the client) sends nothing, so the window is the heartbeat
 *   timeout. The same 11 minutes as the event team's {@code [in game]} (RuneSyncPanelService).</li>
 *   <li><b>Only members who chose it</b>: the Presence category (their own consent, off by default) and RuneSync not paused.</li>
 *   <li><b>Only the clan</b>: a member of the bot's server, or (for a site whose server doesn't have the bot) a member whose RuneLink name is
 *   on the clan's roster.</li>
 *   <li><b>Only sites that placed the widget</b>: the bot answers names only while the published layout holds an {@code in-game-now} widget,
 *   and says who the site lets see them ({@code members}, the default, or {@code public}); the website enforces that per viewer.</li>
 * </ul>
 * No world number: the game's plugin API has none (RuneSync docs/DATA-CATALOGUE.md 3.9 and 3.10).
 */
public final class InGameNowRules {
    private InGameNowRules() {}

    /** The widget's type in the site layout. */
    public static final String WIDGET_TYPE = "in-game-now";
    /** How long after RuneSync's last check-in a member still counts as in the game (the heartbeat timeout). */
    public static final Duration WINDOW = Duration.ofMinutes(11);
    /** The most names one answer holds. */
    public static final int MAX_NAMES = 100;

    /**
     * Who the site lets see the list, from its published layout: null when no page has an {@code in-game-now} widget (the bot then names nobody),
     * {@code public} when any placed widget is set to everyone, else {@code members}.
     */
    public static String visibility(DataObject layout) {
        if (layout == null) return null;
        List<DataObject> found = new ArrayList<>();
        collect(layout, found, 0);
        if (found.isEmpty()) return null;
        for (DataObject w : found) {
            DataObject settings = w.isType("settings", DataType.OBJECT) ? w.getObject("settings") : DataObject.empty();
            if ("public".equals(settings.getString("who", "members"))) return "public";
        }
        return "members";
    }

    private static void collect(DataObject node, List<DataObject> found, int depth) {
        if (depth > 12) return;
        if (WIDGET_TYPE.equals(node.getString("type", "")) && node.hasKey("id")) found.add(node);
        for (String key : node.keys()) {
            if (node.isType(key, DataType.OBJECT)) collect(node.getObject(key), found, depth + 1);
            else if (node.isType(key, DataType.ARRAY)) collect(node.getArray(key), found, depth + 1);
        }
    }

    private static void collect(DataArray array, List<DataObject> found, int depth) {
        for (int i = 0; i < array.length(); i++) {
            if (array.isType(i, DataType.OBJECT)) collect(array.getObject(i), found, depth + 1);
            else if (array.isType(i, DataType.ARRAY)) collect(array.getArray(i), found, depth + 1);
        }
    }

    /** Lower-cased, trimmed names, for matching against the roster. */
    public static Set<String> lower(Collection<String> names) {
        Set<String> out = new LinkedHashSet<>();
        for (String n : names) if (n != null && !n.isBlank()) out.add(key(n));
        return out;
    }

    /** A RuneScape name as a lookup key: lower case, and space, underscore and dash alike. */
    public static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    /**
     * Whether a present member belongs to this clan: in the bot's server, or one of their RuneLink names is on the roster (the only test a site
     * without the bot has).
     */
    public static boolean inClan(boolean inServer, Collection<String> rsns, Set<String> rosterKeys) {
        if (inServer) return true;
        for (String r : rsns) if (r != null && rosterKeys.contains(key(r))) return true;
        return false;
    }

    /**
     * The RuneScape name the list shows, from the member's names in this order: the character RuneSync checked in with (its RuneLink name), the
     * names linked in this server, their other linked names. The first that is on the clan's roster wins (in the roster's spelling), else the
     * first at all; null when they have none. Only RuneScape names are ever shown, never a Discord name or id: a server member without one is
     * only counted ({@code unnamed}).
     */
    public static String displayName(List<String> names, List<String> roster) {
        for (String n : names) {
            if (n == null || n.isBlank()) continue;
            for (String r : roster) if (r != null && key(r).equals(key(n))) return r.strip();
        }
        for (String n : names) if (n != null && !n.isBlank()) return n.strip();
        return null;
    }

    /** Names sorted A to Z (case-insensitive), each once, at most {@link #MAX_NAMES}. */
    public static List<String> sorted(Collection<String> names) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        names.stream().filter(n -> n != null && !n.isBlank()).sorted(Comparator.comparing(n -> n.toLowerCase(Locale.ROOT))).forEach(n -> {
            if (seen.add(key(n)) && out.size() < MAX_NAMES) out.add(n);
        });
        return out;
    }

    /** The answer for a site that hasn't placed the widget (or no longer has it): nobody is named. */
    public static DataObject notPlaced() {
        return DataObject.empty().put("available", true).put("placed", false).put("visibility", "members")
                .put("windowMinutes", WINDOW.toMinutes()).put("count", 0).put("unnamed", 0).put("members", DataArray.empty());
    }

    /** The answer with these names, plus {@code unnamed} clan members in the game who have no RuneScape name to show. */
    public static DataObject answer(String visibility, List<String> names, int unnamed) {
        DataArray members = DataArray.empty();
        for (String n : names) members.add(DataObject.empty().put("name", n));
        return DataObject.empty().put("available", true).put("placed", true).put("visibility", visibility)
                .put("windowMinutes", WINDOW.toMinutes()).put("count", names.size() + unnamed).put("unnamed", unnamed).put("members", members);
    }
}
