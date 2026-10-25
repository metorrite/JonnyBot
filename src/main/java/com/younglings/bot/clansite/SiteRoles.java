package com.younglings.bot.clansite;

import java.util.List;
import java.util.Set;

/**
 * Who someone is on a clan website (T-368, docs/BUILDER-V3.md 8.2), decided from facts the caller looked up. Pure (no database, no Discord)
 * so the whole table can be tested. The roles, highest first:
 * <ul>
 *   <li>{@code OWNER}: the Discord server's owner, or the person who claimed the site, while they are in the server</li>
 *   <li>{@code ADMIN}: anyone the bot's dashboard lets in for that server ({@code DashboardAccess})</li>
 *   <li>{@code EDITOR}: holds one of the site's editor roles, or is named as an editor by Discord user id (none by default; on a bot-less
 *   site, which can't see roles, only the user ids count)</li>
 *   <li>{@code MEMBER}: in the server and, when the site names member roles, holding one; with none named, the clan's Member role
 *   (Server setup &gt; Verification) when it is set, else anyone in the server</li>
 *   <li>{@code GUEST}: signed in but not a member; {@code VISITOR}: not signed in</li>
 * </ul>
 */
public final class SiteRoles {
    private SiteRoles() {}

    public enum Role {
        VISITOR, GUEST, MEMBER, EDITOR, ADMIN, OWNER;

        public boolean atLeast(Role other) {
            return ordinal() >= other.ordinal();
        }

        public String key() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** What a site role may do; each write route asks for one of these. */
    public enum Action {
        /** Open the editor and save the draft. */
        EDIT,
        /** Publish, unpublish, restore an old version. */
        PUBLISH,
        /** The site's own settings (switch on/off, look) and its role settings. */
        SETTINGS,
        /** Delete the website, hand it to someone else, billing. */
        OWN
    }

    /**
     * A site's role settings; empty lists and a false switch until an admin changes them. {@code claimedBy} is null when nobody claimed it.
     * {@code editorUserIds} are people made editors by their Discord user id, for a site whose server the bot isn't in (it can't see roles there).
     */
    public record Settings(List<Long> editorRoleIds, List<Long> memberRoleIds, List<Long> staffRoleIds, boolean editorsMayPublish, Long claimedBy,
                           List<Long> editorUserIds) {
        public static final Settings DEFAULT = new Settings(List.of(), List.of(), List.of(), false, null, List.of());

        public Settings {
            editorRoleIds = List.copyOf(editorRoleIds);
            memberRoleIds = List.copyOf(memberRoleIds);
            staffRoleIds = List.copyOf(staffRoleIds);
            editorUserIds = List.copyOf(editorUserIds);
        }

        /** Settings with no editors named by user id. */
        public Settings(List<Long> editorRoleIds, List<Long> memberRoleIds, List<Long> staffRoleIds, boolean editorsMayPublish, Long claimedBy) {
            this(editorRoleIds, memberRoleIds, staffRoleIds, editorsMayPublish, claimedBy, List.of());
        }
    }

    /**
     * The facts about one signed-in person: whether they are in the server at all, own it, may use its dashboard, and which roles they hold.
     * {@code clanMemberRoleId} is the clan's Member role from the server's verification setup (null when it isn't set up).
     */
    public record Facts(long userId, boolean inGuild, boolean guildOwner, boolean dashboardAdmin, Set<Long> heldRoleIds, Long clanMemberRoleId) {
        public Facts {
            heldRoleIds = Set.copyOf(heldRoleIds);
        }
    }

    /** The person's role on the site. {@code facts} null means nobody is signed in. */
    public static Role resolve(Facts facts, Settings settings) {
        if (facts == null) return Role.VISITOR;
        if (!facts.inGuild()) return Role.GUEST;
        if (facts.guildOwner() || (settings.claimedBy() != null && settings.claimedBy() == facts.userId())) return Role.OWNER;
        if (facts.dashboardAdmin()) return Role.ADMIN;
        if (holdsAny(facts, settings.editorRoleIds()) || settings.editorUserIds().contains(facts.userId())) return Role.EDITOR;
        if (!settings.memberRoleIds().isEmpty()) return holdsAny(facts, settings.memberRoleIds()) ? Role.MEMBER : Role.GUEST;
        if (facts.clanMemberRoleId() != null) return facts.heldRoleIds().contains(facts.clanMemberRoleId()) ? Role.MEMBER : Role.GUEST;
        return Role.MEMBER;
    }

    /**
     * The person's role on a bot-less site's public pages, where nothing vouches for them but their sign-in: the person who claimed the site is
     * its owner and the editors named by user id are editors; everyone else signed in is a guest, because without the bot nobody can tell who is
     * in the server. (The site's admin routes know more: there the website signs whether the person owns or manages the server.)
     */
    public static Role resolveWithoutBot(Long userId, Settings settings) {
        if (userId == null) return Role.VISITOR;
        if (settings.claimedBy() != null && settings.claimedBy().longValue() == userId) return Role.OWNER;
        if (settings.editorUserIds().contains(userId)) return Role.EDITOR;
        return Role.GUEST;
    }

    /** Whether someone may open staff-only pages: admins and editors, and anyone holding one of the site's staff roles. */
    public static boolean isStaff(Role role, Facts facts, Settings settings) {
        if (role.atLeast(Role.EDITOR)) return true;
        return facts != null && facts.inGuild() && holdsAny(facts, settings.staffRoleIds());
    }

    /** The one rule for who may do what (8.2): editors edit, admins publish (editors too with the switch on) and change settings, owners own. */
    public static boolean allows(Role role, Action action, Settings settings) {
        return switch (action) {
            case EDIT -> role.atLeast(Role.EDITOR);
            case PUBLISH -> role.atLeast(Role.ADMIN) || (role == Role.EDITOR && settings.editorsMayPublish());
            case SETTINGS -> role.atLeast(Role.ADMIN);
            case OWN -> role == Role.OWNER;
        };
    }

    private static boolean holdsAny(Facts facts, List<Long> roleIds) {
        for (Long id : roleIds) if (facts.heldRoleIds().contains(id)) return true;
        return false;
    }
}
