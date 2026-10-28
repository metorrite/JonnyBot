package com.runeclans.bot.access;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;

import java.util.Arrays;
import java.util.Optional;

/** How much a person must be in a server for a server grant to let them in. Each level includes the ones above it in this list. */
public enum AccessLevel {
    MEMBER("member", "Any member"),
    MANAGE_SERVER("manage_server", "Manage Server or above"),
    ADMINISTRATOR("administrator", "Administrator or above"),
    OWNER("owner", "Server owner only");

    private final String key;
    private final String label;

    AccessLevel(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() { return key; }
    public String label() { return label; }

    public static Optional<AccessLevel> ofKey(String key) {
        return Arrays.stream(values()).filter(l -> l.key.equals(key)).findFirst();
    }

    /** Whether {@code member}, as found in the server, has at least this level there. A missing member never does. */
    public boolean satisfiedBy(Member member) {
        if (member == null) return false;
        return switch (this) {
            case MEMBER -> true;
            case MANAGE_SERVER -> member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER);
            case ADMINISTRATOR -> member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR);
            case OWNER -> member.isOwner();
        };
    }
}
