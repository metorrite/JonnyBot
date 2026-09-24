package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
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

    // This bot serves one specific clan ("Younglings") on one specific server — no multi-clan
    // support exists anywhere else in this codebase, so this isn't made configurable for a
    // hypothetical future guild.
    public static final String CLAN_NAME = "Younglings";

    private final RuneScapeApiClient apiClient;
    private final ClanMemberRepository clanMemberRepository;
    private final RuneScapeStatsService statsService;
    private final BotConfig botConfig;

    public ClanSyncService(RuneScapeApiClient apiClient, ClanMemberRepository clanMemberRepository,
                            RuneScapeStatsService statsService, BotConfig botConfig) {
        this.apiClient = apiClient;
        this.clanMemberRepository = clanMemberRepository;
        this.statsService = statsService;
        this.botConfig = botConfig;
    }

    public record SyncResult(int rosterSize, int newMembers, int departedMembers, int polled, int pollFailed) {}

    /**
     * Refreshes the roster (adds new members, updates ranks, marks anyone no longer listed as
     * inactive) and then polls every currently-listed member's full RuneMetrics profile, same as
     * a manual "Poll Now" would for a linked player. Spaced out by
     * {@link BotConfig#getRunescapePollDelaySeconds()} between members, same tuning knob the
     * (currently-disabled) auto-poll scheduler uses — for a clan this size that means this call
     * blocks for a couple of minutes, which is expected, not a hang.
     */
    public SyncResult syncAndPoll(long guildId) {
        List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(CLAN_NAME);
        if (roster.isEmpty()) return new SyncResult(0, 0, 0, 0, 0);

        Set<String> beforeLower = new HashSet<>();
        for (var row : clanMemberRepository.getAll(guildId, true)) beforeLower.add(row.rsn().toLowerCase());

        Set<String> currentLower = new HashSet<>();
        int newMembers = 0;
        for (var member : roster) {
            clanMemberRepository.upsert(guildId, member.rsn(), member.clanRank());
            String lower = member.rsn().toLowerCase();
            currentLower.add(lower);
            if (!beforeLower.contains(lower)) newMembers++;
        }

        int departed = 0;
        for (String rsnLower : beforeLower) {
            if (!currentLower.contains(rsnLower)) {
                clanMemberRepository.markInactive(guildId, rsnLower);
                departed++;
            }
        }

        long delayMs = botConfig.getRunescapePollDelaySeconds() * 1000;
        int polled = 0;
        int pollFailed = 0;
        for (var member : roster) {
            try {
                if (statsService.pollAndSnapshot(guildId, member.rsn()).isPresent()) polled++;
                else pollFailed++;
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Failed to poll clan member '{}' during sync", member.rsn(), e);
                pollFailed++;
            }
        }

        log.info("Clan sync for '{}' finished: {} in roster, {} new, {} departed, {}/{} polled successfully.",
                CLAN_NAME, roster.size(), newMembers, departed, polled, roster.size());
        return new SyncResult(roster.size(), newMembers, departed, polled, pollFailed);
    }
}
