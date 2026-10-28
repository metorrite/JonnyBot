package com.runeclans.bot.internal;

import com.runeclans.bot.events.BingoTiles;
import com.runeclans.bot.events.PluginObjectiveMatcher;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.util.function.Supplier;

/**
 * The bingo editor's "auto with RuneSync" hints ({@code POST plugin-tile-hints}, T-141, PLUGIN-ERA-FEATURES.md section 2): for the tile
 * labels being typed, whether RuneSync can tick each one by itself and why, from the same rules that tick tiles
 * ({@link PluginObjectiveMatcher#explain}). Read-only: nothing is saved. Body {@code {"template": "bingo", "labels": ["Nex: Torva boots", ...]}},
 * answer {@code {"hints": [{"label", "auto", "text"}]}} in the same order (a label with no hint has {@code auto} false and no text).
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class PluginTileHintsAdminApi implements AdminRouteExtension {
    static final int MAX_LABELS = 100;
    static final int MAX_LABEL_LENGTH = 200;

    private final BingoTiles tiles;

    public PluginTileHintsAdminApi(BingoTiles tiles) {
        this.tiles = tiles;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("plugin-tile-hints")) return null;
        AdminRouteExtension.requireMethod(method, "POST");
        return hints(body.get(), tiles::resolve, tiles::knowsItem);
    }

    static DataObject hints(DataObject body, PluginObjectiveMatcher.Labels labels, java.util.function.Function<String, Boolean> knownItem) {
        String template = body.getString("template", "");
        DataArray given;
        try {
            given = body.getArray("labels");
        } catch (RuntimeException e) {
            throw AdminRouteExtension.refuse(400, "Send the tile labels as a list.");
        }
        if (given.length() > MAX_LABELS) throw AdminRouteExtension.refuse(400, "At most " + MAX_LABELS + " labels at a time.");
        DataArray out = DataArray.empty();
        for (int i = 0; i < given.length(); i++) {
            if (!given.isType(i, DataType.STRING)) throw AdminRouteExtension.refuse(400, "Every label must be text.");
            String label = given.getString(i);
            if (label.length() > MAX_LABEL_LENGTH) label = label.substring(0, MAX_LABEL_LENGTH);
            PluginObjectiveMatcher.Hint hint = PluginObjectiveMatcher.explain(template, label, labels, knownItem);
            out.add(DataObject.empty().put("label", label).put("auto", hint != null && hint.auto()).put("text", hint == null ? null : hint.text()));
        }
        return DataObject.empty().put("hints", out);
    }
}
