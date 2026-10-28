package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ProfileRules;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.runescape.PlayerLinkRepository.StatsSnapshotRow;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.SkillValue;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The rules behind a player's public profile ({@code GET /internal/directory/players/{rsn}}, {@link DirectoryPlayerRoutes}), apart from the
 * database so they can be tested on their own. A clan is included only while the public directory lists it ({@link ProfileRules#listing} is
 * {@code LISTED}: public, verified, not suspended) and the player's member page on that clan's site is public ({@link SiteApi#visibleMember}:
 * a current member who hasn't hidden themselves from the rankings). Nothing here is new: it is what those clan pages already show.
 */
final class DirectoryPlayerLogic {
    /** RuneScape names: 1 to 12 letters, digits, spaces, underscores or hyphens. */
    private static final Pattern RSN = Pattern.compile("[A-Za-z0-9 _-]{1,12}");
    /** As many snapshots as a clan site's member page reads, so the month's gain reaches back as far. */
    static final int SNAPSHOTS = 2000;

    private DirectoryPlayerLogic() {
    }

    /** What the rules need to read; the route reads it from the database, tests from plain lists. */
    interface Source {
        /** The servers whose roster has this name as an active member. */
        List<Long> guildsWithActiveMember(String rsn);

        Optional<ClanProfile> profile(long guildId);

        /** The server's in-game clan name ({@code ClanProfileService#clanNameOf}), which a listed profile must be verified for. */
        String clanName(long guildId);

        /** {@link SiteApi#visibleMember}: the roster row when the member page is public, else null. */
        MemberRow visibleMember(long guildId, String rsn);

        /** The highest rank order of the server's clan ranks. */
        int maxRankOrder(long guildId);

        List<StatsSnapshotRow> snapshots(String rsn);

        List<SkillValue> skills(long snapshotId);
    }

    record Membership(ClanProfile clan, MemberRow row, int maxRankOrder) {}

    /** The name trimmed, or null when it isn't a RuneScape name (the route answers 400). */
    static String cleanRsn(String raw) {
        if (raw == null) return null;
        String rsn = raw.strip();
        return RSN.matcher(rsn).matches() ? rsn : null;
    }

    /** The clans that may show this player, the most recently polled first (then by name). */
    static List<Membership> memberships(String rsn, Source source) {
        List<Membership> out = new ArrayList<>();
        for (long guildId : source.guildsWithActiveMember(rsn)) {
            ClanProfile profile = source.profile(guildId).orElse(null);
            if (profile == null || profile.slug() == null || profile.slug().isBlank()) continue;
            if (ProfileRules.listing(profile, source.clanName(guildId)) != ProfileRules.Listing.LISTED) continue;
            MemberRow row = source.visibleMember(guildId, rsn);
            if (row == null) continue;
            out.add(new Membership(profile, row, source.maxRankOrder(guildId)));
        }
        out.sort(Comparator.comparing((Membership m) -> m.row().lastPolled(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(m -> m.clan().displayName() == null ? "" : m.clan().displayName(), String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The whole answer, or null when no included clan has the player (the route answers 404, as for a name nobody knows). */
    static DataObject answer(String rsn, Source source, OffsetDateTime now) {
        List<Membership> memberships = memberships(rsn, source);
        if (memberships.isEmpty()) return null;
        String name = memberships.getFirst().row().rsn();
        DataArray clans = DataArray.empty();
        for (Membership m : memberships) {
            MemberRow row = m.row();
            clans.add(DataObject.empty().put("slug", m.clan().slug()).put("name", m.clan().displayName()).put("rank", row.clanRank())
                    .put("rankOrder", row.rankOrder()).put("maxRankOrder", m.maxRankOrder())
                    .put("firstSeen", row.firstSeen() == null ? null : row.firstSeen().toString())
                    .put("joined", row.clanJoinedAt() != null ? row.clanJoinedAt().toString() : row.firstSeen() == null ? null : row.firstSeen().toLocalDate().toString())
                    .put("joinedExact", row.clanJoinedAt() != null)
                    .put("lastPolled", row.lastPolled() == null ? null : row.lastPolled().toString()));
        }
        return DataObject.empty().put("rsn", name).put("clans", clans).put("stats", stats(name, source, now));
    }

    /** The newest snapshot's totals and skills, with XP gained over the last day, week and month ({@link SiteApi#gainSince}); null with none. */
    static DataObject stats(String rsn, Source source, OffsetDateTime now) {
        List<StatsSnapshotRow> snapshots = new ArrayList<>(source.snapshots(rsn));
        if (snapshots.isEmpty()) return null;
        snapshots.sort(Comparator.comparing(StatsSnapshotRow::snapshotAt).reversed());
        StatsSnapshotRow latest = snapshots.getFirst();
        DataArray skills = DataArray.empty();
        for (SkillValue skill : source.skills(latest.snapshotId())) {
            skills.add(DataObject.empty().put("id", skill.skillId()).put("name", RuneScapeSkillCatalog.nameFor(skill.skillId())).put("level", skill.level())
                    .put("xp", skill.xp()).put("rank", skill.rank()));
        }
        return DataObject.empty().put("snapshotAt", latest.snapshotAt().toInstant().toString()).put("totalLevel", latest.totalLevel())
                .put("totalXp", latest.totalXp()).put("combatLevel", latest.combatLevel()).put("questsComplete", latest.questsComplete())
                .put("skills", skills)
                .put("gains", DataObject.empty()
                        .put("day", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(1))))
                        .put("week", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(7))))
                        .put("month", SiteApi.gainSince(snapshots, now.minus(Duration.ofDays(30)))));
    }
}
