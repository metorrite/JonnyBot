package com.younglings.bot.plugin;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.lookup.GePriceService;
import com.younglings.bot.util.TtlCache;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * What the server adds to an event as it is stored, so nothing that matters is taken on the client's word:
 * <ul>
 *     <li>a skill {@code XP} event keeps the client's raw number as {@code xp} and gains {@code xpScaled} (raw divided by the scale, 10 by default,
 *     see {@link BotConfig#getPluginXpScale}) and the {@code xpScale} used;</li>
 *     <li>a {@code DROP} loses any {@code value} the client sent and gains {@code unitPriceGp} and {@code valueGp} (quantity times price) from the
 *     Grand Exchange price when it is known. When the price is not known they are simply absent. Anything that awards points for a drop must read
 *     {@code valueGp}, never a client value.</li>
 * </ul>
 * Prices are remembered for half an hour and a batch asks the wiki for at most {@link #MAX_LOOKUPS_PER_BATCH} items it doesn't know, so a busy
 * plugin can't turn into a stream of requests; items beyond that are stored without a value this time.
 */
@BService
public class PluginEnrichment {
    static final int MAX_LOOKUPS_PER_BATCH = 8;
    private static final Duration PRICE_TTL = Duration.ofMinutes(30);

    /** How many wiki price lookups one batch may still make. */
    public static final class Budget {
        private int left;

        Budget(int left) {
            this.left = left;
        }

        boolean take() {
            if (left <= 0) return false;
            left--;
            return true;
        }
    }

    private final Function<String, Optional<Long>> priceOf;
    private final LongSupplier xpScale;
    private final TtlCache<String, Long> prices = new TtlCache<>(PRICE_TTL);

    public PluginEnrichment(GePriceService gePrices, BotConfig config) {
        this(gePrices::latestPrice, config::getPluginXpScale);
    }

    /** For tests: any price source and scale. */
    PluginEnrichment(Function<String, Optional<Long>> priceOf, LongSupplier xpScale) {
        this.priceOf = priceOf;
        this.xpScale = xpScale;
    }

    public Budget budget() {
        return new Budget(MAX_LOOKUPS_PER_BATCH);
    }

    /** The event as it should be stored. Other kinds of event come back unchanged. */
    public PluginPayload.Event enrich(PluginPayload.Event event, Budget budget) {
        return switch (event.type()) {
            case XP -> withData(event, xp(DataObject.fromJson(event.dataJson())));
            case DROP -> withData(event, drop(DataObject.fromJson(event.dataJson()), budget));
            default -> event;
        };
    }

    private DataObject xp(DataObject data) {
        long scale = Math.max(1, xpScale.getAsLong());
        long raw = data.getLong("xp", 0);
        return data.put("xpScale", scale).put("xpScaled", raw / scale);
    }

    private DataObject drop(DataObject data, Budget budget) {
        data.remove("value"); // the client's idea of a drop's worth is never kept
        data.remove("valueGp");
        data.remove("unitPriceGp");
        String item = data.getString("itemName", "").strip();
        Optional<Long> unit = price(item, budget);
        if (unit.isPresent()) {
            long quantity = Math.max(1, data.getLong("quantity", 1));
            data.put("unitPriceGp", unit.get());
            data.put("valueGp", multiplySaturating(unit.get(), quantity));
        }
        return data;
    }

    private Optional<Long> price(String item, Budget budget) {
        if (item.isEmpty()) return Optional.empty();
        String key = item.toLowerCase(java.util.Locale.ROOT);
        Optional<Long> cached = prices.get(key);
        if (cached.isPresent()) return cached;
        if (!budget.take()) return Optional.empty();
        Optional<Long> looked = priceOf.apply(item);
        looked.ifPresent(p -> prices.put(key, p));
        return looked;
    }

    private static long multiplySaturating(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static PluginPayload.Event withData(PluginPayload.Event e, DataObject data) {
        return new PluginPayload.Event(e.key(), e.type(), e.at(), e.sourceKind(), e.sourceName(), e.sourceId(), new String(data.toJson(), java.nio.charset.StandardCharsets.UTF_8));
    }
}
