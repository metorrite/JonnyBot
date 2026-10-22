package com.younglings.bot.events;

import com.younglings.bot.events.EventRepository.Progress;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Source;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A bingo board as RuneClans shows it (T-336): the tiles (number, label, points, which tiles must be done first), the shape for a shaped board, and, for
 * whoever the page may show, which tiles each team or player has done and which of those the RuneSync plugin ticked by itself ({@link Source#AUTO}).
 * Never proof pictures or who sent them: Discord's picture links expire and screenshots show player names. Pure.
 */
public final class HubBoard {
    private HubBoard() {}

    /** A team ({@code t:<id>}) or player ({@code u:<id>}) whose progress the page may show, with the name it may show them by. */
    public record Subject(String key, String name, long points, Map<String, Integer> completions) {}

    /** The board of an event, or null when it has none. {@code rows} are the subjects in standings order; {@code mine} is the viewer's own, or null. */
    public static DataObject of(DataObject config, List<ObjectiveSpec> objectives, List<Subject> rows, Subject mine, List<Progress> progress, boolean teams) {
        List<String> keys = BingoBoardView.boardKeys(config);
        if (keys.isEmpty()) return null;
        Map<String, Integer> numberOf = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) numberOf.put(keys.get(i), i + 1);
        Map<String, ObjectiveSpec> byKey = new HashMap<>();
        objectives.forEach(o -> byKey.put(o.key(), o));

        DataArray tiles = DataArray.empty();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            boolean free = BingoBoardOptions.FREE_KEY.equals(key);
            ObjectiveSpec o = byKey.get(key);
            DataArray needs = DataArray.empty();
            if (o != null) o.prerequisites().stream().map(numberOf::get).filter(n -> n != null).sorted().forEach(needs::add);
            tiles.add(DataObject.empty().put("n", i + 1).put("label", free ? "FREE" : o == null ? key : o.label()).put("points", o == null ? 0 : o.points())
                    .put("free", free).put("needs", needs));
        }

        Map<String, Set<String>> autoBy = autoTicked(progress, teams);
        DataArray shown = DataArray.empty();
        for (Subject s : rows) shown.add(subject(s, keys, autoBy).put("name", s.name()).put("points", s.points()));

        DataObject out = DataObject.empty().put("columns", BingoBoardView.boardSize(config)).put("tiles", tiles).put("rows", shown)
                .put("mine", mine == null ? null : subject(mine, keys, autoBy).put("points", mine.points()));
        out.put("layout", layout(config).map(HubBoard::layoutJson).orElse(null));
        return out;
    }

    /** Which objectives each subject had ticked by the plugin. */
    static Map<String, Set<String>> autoTicked(List<Progress> progress, boolean teams) {
        Map<String, Set<String>> out = new HashMap<>();
        for (Progress p : progress) {
            if (p.source() != Source.AUTO) continue;
            String subject = teams ? (p.teamId() == null ? null : "t:" + p.teamId()) : "u:" + p.userId();
            if (subject != null) out.computeIfAbsent(subject, k -> new HashSet<>()).add(p.objectiveKey());
        }
        return out;
    }

    private static DataObject subject(Subject s, List<String> keys, Map<String, Set<String>> autoBy) {
        DataArray done = DataArray.empty();
        DataArray auto = DataArray.empty();
        Set<String> autoKeys = autoBy.getOrDefault(s.key(), Set.of());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (BingoBoardOptions.FREE_KEY.equals(key)) continue;
            if (s.completions().getOrDefault(key, 0) > 0) {
                done.add(i + 1);
                if (autoKeys.contains(key)) auto.add(i + 1);
            }
        }
        return DataObject.empty().put("done", done).put("auto", auto);
    }

    private static Optional<BingoLayout> layout(DataObject config) {
        try {
            if (!config.hasKey("board") || config.isNull("board")) return Optional.empty();
            DataObject board = config.getObject("board");
            if (!board.hasKey("layout") || board.isNull("layout")) return Optional.empty();
            return BingoLayout.of(board.getString("layout", null), board.getInt("shapeSize", 0));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static DataObject layoutJson(BingoLayout layout) {
        DataArray rows = DataArray.empty();
        for (List<Integer> row : layout.rows()) {
            DataArray r = DataArray.empty();
            row.forEach(r::add);
            rows.add(r);
        }
        DataArray names = DataArray.empty();
        layout.rowNames().forEach(names::add);
        return DataObject.empty().put("id", layout.id()).put("name", layout.name()).put("rule", layout.rule()).put("rows", rows).put("rowNames", names);
    }
}
