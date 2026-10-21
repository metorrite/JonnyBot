package com.younglings.bot.internal;

import com.younglings.bot.events.BingoStarterPacks;
import com.younglings.bot.events.BingoTilePool;
import com.younglings.bot.events.BingoTilePool.Difficulty;
import com.younglings.bot.events.BingoTilePool.Entry;
import com.younglings.bot.events.BingoTileRepository;
import com.younglings.bot.events.BingoTileRepository.Stored;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The dashboard's Bingo tile pool: a server's own list of bingo tiles (name, points, how many times, difficulty, tags, on or off), filled by hand or from
 * a starter pack made from public wiki content. An event made with "Your tile pool" takes a copy when it is created. Empty and unused until a server
 * adds tiles. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 * <ul>
 *     <li>{@code GET bingo-pool}: the tiles, the starter packs, the difficulties and the limits</li>
 *     <li>{@code POST bingo-pool/tiles}: add a tile {@code {label, points?, target?, difficulty?, tags?, enabled?}}</li>
 *     <li>{@code PUT bingo-pool/tiles/{id}}: change a tile</li>
 *     <li>{@code POST bingo-pool/tiles/{id}/delete}: remove a tile</li>
 *     <li>{@code POST bingo-pool/seed} {@code {pack, difficulties?}}: add a starter pack's tiles that are not already there</li>
 *     <li>{@code POST bingo-pool/clear} {@code {pack?}}: remove every tile, or only the ones that came from a pack</li>
 * </ul>
 */
@BService
public class BingoPoolAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(BingoPoolAdminApi.class);

    private final BingoTileRepository repository;

    public BingoPoolAdminApi(BingoTileRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("bingo-pool")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        if (parts.length == 2 && parts[1].equals("tiles")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return add(guild, actor, body.get());
        }
        if (parts.length == 3 && parts[1].equals("tiles")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return update(guild, actor, id(parts[2]), body.get());
        }
        if (parts.length == 4 && parts[1].equals("tiles") && parts[3].equals("delete")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return delete(guild, actor, id(parts[2]));
        }
        if (parts.length == 2 && parts[1].equals("seed")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return seed(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("clear")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return clear(guild, actor, body.get());
        }
        return null;
    }

    DataObject overview(Guild guild) {
        DataArray tiles = DataArray.empty();
        for (Stored s : repository.list(guild.getIdLong())) tiles.add(toJson(s));
        DataArray packs = DataArray.empty();
        for (var pack : BingoStarterPacks.all()) packs.add(DataObject.empty().put("id", pack.id()).put("title", pack.title()).put("description", pack.description()));
        DataArray difficulties = DataArray.empty();
        for (Difficulty d : Difficulty.values()) difficulties.add(DataObject.empty().put("key", d.key()).put("label", d.label()).put("defaultPoints", d.defaultPoints()));
        return DataObject.empty().put("tiles", tiles).put("packs", packs).put("difficulties", difficulties)
                .put("maxTiles", BingoTilePool.MAX_TILES_PER_SERVER).put("maxLabel", BingoTilePool.MAX_LABEL).put("maxPoints", BingoTilePool.MAX_POINTS).put("maxTarget", BingoTilePool.MAX_TARGET)
                .put("maxTags", BingoTilePool.MAX_TAGS).put("maxTilesInEvent", BingoTilePool.MAX_TILES_IN_EVENT);
    }

    DataObject add(Guild guild, Member actor, DataObject body) {
        if (repository.count(guild.getIdLong()) >= BingoTilePool.MAX_TILES_PER_SERVER) throw new ApiError(400, "The pool is full (" + BingoTilePool.MAX_TILES_PER_SERVER + " tiles). Remove some first.");
        Entry entry = parse(body, null);
        if (repository.add(guild.getIdLong(), entry, "manual") < 0) throw new ApiError(400, "There is already a tile called \"" + entry.label() + "\".");
        log.info("Dashboard: {} added a bingo tile", actor.getId());
        return overview(guild);
    }

    DataObject update(Guild guild, Member actor, long id, DataObject body) {
        Stored current = repository.list(guild.getIdLong()).stream().filter(s -> s.id() == id).findFirst().orElseThrow(() -> new ApiError(404, "That tile doesn't exist."));
        Entry entry = parse(body, current.entry());
        if (!repository.update(guild.getIdLong(), id, entry)) throw new ApiError(400, "There is already a tile called \"" + entry.label() + "\".");
        log.info("Dashboard: {} changed bingo tile {}", actor.getId(), id);
        return overview(guild);
    }

    DataObject delete(Guild guild, Member actor, long id) {
        if (!repository.delete(guild.getIdLong(), id)) throw new ApiError(404, "That tile doesn't exist.");
        log.info("Dashboard: {} removed bingo tile {}", actor.getId(), id);
        return overview(guild);
    }

    /** Adds the pack's tiles that the pool does not have yet, up to the server's limit; a name already in the pool is left exactly as it is. */
    DataObject seed(Guild guild, Member actor, DataObject body) {
        String pack = body.getString("pack", "").strip();
        if (BingoStarterPacks.all().stream().noneMatch(p -> p.id().equals(pack))) throw new ApiError(400, "Choose a starter pack from the list.");
        Set<Difficulty> only = EnumSet.noneOf(Difficulty.class);
        if (body.hasKey("difficulties") && !body.isNull("difficulties")) {
            DataArray array = body.getArray("difficulties");
            for (int i = 0; i < array.length(); i++) {
                Difficulty d = Difficulty.parse(array.getString(i, ""));
                if (d == null) throw new ApiError(400, "Choose difficulties from the list.");
                only.add(d);
            }
        }
        long guildId = guild.getIdLong();
        int room = BingoTilePool.MAX_TILES_PER_SERVER - repository.count(guildId);
        int added = 0;
        int skipped = 0;
        for (Entry entry : BingoStarterPacks.tiles(pack, only)) {
            if (added >= room) {
                skipped++;
                continue;
            }
            if (repository.add(guildId, entry, pack) >= 0) added++;
            else skipped++;
        }
        log.info("Dashboard: {} added the '{}' starter pack ({} tiles, {} already there or over the limit)", actor.getId(), pack, added, skipped);
        return overview(guild).put("added", added).put("skipped", skipped);
    }

    DataObject clear(Guild guild, Member actor, DataObject body) {
        String pack = body.hasKey("pack") && !body.isNull("pack") ? body.getString("pack", "").strip() : null;
        if (pack != null && BingoStarterPacks.all().stream().noneMatch(p -> p.id().equals(pack)) && !pack.equals("manual")) throw new ApiError(400, "Choose a starter pack from the list.");
        int removed = repository.deleteBySource(guild.getIdLong(), pack == null || pack.isEmpty() ? null : pack);
        log.info("Dashboard: {} cleared {} bingo tiles ({})", actor.getId(), removed, pack == null ? "all" : pack);
        return overview(guild).put("removed", removed);
    }

    /** A tile from the request; on a change, a field that is left out keeps what the tile had. */
    static Entry parse(DataObject body, Entry before) {
        String label = body.hasKey("label") ? BingoTilePool.cleanLabel(body.getString("label", "")) : before == null ? "" : before.label();
        Difficulty difficulty = before == null ? Difficulty.MEDIUM : before.difficulty();
        if (body.hasKey("difficulty") && !body.isNull("difficulty")) difficulty = Difficulty.parse(body.getString("difficulty", ""));
        long points = number(body, "points", before == null ? (difficulty == null ? 0 : difficulty.defaultPoints()) : before.points());
        long target = number(body, "target", before == null ? 1 : before.target());
        String problem = BingoTilePool.problem(label, points, target, difficulty);
        if (problem != null) throw new ApiError(400, problem);

        List<String> tags = before == null ? List.of() : before.tags();
        if (body.hasKey("tags") && !body.isNull("tags")) {
            List<String> typed = new ArrayList<>();
            DataArray array = body.getArray("tags");
            for (int i = 0; i < array.length(); i++) typed.add(array.getString(i, ""));
            tags = BingoTilePool.cleanTags(typed);
            if (tags == null) throw new ApiError(400, "A tag is up to " + BingoTilePool.MAX_TAG + " letters, numbers, spaces or dashes, and a tile has at most " + BingoTilePool.MAX_TAGS + ".");
        }
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", true) : before == null || before.enabled();
        return new Entry(label, points, target, difficulty, tags, enabled);
    }

    private static long number(DataObject body, String key, long fallback) {
        if (!body.hasKey(key) || body.isNull(key)) return fallback;
        Object raw = body.opt(key).orElse(null);
        if (!(raw instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())) throw new ApiError(400, key + " must be a whole number.");
        return n.longValue();
    }

    private static long id(String raw) {
        if (!raw.matches("\\d{1,18}")) throw new ApiError(404, "That tile doesn't exist.");
        return Long.parseLong(raw);
    }

    private static DataObject toJson(Stored s) {
        Entry e = s.entry();
        DataArray tags = DataArray.empty();
        e.tags().forEach(tags::add);
        return DataObject.empty().put("id", Long.toString(s.id())).put("label", e.label()).put("points", e.points()).put("target", e.target())
                .put("difficulty", e.difficulty().key()).put("tags", tags).put("enabled", e.enabled()).put("source", s.source());
    }
}
