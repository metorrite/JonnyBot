package com.younglings.bot.events;

import com.younglings.bot.events.BingoTilePool.Difficulty;
import com.younglings.bot.events.BingoTilePool.Entry;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives a new bingo event the server's own tiles when its settings say {@code board.pool = "custom"}: the enabled tiles are copied into the event
 * ({@code board.tiles}) at creation, so changing the pool afterwards never changes an event that is already made. Optional filters
 * {@code board.poolDifficulties} (a list of difficulty names) and {@code board.poolTags} (every one must be on a tile) narrow what is copied.
 * Everything else about an event is untouched, and a server that never fills its pool never sees any difference.
 */
@BService
public class BingoPools {
    private final BingoTileRepository repository;

    public BingoPools(BingoTileRepository repository) {
        this.repository = repository;
    }

    /** {@code config} with the server's tiles filled in if it asks for them and has not got any yet; otherwise {@code config} itself. */
    public DataObject expand(long guildId, String template, DataObject config) {
        if (!BingoTemplate.KEY.equals(template) || config == null || !config.hasKey("board") || config.isNull("board")) return config;
        DataObject board;
        try {
            board = config.getObject("board");
        } catch (RuntimeException e) {
            return config; // the template reports a board it can't read
        }
        if (!BingoBoard.POOL_CUSTOM.equals(board.getString("pool", "")) || (board.hasKey("tiles") && !board.isNull("tiles"))) return config;

        List<Entry> chosen = BingoTilePool.select(repository.list(guildId).stream().map(BingoTileRepository.Stored::entry).toList(), difficulties(board), tags(board));
        DataObject copy = DataObject.fromJson(config.toString());
        DataObject newBoard = copy.getObject("board");
        newBoard.put("tiles", BingoTilePool.toConfig(chosen));
        newBoard.remove("poolDifficulties");
        newBoard.remove("poolTags");
        copy.put("board", newBoard);
        return copy;
    }

    private static Set<Difficulty> difficulties(DataObject board) {
        Set<Difficulty> set = EnumSet.noneOf(Difficulty.class);
        if (board.hasKey("poolDifficulties") && !board.isNull("poolDifficulties")) {
            DataArray array = board.getArray("poolDifficulties");
            for (int i = 0; i < array.length(); i++) {
                Difficulty d = Difficulty.parse(array.getString(i, ""));
                if (d != null) set.add(d);
            }
        }
        return set;
    }

    private static Set<String> tags(DataObject board) {
        Set<String> set = new HashSet<>();
        if (board.hasKey("poolTags") && !board.isNull("poolTags")) {
            DataArray array = board.getArray("poolTags");
            for (int i = 0; i < array.length(); i++) set.add(array.getString(i, "").strip().toLowerCase(java.util.Locale.ROOT));
        }
        set.remove("");
        return set;
    }
}
