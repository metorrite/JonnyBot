package com.runeclans.bot.linkedroles;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the bot tells Discord about a person for Linked Roles: the metadata schema (at most 5 records, Discord's limit) and one person's values.
 * Metadata is per person, not per server (Discord's design), so "member of clan X" can't be a condition; {@code clan_member} means a member of
 * any clan listed on RuneClans. Pure, so the tests check it without Discord.
 * <p>
 * Type codes from Discord's ApplicationRoleConnectionMetadataType: 2 integer &gt;= the server's value, 5 datetime &lt;= (the server's value is
 * a count of days before today, so "linked at least N days ago"), 7 boolean equal.
 */
public final class RoleMetadata {
    public static final String PLATFORM_NAME = "RuneScape";

    public record Field(String key, int type, String name, String description) {}

    public static final List<Field> SCHEMA = List.of(
            new Field("rsn_linked", 7, "RuneScape name linked", "Has linked a RuneScape name to their Discord account"),
            new Field("total_level", 2, "Total level", "RuneScape total level of at least"),
            new Field("combat_level", 2, "Combat level", "RuneScape combat level of at least"),
            new Field("clan_member", 7, "In a RuneClans clan", "Member of a clan listed on RuneClans"),
            new Field("linked_since", 5, "Days linked", "RuneScape name linked for at least this many days"));

    /**
     * What the bot knows about a person. {@code rsn} is null with no linked name; levels are null when no snapshot exists yet (the condition then
     * fails rather than guessing).
     */
    public record Profile(String rsn, Instant linkedSince, Integer totalLevel, Integer combatLevel, boolean clanMember) {
        public static final Profile NONE = new Profile(null, null, null, null, false);
    }

    private RoleMetadata() {}

    /** The body of {@code PUT /applications/{id}/role-connections/metadata}. */
    public static DataArray schemaJson() {
        DataArray out = DataArray.empty();
        for (Field f : SCHEMA) out.add(DataObject.empty().put("key", f.key()).put("name", f.name()).put("description", f.description()).put("type", f.type()));
        return out;
    }

    /** The person's values as Discord wants them: every value a string; a value we don't know is left out. */
    public static Map<String, String> values(Profile p) {
        Map<String, String> out = new LinkedHashMap<>();
        boolean linked = p.rsn() != null;
        out.put("rsn_linked", linked ? "1" : "0");
        if (linked && p.totalLevel() != null) out.put("total_level", Integer.toString(p.totalLevel()));
        if (linked && p.combatLevel() != null) out.put("combat_level", Integer.toString(p.combatLevel()));
        out.put("clan_member", linked && p.clanMember() ? "1" : "0");
        if (linked && p.linkedSince() != null) out.put("linked_since", p.linkedSince().truncatedTo(ChronoUnit.SECONDS).toString());
        return out;
    }

    /** The body of {@code PUT /users/@me/applications/{id}/role-connection}. */
    public static DataObject connectionJson(Profile p) {
        DataObject metadata = DataObject.empty();
        values(p).forEach(metadata::put);
        DataObject body = DataObject.empty().put("platform_name", PLATFORM_NAME).put("metadata", metadata);
        if (p.rsn() != null) body.put("platform_username", p.rsn().length() > 100 ? p.rsn().substring(0, 100) : p.rsn());
        return body;
    }

    /** The body that clears a person's connection (unlink): no values, so every condition fails and the roles drop. */
    public static DataObject clearedJson() {
        return DataObject.empty().put("platform_name", PLATFORM_NAME).put("metadata", DataObject.empty());
    }

    /** A stable text of the values, to tell whether they changed since the last push. */
    public static String fingerprint(Profile p) {
        return (p.rsn() == null ? "" : p.rsn()) + "|" + values(p);
    }
}
