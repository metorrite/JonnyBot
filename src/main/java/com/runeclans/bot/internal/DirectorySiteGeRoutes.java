package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.ClanSiteService;
import com.runeclans.bot.lookup.GePriceService;
import com.runeclans.bot.lookup.GePriceService.Watched;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code GET sites/{slug}/data/ge?items=Name|Name}: Grand Exchange prices for the clan website's "GE price watch" widget (T-592), only while
 * the site is published. Up to {@value #MAX_ITEMS} item names, each answered with its price, 7-day change and a price a day for the last 7
 * days, from the bot's own price lookup (the Weird Gloop exchange API, cached). Nothing here needs Discord, so a bot-less site gets it too.
 * {@code {items:[{name, found, price, change7d, week, wiki}]}} in the order asked, an unknown name as {@code found: false}.
 */
@BService
public class DirectorySiteGeRoutes implements DirectoryRouteModule {
    static final int MAX_ITEMS = 12;

    private final ClanSiteService sites;
    private final GePriceService prices;
    private final RateLimiter perSite = new RateLimiter(10, 50);

    public DirectorySiteGeRoutes(ClanSiteService sites, GePriceService prices) {
        this.sites = sites;
        this.prices = prices;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length != 4 || !parts[2].equals("data") || !parts[3].equals("ge")) return false;
        if (!request.requireGet()) return true;
        if (request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId())).isEmpty()) {
            request.sendError(404, "Not found");
            return true;
        }
        if (!perSite.tryAcquire(parts[1])) {
            request.sendError(429, "Too many requests");
            return true;
        }
        DataArray items = DataArray.empty();
        for (String name : names(request.query("items"))) items.add(json(name, prices.watch(name)));
        request.send(200, DataObject.empty().put("items", items));
        return true;
    }

    /** The asked names: split on {@code |}, trimmed, no blanks or repeats (any case), at most {@value #MAX_ITEMS}, each at most 80 characters. */
    static List<String> names(String raw) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("\\|")) {
            String name = part.strip();
            if (name.isEmpty() || name.length() > 80 || !seen.add(name.toLowerCase(java.util.Locale.ROOT))) continue;
            out.add(name);
            if (out.size() == MAX_ITEMS) break;
        }
        return out;
    }

    static DataObject json(String asked, Optional<Watched> watched) {
        if (watched.isEmpty()) return DataObject.empty().put("name", asked).put("found", false);
        Watched w = watched.get();
        Double change = w.price().week() == null ? null : w.price().week().percent();
        return DataObject.empty().put("name", w.price().name()).put("found", true).put("price", w.price().price())
                .put("change7d", change == null ? null : Math.round(change * 10) / 10.0).put("week", DataArray.fromCollection(w.week()))
                .put("wiki", w.price().wikiUrl());
    }
}
