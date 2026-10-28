package com.runeclans.bot.alliance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the public hub may say about a clan's alliances. Whether a clan has alliances (and what they share with each other) is separate from whether
 * it wants them shown; this class is only about showing. The rules, from the clans themselves:
 * <ul>
 *   <li>A clan chooses {@link Visibility}: {@code HIDDEN} (the default: its page says nothing about alliances), {@code NO_ALLIES} (its page says it
 *       has none, whatever is true) or {@code SHOWN} (its page lists its allies).</li>
 *   <li><b>Mutual hiding:</b> a clan that is not {@code SHOWN} is never named on another clan's page or on an alliance page, so hiding is never
 *       undone by an ally. The same goes for a clan that is not publicly listed in the directory: nobody is exposed by being named by someone else.</li>
 *   <li>Only active members count; a clan that has only been invited is not an ally yet.</li>
 *   <li>A clan page that is {@code SHOWN} but has no visible ally says "no allies" rather than revealing that hidden ones exist.</li>
 *   <li>An alliance page exists only while at least two clans can be shown in it.</li>
 * </ul>
 * Pure (no Discord, no database), so every combination is tested.
 */
public final class AllianceVisibility {
    private AllianceVisibility() {}

    public enum Visibility {
        HIDDEN, NO_ALLIES, SHOWN;

        public static Visibility parse(String raw) {
            if (raw == null) return HIDDEN;
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return HIDDEN;
            }
        }
    }

    /** A clan in an alliance. {@code listed}: its profile is publicly listed in the directory. {@code active}: it has accepted (not just been invited). */
    public record Ally(long guildId, Visibility visibility, boolean listed, boolean active) {
        /** Whether other pages may name this clan. */
        public boolean nameable() {
            return active && visibility == Visibility.SHOWN && listed;
        }
    }

    public record AllianceView(long id, String name, List<Ally> members) {}

    /** The clans another page may name in an alliance. */
    public static List<Ally> nameableMembers(AllianceView alliance) {
        return alliance.members().stream().filter(Ally::nameable).toList();
    }

    /** Whether the alliance has a public page of its own: at least two clans can be named in it. */
    public static boolean hasPage(AllianceView alliance) {
        return nameableMembers(alliance).size() >= 2;
    }

    /** One alliance as it appears on a clan's page: the other clans that may be named, and whether it links to an alliance page. */
    public record Group(long allianceId, String name, List<Long> allies, boolean pageAvailable) {}

    public enum Mode { ABSENT, NONE, LIST }

    /** What a clan's page shows about its alliances. {@code ABSENT}: nothing at all. {@code NONE}: "No allies". {@code LIST}: the groups. */
    public record Section(Mode mode, List<Group> groups) {
        static Section of(Mode mode, List<Group> groups) {
            return new Section(mode, List.copyOf(groups));
        }
    }

    /**
     * The alliance section of {@code viewer}'s page. {@code alliances} are the alliances the viewer is an active member of (with all their members,
     * including those that must stay hidden); this decides what may be said.
     */
    public static Section sectionFor(long viewer, Visibility viewerChoice, List<AllianceView> alliances) {
        if (viewerChoice == Visibility.HIDDEN) return Section.of(Mode.ABSENT, List.of());
        if (viewerChoice == Visibility.NO_ALLIES) return Section.of(Mode.NONE, List.of());
        List<Group> groups = new ArrayList<>();
        for (AllianceView alliance : alliances) {
            boolean activeMember = alliance.members().stream().anyMatch(m -> m.guildId() == viewer && m.active());
            if (!activeMember) continue;
            List<Long> others = nameableMembers(alliance).stream().map(Ally::guildId).filter(id -> id != viewer).toList();
            if (others.isEmpty()) continue;
            groups.add(new Group(alliance.id(), alliance.name(), others, hasPage(alliance)));
        }
        return groups.isEmpty() ? Section.of(Mode.NONE, List.of()) : Section.of(Mode.LIST, groups);
    }
}
