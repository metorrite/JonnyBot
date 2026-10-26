package com.younglings.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Settings that were renamed and still answer to their old name (T-662: {@code JONNYBOT_*} became {@code RUNECLAN_BOT_*}, {@code RSNLINK_*}
 * became {@code RUNELINK_*}). The new name wins; when only the old name is set its value is used exactly as before, and a deprecation note
 * naming both variables (never the value) is logged once per process. A blank value counts as unset.
 */
public final class EnvAliases {
    private static final Logger log = LoggerFactory.getLogger(EnvAliases.class);
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private EnvAliases() {}

    /** {@code name} from the process environment, else {@code deprecatedName}; null when neither is set. */
    public static String get(String name, String deprecatedName) {
        return get(name, deprecatedName, System::getenv);
    }

    /** {@code name} from {@code lookup}, else {@code deprecatedName} (logged once); null when neither is set. */
    public static String get(String name, String deprecatedName, Function<String, String> lookup) {
        return resolve(name, deprecatedName, lookup, WARNED, (newName, oldName) ->
                log.warn("{} is deprecated; rename it to {} (the old name still works for now)", oldName, newName));
    }

    /** {@link #get}'s rule, with the "already warned" set and the warning passed in so it can be tested. */
    static String resolve(String name, String deprecatedName, Function<String, String> lookup, Set<String> warned,
                          BiConsumer<String, String> warn) {
        String value = lookup.apply(name);
        if (value != null && !value.isBlank()) return value;
        value = lookup.apply(deprecatedName);
        if (value == null || value.isBlank()) return null;
        if (warned.add(deprecatedName)) warn.accept(name, deprecatedName);
        return value;
    }
}
