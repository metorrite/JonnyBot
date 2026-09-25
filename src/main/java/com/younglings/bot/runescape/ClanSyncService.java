package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers and tracks the clan's roster from the Clan Hiscores API, independent of
 * {@link PlayerLinkService}'s verified links — a name shows up here, and gets its full stats
 * polled, whether or not anyone has claimed it belongs to them. This is what makes "track the
 * whole clan even where nobody's verified" possible, since {@link RuneScapeStatsService#pollAndSnapshot}
 * only ever needed a raw RSN to begin with, not a link.
 */
@BService
public class ClanSyncService {
    private static final Logger log = LoggerFactory.getLogger(ClanSyncService.class);

    private final RuneScapeApiClient apiClient;
    private final ClanMemberRepository clanMemberRepository;
    private final RuneScapeStatsService statsService;
    private final RsnRenameService renameService;
    private final GuildSettingsService guildSettingsService;
    private final BotConfig botConfig;

    public ClanSyncService(RuneScapeApiClient apiClient, ClanMemberRepository clanMemberRepository,
                            RuneScapeStatsService statsService, RsnRenameService renameService,
                            GuildSettingsService guildSettingsService, BotConfig botConfig) {
        this.apiClient = apiClient;
        this.clanMemberRepository = clanMemberRepository;
        this.statsService = statsService;
        this.renameService = renameService;
        this.guildSettingsService = guildSettingsService;
        this.botConfig = botConfig;
    }

    public record SyncResult(int rosterSize, int newMembers, int departedMembers, int polled, int pollFailed) {}

    /** This guild's configured clan name (own override, or the {@code BotConfig} default) — {@code null} if never configured either way. */
    public String getClanName(long guildId) {
        return guildSettingsService.getEffective(guildId).clanName();
    }

    /** The tracked roster from the last sync — {@code activeOnly} excludes members no longer seen in the clan. */
    public List<ClanMemberRepository.ClanMemberRow> getRoster(long guildId, boolean activeOnly) {
        return clanMemberRepository.getAll(guildId, activeOnly);
    }

    /** Manual dev backfill for a clan member's join date — see {@link ClanMemberRepository#setClanJoinedAt}. */
    public boolean setClanJoinedAt(long guildId, String rsn, LocalDate joinedAt) {
        return clanMemberRepository.setClanJoinedAt(guildId, rsn, joinedAt);
    }

    /**
     * Refreshes the roster (adds new members, updates ranks/XP/kills, marks anyone no longer listed
     * as inactive) and then polls every currently-listed member's full RuneMetrics profile, same as
     * a manual "Poll Now" would for a linked player. Spaced out by
     * {@link BotConfig#getRunescapePollDelaySeconds()} between members, same tuning knob the
     * (currently-disabled) auto-poll scheduler uses — for a clan this size that means this call
     * blocks for a couple of minutes, which is expected, not a hang.
     * <p>
     * Also runs rename detection ({@link RsnRenameService}) against this cycle's departed/new sets
     * — takes a {@link Guild}, not just a guild ID, since a detected rename may need to DM the
     * linked player or post to the admin alert channel. Returns an all-zero {@link SyncResult} if
     * this guild has no clan name configured yet (via {@code /configure}) — callers should check
     * {@link #getClanName} first to tell that apart from "fetch failed" with a clearer message.
     */
    public SyncResult syncAndPoll(Guild guild) {
        long guildId = guild.getIdLong();
        String clanName = getClanName(guildId);
        if (clanName == null) return new SyncResult(0, 0, 0, 0, 0);

        List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(clanName);
        if (roster.isEmpty()) return new SyncResult(0, 0, 0, 0, 0);

        List<ClanMemberRepository.ClanMemberRow> before = clanMemberRepository.getAll(guildId, true);
        Set<String> beforeLower = new HashSet<>();
        for (var row : before) beforeLower.add(row.rsn().toLowerCase());

        Set<String> currentLower = new HashSet<>();
        Set<String> newLower = new HashSet<>();
        for (var member : roster) {
            clanMemberRepository.upsert(guildId, member.rsn(), member.clanRank(), member.totalXp(), member.kills());
            String lower = member.rsn().toLowerCase();
            currentLower.add(lower);
            if (!beforeLower.contains(lower)) newLower.add(lower);
        }

        Set<String> departedLower = new HashSet<>();
        for (String rsnLower : beforeLower) {
            if (!currentLower.contains(rsnLower)) {
                clanMemberRepository.markInactive(guildId, rsnLower);
                departedLower.add(rsnLower);
            }
        }

        long delayMs = botConfig.getRunescapePollDelaySeconds() * 1000;
        int polled = 0;
        int pollFailed = 0;
        // Only kept for names that just appeared this cycle — the rename check is the only thing
        // that needs the full profile result, not just pass/fail, and there's no reason to hold onto
        // every other roster member's full skills/activities in memory once its snapshot is saved.
        Map<String, ProfileResult> newMemberResults = new HashMap<>();
        for (var member : roster) {
            try {
                ProfileResult result = statsService.pollAndSnapshotResult(guildId, member.rsn());
                if (result instanceof ProfileResult.Found) polled++;
                else pollFailed++;

                String lower = member.rsn().toLowerCase();
                if (newLower.contains(lower)) newMemberResults.put(lower, result);

                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Failed to poll clan member '{}' during sync", member.rsn(), e);
                pollFailed++;
            }
        }

        try {
            renameService.detectAndNotify(guild, before, roster, departedLower, newLower, newMemberResults);
        } catch (Exception e) {
            log.error("Rename detection failed during clan sync for guild {}", guildId, e);
        }

        log.info("Clan sync for '{}' (guild {}) finished: {} in roster, {} new, {} departed, {}/{} polled successfully.",
                clanName, guildId, roster.size(), newLower.size(), departedLower.size(), polled, roster.size());
        return new SyncResult(roster.size(), newLower.size(), departedLower.size(), polled, pollFailed);
    }
}
