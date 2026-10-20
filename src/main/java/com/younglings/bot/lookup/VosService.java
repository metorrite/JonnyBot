package com.younglings.bot.lookup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The Voice of Seren: two Prifddinas districts are boosted, and they change on the hour. The answer comes from the Weird Gloop API (crowdsourced by
 * players running Alt1), so it can lag a little behind the hour; {@link Vos#current} says whether it describes the hour we are in.
 */
@BService
public class VosService {
    static final String URL = "https://api.weirdgloop.org/runescape/vos";

    public record Vos(String district1, String district2, Instant since) {
        /** True if the report is from the hour containing {@code now} (the rotation changes on the hour). */
        public boolean current(Instant now) {
            return !since.isBefore(now.truncatedTo(ChronoUnit.HOURS)) && Duration.between(since, now).compareTo(Duration.ofMinutes(75)) < 0;
        }
    }

    private final LookupHttp http;

    public VosService(LookupHttp http) {
        this.http = http;
    }

    /** Empty if the service is down or answers nonsense. Cached by {@link LookupHttp} for 15 minutes, so a report can be that stale; callers check {@link Vos#current}. */
    public Optional<Vos> fetch() {
        return http.get(URL).flatMap(VosService::parse);
    }

    /** The next time the districts change after {@code now}: the start of the next hour. */
    public static Instant nextChange(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1));
    }

    static Optional<Vos> parse(String json) {
        try {
            DataObject o = DataObject.fromJson(json);
            String a = o.getString("district1", "").strip();
            String b = o.getString("district2", "").strip();
            if (a.isEmpty() || b.isEmpty() || !o.hasKey("timestamp")) return Optional.empty();
            return Optional.of(new Vos(a, b, Instant.parse(o.getString("timestamp"))));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
