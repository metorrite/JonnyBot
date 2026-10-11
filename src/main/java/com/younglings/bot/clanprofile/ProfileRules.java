package com.younglings.bot.clanprofile;

import com.younglings.bot.clanprofile.ClanProfile.Visibility;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The rules for a clan profile, with no database or Discord in them so they can be tested: what text is acceptable, what makes a URL
 * safe to show, who counts as clan leadership, when a clan is verified, and whether a profile is listed publicly right now.
 */
public final class ProfileRules {
    public static final int MAX_NAME = 40;
    public static final int MAX_TAGLINE = 120;
    public static final int MAX_DESCRIPTION = 1500;
    public static final int MAX_REQUIREMENTS = 600;
    public static final int MAX_URL = 300;
    public static final int MIN_SLUG = 3;
    public static final int MAX_SLUG = 32;

    /** Words that would collide with the directory's own pages. */
    private static final Set<String> RESERVED_SLUGS = Set.of("admin", "api", "new", "search", "recruiting", "events", "leaderboards", "leaderboard", "report",
            "about", "www", "login", "logout", "dashboard", "settings", "docs", "faq", "install", "privacy", "terms", "clans", "directory", "jonnybot");

    /** Clan ranks that may speak for the clan and so may verify it. */
    private static final Set<String> LEADERSHIP_RANKS = Set.of("owner", "deputy owner", "overseer");

    /** Whether a profile is shown in the directory ({@code LISTED}), reachable only by its link ({@code UNLISTED}) or not shown at all. */
    public enum Listing { HIDDEN, UNLISTED, LISTED }

    private ProfileRules() {}

    // ---------- slugs ----------

    /** "The Younglings!" becomes "the-younglings": lower case, letters and digits, single dashes, at most {@link #MAX_SLUG} characters. */
    public static String slugify(String text) {
        if (text == null) return "";
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_SLUG) slug = slug.substring(0, MAX_SLUG).replaceAll("-+$", "");
        return slug;
    }

    /** Why a slug can't be used, or null when it can. */
    public static String slugProblem(String slug) {
        if (slug == null || slug.length() < MIN_SLUG || slug.length() > MAX_SLUG) return "The address must be " + MIN_SLUG + " to " + MAX_SLUG + " characters.";
        if (!slug.matches("[a-z0-9]+(-[a-z0-9]+)*")) return "The address can only use lower-case letters, digits and single dashes.";
        if (RESERVED_SLUGS.contains(slug)) return "\"" + slug + "\" is reserved. Choose another address.";
        return null;
    }

    // ---------- fields ----------

    /** What is wrong with the text fields of a profile, as words for the person editing it (empty when it can be saved). */
    public static List<String> fieldProblems(String displayName, String tagline, String description, String requirements, String logoUrl, String applyUrl,
                                             Integer colour, String timezone) {
        List<String> problems = new ArrayList<>();
        if (displayName == null || displayName.strip().isEmpty()) problems.add("Give the clan a name to show.");
        else if (displayName.strip().length() > MAX_NAME) problems.add("The name can be at most " + MAX_NAME + " characters.");
        if (tagline != null && tagline.strip().length() > MAX_TAGLINE) problems.add("The tagline can be at most " + MAX_TAGLINE + " characters.");
        if (description != null && description.strip().length() > MAX_DESCRIPTION) problems.add("The description can be at most " + MAX_DESCRIPTION + " characters.");
        if (requirements != null && requirements.strip().length() > MAX_REQUIREMENTS) problems.add("The requirements can be at most " + MAX_REQUIREMENTS + " characters.");
        String logo = urlProblem(logoUrl);
        if (logo != null) problems.add("Logo: " + logo);
        String apply = urlProblem(applyUrl);
        if (apply != null) problems.add("Apply link: " + apply);
        if (colour != null && (colour < 0 || colour > 0xFFFFFF)) problems.add("That colour isn't valid.");
        if (timezone != null && !timezone.isBlank() && !validTimezone(timezone)) problems.add("That time zone isn't one JonnyBot knows.");
        return problems;
    }

    /** Only plain https links are accepted, so a listing can't carry a script or an insecure resource. Null or blank means none. */
    static String urlProblem(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        if (u.length() > MAX_URL) return "must be " + MAX_URL + " characters or fewer.";
        if (!u.regionMatches(true, 0, "https://", 0, 8)) return "must start with https://";
        if (u.matches(".*\\s.*")) return "can't contain spaces.";
        if (u.length() <= 8 || !u.substring(8).contains(".")) return "doesn't look like a web address.";
        return null;
    }

    static boolean validTimezone(String zone) {
        try {
            ZoneId.of(zone.strip());
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    // ---------- verification ----------

    /** Whether a rank in the clan roster may speak for the clan ("Owner", "Deputy Owner", "Overseer"). */
    public static boolean isLeadership(String clanRank) {
        return clanRank != null && LEADERSHIP_RANKS.contains(clanRank.strip().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /**
     * Why this person cannot verify the clan right now, in words, or null when they can.
     *
     * @param clanName       the server's configured RuneScape clan (null if none)
     * @param rosterSize     how many active members the last roster sync found (0 if the clan wasn't found)
     * @param claimedByOther another server has already verified this clan
     * @param linkedNames    how many RuneScape names the person has linked in this server
     * @param ranksOnRoster  the clan ranks of those linked names that are on the roster
     */
    public static String verificationProblem(String clanName, int rosterSize, boolean claimedByOther, int linkedNames, List<String> ranksOnRoster) {
        if (clanName == null || clanName.isBlank()) return "Set this server's RuneScape clan in Server setup first.";
        if (rosterSize == 0) return "I couldn't find any members for the clan \"" + clanName + "\". Check the name in Server setup, or sync the clan roster and try again.";
        if (claimedByOther) return "The clan \"" + clanName + "\" has already been verified by another server. If this is your clan, ask the bot's owner to look into it.";
        if (linkedNames == 0) return "Link your RuneScape name in this server first (with /rs), so JonnyBot can see your rank in the clan.";
        if (ranksOnRoster.isEmpty()) return "None of the RuneScape names you linked here is on the \"" + clanName + "\" roster.";
        if (ranksOnRoster.stream().noneMatch(ProfileRules::isLeadership)) {
            return "Only a clan Owner, Deputy Owner or Overseer can verify a clan. Your rank is " + String.join(" / ", ranksOnRoster) + ".";
        }
        return null;
    }

    /** A profile is verified while the clan it was verified for is still the server's configured clan. */
    public static boolean verified(ClanProfile profile, String currentClanName) {
        return profile.verifiedClanName() != null && currentClanName != null && profile.verifiedClanName().equalsIgnoreCase(currentClanName.strip());
    }

    // ---------- visibility ----------

    /**
     * Where a profile shows up right now. A suspended profile is hidden. Public needs a verified clan, so a clan that is renamed or
     * un-verified drops out of the directory instead of staying listed under a name nobody has checked.
     */
    public static Listing listing(ClanProfile profile, String currentClanName) {
        if (profile.suspended()) return Listing.HIDDEN;
        return switch (profile.visibility()) {
            case PRIVATE -> Listing.HIDDEN;
            case UNLISTED -> Listing.UNLISTED;
            case PUBLIC -> verified(profile, currentClanName) ? Listing.LISTED : Listing.HIDDEN;
        };
    }

    /** What stops this profile being set to {@code wanted}, or null when it can be. */
    public static String visibilityProblem(ClanProfile profile, String currentClanName, Visibility wanted) {
        if (wanted == Visibility.PRIVATE) return null;
        if (profile.suspended()) return "This listing has been suspended by the bot's owner" + (profile.suspendedReason() == null || profile.suspendedReason().isBlank() ? "." : ": " + profile.suspendedReason());
        if (wanted == Visibility.PUBLIC && !verified(profile, currentClanName)) return "Verify the clan first: a public listing needs a clan Owner, Deputy Owner or Overseer to verify it.";
        return null;
    }
}
