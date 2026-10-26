package com.younglings.bot.linkedroles;

import com.younglings.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where Linked Roles learns a person's current RuneLink name after they verified (T-349). The website passes the name it found at sign-in;
 * the hourly pass asks again, so an unlink, a new link or a rename on RuneLink reaches Discord within the hour without the person verifying
 * again. The bot pulls rather than the hub pushing: only the few people who verified are asked about, and the hub tells no app about links it
 * wasn't granted.
 */
@FunctionalInterface
public interface RsLinkSource {
    /** The most people one call asks about (the hub's {@code POST /v1/link/active} limit). */
    int BATCH = 100;

    /**
     * One person's current RuneLink name, when that link was made, and whether the hub has confirmed it from public data (T-552: a level
     * above {@code implicit}; an older hub that doesn't say counts as not confirmed).
     */
    record Current(String rsn, Instant since, boolean confirmed) {
        public Current(String rsn, Instant since) {
            this(rsn, since, false);
        }
    }

    /**
     * Each asked person's current link; a person with none is missing from the map. Empty when the answer isn't known (hub off, unreachable,
     * refused): the stored name is then kept, never cleared on a guess.
     */
    Optional<Map<Long, Current>> current(List<Long> userIds);

    /** No source: the name from sign-in is kept until the person verifies again (the behaviour before T-349). */
    RsLinkSource NONE = ids -> Optional.empty();

    /** The RuneClans hub's first-party {@code POST /v1/link/active}, with the bot's API key ({@code link:resolve}). */
    static RsLinkSource hub(RuneClansHubClient hub) {
        if (!hub.enabled()) return NONE;
        return ids -> {
            if (ids.isEmpty()) return Optional.of(Map.of());
            DataArray asked = DataArray.empty();
            for (long id : ids) asked.add(Long.toUnsignedString(id));
            RuneClansHubClient.Answer a = hub.call("POST", "/v1/link/active", DataObject.empty().put("discordIds", asked).toString());
            return a == null || !a.ok() || a.json() == null ? Optional.empty() : parse(a.json(), ids);
        };
    }

    /** Reads the hub's answer; only ids that were asked about count. Empty when the answer isn't the expected shape. */
    static Optional<Map<Long, Current>> parse(DataObject answer, List<Long> asked) {
        DataArray links = answer.optArray("links").orElse(null);
        if (links == null) return Optional.empty();
        Map<Long, Current> out = new HashMap<>();
        for (int i = 0; i < links.length(); i++) {
            if (!links.isType(i, DataType.OBJECT)) continue;
            DataObject l = links.getObject(i);
            long id;
            try {
                id = Long.parseUnsignedLong(l.getString("discordId", ""));
            } catch (NumberFormatException e) {
                continue;
            }
            String rsn = LinkedRoles.cleanRsn(l.getString("rsn", null));
            if (!asked.contains(id) || rsn == null) continue;
            Instant since = null;
            try {
                String at = l.getString("linkedAt", null);
                if (at != null) since = Instant.parse(at);
            } catch (DateTimeParseException e) {
                // keep the name; "days linked" then counts from the first time it is seen
            }
            String level = l.getString("verification", "implicit");
            boolean confirmed = level.equals("public_data_corroborated") || level.equals("jagex_signed");
            out.put(id, new Current(rsn, since, confirmed));
        }
        return Optional.of(out);
    }
}
