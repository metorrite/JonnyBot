package com.younglings.bot.clanprofile;

import com.younglings.bot.runescape.ClanMemberRepository.ClanMemberRow;
import com.younglings.bot.runescape.ClanSyncService;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Reads a server's profile in the context of its current clan, and verifies the clan on behalf of a leader. */
@BService
public class ClanProfileService {
    private static final Logger log = LoggerFactory.getLogger(ClanProfileService.class);

    /** The outcome of a verification attempt: either it worked, or {@code problem} says why not. */
    public record Verification(boolean ok, String problem) {}

    private final ClanProfileRepository repository;
    private final ClanSyncService clanSync;
    private final PlayerLinkService links;

    public ClanProfileService(ClanProfileRepository repository, ClanSyncService clanSync, PlayerLinkService links) {
        this.repository = repository;
        this.clanSync = clanSync;
        this.links = links;
    }

    /** The server's profile, or an empty private one if it has never saved any. */
    public ClanProfile profileOf(long guildId) {
        return repository.get(guildId).orElseGet(() -> ClanProfile.empty(guildId));
    }

    /** The RuneScape clan this server is configured for (null if none). */
    public String clanNameOf(long guildId) {
        String name = clanSync.getClanName(guildId);
        return name == null || name.isBlank() ? null : name.strip();
    }

    /** The active members on the clan's roster, counted by the database (the hub lists every clan with this). */
    public int memberCount(long guildId) {
        return clanSync.countRoster(guildId, true);
    }

    /**
     * Checks that {@code actorId} is a leader of the server's clan, as far as JonnyBot can tell: the clan exists with a roster, no other
     * server has verified it, and one of the RuneScape names the person linked in this server is on the roster as an Owner or
     * Deputy Owner. On success the verification is stored against the clan name, so renaming the clan later un-verifies it.
     */
    public Verification verify(long guildId, long actorId) {
        String clan = clanNameOf(guildId);
        List<ClanMemberRow> roster = clan == null ? List.of() : clanSync.getRoster(guildId, true);
        Map<String, String> rankByName = new HashMap<>();
        for (ClanMemberRow row : roster) rankByName.put(row.rsn().toLowerCase(Locale.ROOT), row.clanRank());

        List<PlayerLink> mine = links.getLinksForUser(guildId, actorId);
        List<String> ranks = new ArrayList<>();
        for (PlayerLink link : mine) {
            String rank = rankByName.get(link.rsn().toLowerCase(Locale.ROOT));
            if (rank != null) ranks.add(rank);
        }
        boolean claimed = clan != null && repository.verifiedHolder(clan, guildId).isPresent();

        String problem = ProfileRules.verificationProblem(clan, roster.size(), claimed, mine.size(), ranks);
        if (problem != null) return new Verification(false, problem);
        repository.setVerified(guildId, clan, actorId);
        log.info("Clan '{}' verified for guild {} by {}", clan, guildId, actorId);
        return new Verification(true, null);
    }

    /** The profile as the public may see it, or empty when it is hidden (private, suspended, or public without a verified clan). */
    public Optional<ClanProfile> publicView(long guildId, boolean allowUnlisted) {
        return publicView(profileOf(guildId), clanNameOf(guildId), allowUnlisted);
    }

    /**
     * The same, for a profile already loaded (a list of them) and its server's clan name ({@link #clanNameOf}), so a list of clans doesn't
     * fetch each profile and clan name again.
     */
    public Optional<ClanProfile> publicView(ClanProfile profile, String clanName, boolean allowUnlisted) {
        ProfileRules.Listing listing = ProfileRules.listing(profile, clanName);
        if (listing == ProfileRules.Listing.LISTED || (allowUnlisted && listing == ProfileRules.Listing.UNLISTED)) return Optional.of(profile);
        return Optional.empty();
    }
}
