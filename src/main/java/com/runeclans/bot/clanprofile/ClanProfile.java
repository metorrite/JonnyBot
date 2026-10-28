package com.runeclans.bot.clanprofile;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * How a server presents its clan to the world: a name, a few lines about it, who it recruits and how to apply. Nothing is public until
 * the server chooses a {@link Visibility} other than private, and a clan is only listed in the public directory once its ownership is
 * verified (see {@link ProfileRules}). The bot's owner can suspend a listing that breaks the rules.
 */
public record ClanProfile(long guildId, Visibility visibility, String slug, String displayName, String tagline, String description, String logoUrl,
                          Integer colour, String timezone, Set<Focus> focus, Recruiting recruiting, String requirements, String applyUrl,
                          boolean leaderboardOptIn, String verifiedClanName, Instant verifiedAt, Long verifiedBy, Instant suspendedAt, String suspendedReason, Instant updatedAt) {

    /** Who can see the profile: nobody outside the server, anyone holding the link, or everyone in the directory. */
    public enum Visibility {
        PRIVATE("Private"), UNLISTED("Unlisted"), PUBLIC("Public");

        private final String label;

        Visibility(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Recruiting {
        CLOSED("Not recruiting"), OPEN("Recruiting"), INVITE_ONLY("Invite only");

        private final String label;

        Recruiting(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Focus {
        PVM("PvM"), SKILLING("Skilling"), SOCIAL("Social"), EVENTS("Events"), COMPETITIVE("Competitive"), CASUAL("Casual"), IRONMAN("Ironman"),
        NEW_PLAYERS("New player friendly");

        private final String label;

        Focus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static ClanProfile empty(long guildId) {
        return new ClanProfile(guildId, Visibility.UNLISTED, null, "", "", "", null, null, null, EnumSet.noneOf(Focus.class), Recruiting.CLOSED, "", null,
                false, null, null, null, null, null, null);
    }

    public boolean suspended() {
        return suspendedAt != null;
    }
}
