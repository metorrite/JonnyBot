package com.younglings.bot.tracking;

import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.ClanMemberRepository;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.wheel.WheelEligibility;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Who is on the Citadel wheel for a week: the accounts that capped, reduced to one entry per person and without staff (see {@link WheelEligibility}).
 * Admin in Discord means the Administrator permission or the bot's Admin tier; admin in the clan means the "Admin" rank or higher on the server's
 * rank list. Used by the Discord wheel, its Copy names button and the dashboard's wheel page, so all three agree.
 */
@BService
public class CitadelWheelEntries {
    private static final Logger log = LoggerFactory.getLogger(CitadelWheelEntries.class);
    static final String ADMIN_RANK = "Admin";

    private final WeeklyDigestService digest;
    private final ClanMemberRepository clanMembers;
    private final PlayerLinkRepository links;
    private final ClanPointsRepository ranks;
    private final AdminRoleFilter adminFilter;

    public CitadelWheelEntries(WeeklyDigestService digest, ClanMemberRepository clanMembers, PlayerLinkRepository links, ClanPointsRepository ranks, AdminRoleFilter adminFilter) {
        this.digest = digest;
        this.clanMembers = clanMembers;
        this.links = links;
        this.ranks = ranks;
        this.adminFilter = adminFilter;
    }

    public WheelEligibility.Result forWeek(Guild guild, OffsetDateTime start, OffsetDateTime end) {
        long guildId = guild.getIdLong();
        List<String> cappers = digest.getCappedRsns(guildId, start, end);

        Map<String, String> rankByRsn = new HashMap<>();
        for (var member : clanMembers.getAll(guildId, true)) rankByRsn.put(member.rsn().toLowerCase(Locale.ROOT), member.clanRank());
        var predicate = WheelEligibility.atLeast(ranks.getRanksOrdered(guildId).stream().map(ClanPointsRepository.RankConfigRow::rankName).toList(), ADMIN_RANK);

        List<WheelEligibility.Link> accountLinks = links.getAllLinks(guildId).stream().map(l -> new WheelEligibility.Link(l.rsn(), l.discordUserId())).toList();
        Map<Long, Boolean> adminCache = new HashMap<>();
        return WheelEligibility.select(cappers, rankByRsn, predicate, accountLinks, user -> adminCache.computeIfAbsent(user, id -> isDiscordAdmin(guild, id)));
    }

    private boolean isDiscordAdmin(Guild guild, long userId) {
        try {
            Member member = guild.getMemberById(userId);
            if (member == null) member = guild.retrieveMemberById(userId).submit().get(5, TimeUnit.SECONDS);
            return member.hasPermission(Permission.ADMINISTRATOR) || adminFilter.isAuthorized(guild, member);
        } catch (Exception e) {
            // someone who has left the server, or a lookup that failed, isn't treated as staff
            log.debug("Couldn't check whether {} is an admin", userId, e);
            return false;
        }
    }
}
