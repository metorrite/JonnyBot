package com.younglings.bot.runescape;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.tracking.ClassifiedEntry;
import com.younglings.bot.tracking.TrackingEventRouter;
import com.younglings.bot.tracking.TrackingGroup;
import com.younglings.bot.tracking.TrackingIconCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final TrackingEventRouter trackingEventRouter;
    private final WeeklyDigestRepository weeklyDigestRepository;
    private final TrackingIconCatalog trackingIconCatalog;
    private final VerificationRoleSyncService roleSyncService;
    private final PlayerLinkService linkService;

    /** Held while a refresh reads the stored roster, rewrites it and announces the difference. */
    private final Object rosterLock = new Object();
    /** When each guild's roster was last refreshed (by anything), as epoch millis — what {@link #refreshRosterIfStale} throttles on. */
    private final Map<Long, Long> lastRosterRefresh = new ConcurrentHashMap<>();
    private static final long QUICK_REFRESH_MIN_INTERVAL_MS = 60_000L;
    /** Fewer vanished names than this are never treated as a bad response, however small the clan. */
    private static final int MIN_DEPARTURE_CAP = 5;

    public ClanSyncService(RuneScapeApiClient apiClient, ClanMemberRepository clanMemberRepository,
                            RuneScapeStatsService statsService, RsnRenameService renameService,
                            GuildSettingsService guildSettingsService, BotConfig botConfig,
                            TrackingEventRouter trackingEventRouter, WeeklyDigestRepository weeklyDigestRepository,
                            TrackingIconCatalog trackingIconCatalog, VerificationRoleSyncService roleSyncService,
                            PlayerLinkService linkService) {
        this.apiClient = apiClient;
        this.clanMemberRepository = clanMemberRepository;
        this.statsService = statsService;
        this.renameService = renameService;
        this.guildSettingsService = guildSettingsService;
        this.botConfig = botConfig;
        this.trackingEventRouter = trackingEventRouter;
        this.weeklyDigestRepository = weeklyDigestRepository;
        this.trackingIconCatalog = trackingIconCatalog;
        this.roleSyncService = roleSyncService;
        this.linkService = linkService;
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

        RosterChange change = refreshRoster(guild, clanName);
        if (change == null) return new SyncResult(0, 0, 0, 0, 0);
        List<RuneScapeApiClient.ClanMember> roster = change.roster();

        // Only kept for names that just appeared this cycle — the rename check is the only thing
        // that needs the full profile result, not just pass/fail, and there's no reason to hold onto
        // every other roster member's full skills/activities in memory once its snapshot is saved.
        // This poll isn't spread across a window like RosterPollScheduler's — it needs everyone's
        // current data now, as fast as the API's safe floor allows, so the daily diff/rename check has
        // it to work with immediately.
        Map<String, ProfileResult> newMemberResults = new HashMap<>();
        long floorDelayMs = botConfig.getRunescapePollDelaySeconds() * 1000L;
        RequestPacer.Stats paceBefore = statsService.requestStats();
        PollTally tally = pollRsns(roster.stream().map(RuneScapeApiClient.ClanMember::rsn).toList(), floorDelayMs, change.newLower(), newMemberResults);
        int polled = tally.polled();
        int pollFailed = tally.pollFailed();

        Set<String> maybeRenamed = detectRenames(guild, change, newMemberResults);
        applyMembershipRoles(guild, change, maybeRenamed);

        log.info("Clan sync for '{}' (guild {}) finished: {} in roster, {} new, {} departed, {}/{} polled successfully. RuneMetrics, bot-wide during the sync: {}.",
                clanName, guildId, roster.size(), change.newLower().size(), change.departedLower().size(), polled, roster.size(),
                statsService.requestStats().minus(paceBefore).describe());
        return new SyncResult(roster.size(), change.newLower().size(), change.departedLower().size(), polled, pollFailed);
    }

    /** What the clan roster looked like before and after one refresh: who appeared and who vanished. */
    private record RosterChange(List<RuneScapeApiClient.ClanMember> roster, List<ClanMemberRepository.ClanMemberRow> before,
                                List<String> newNames, Set<String> newLower, List<String> departedNames, Set<String> departedLower) {}

    /** What {@link #refreshRosterIfStale} found: whether it looked at all, and which Discord users' linked accounts have just joined the clan. */
    public record QuickRefresh(boolean refreshed, Set<Long> joinedDiscordUserIds) {
        static final QuickRefresh SKIPPED = new QuickRefresh(false, Set.of());
    }

    /** Fetches the clan list once, updates the stored roster to match, and announces joins and leaves. Doesn't poll anyone: that's what makes it quick. Null if there's nothing to go on (no clan configured, or the fetch came back empty). */
    private RosterChange refreshRoster(Guild guild, String clanName) {
        long guildId = guild.getIdLong();
        // One refresh at a time: a manual refresh and the daily sync both read "who was in the roster", change it,
        // and announce the difference, so two at once would each announce the same join.
        synchronized (rosterLock) {
            lastRosterRefresh.put(guildId, System.currentTimeMillis());

            List<RuneScapeApiClient.ClanMember> roster = apiClient.fetchClanRoster(clanName);
            if (roster.isEmpty()) return null;

            List<ClanMemberRepository.ClanMemberRow> before = clanMemberRepository.getAll(guildId, true);
            Set<String> beforeLower = new HashSet<>();
            for (var row : before) beforeLower.add(row.rsn().toLowerCase());
            // The very first sync for a guild has an empty `before` — every roster member would
            // otherwise look "new" and flood the joins/leaves channel with the whole clan at once.
            boolean firstSyncEver = before.isEmpty();

            Set<String> currentLower = new HashSet<>();
            Set<String> newLower = new HashSet<>();
            List<String> newNames = new ArrayList<>();
            for (var member : roster) {
                clanMemberRepository.upsert(guildId, member.rsn(), member.clanRank(), member.totalXp(), member.kills());
                String lower = member.rsn().toLowerCase();
                currentLower.add(lower);
                if (!beforeLower.contains(lower)) {
                    newLower.add(lower);
                    newNames.add(member.rsn());
                }
            }

            Set<String> departedLower = new HashSet<>();
            List<String> departedNames = new ArrayList<>();
            for (var row : before) {
                String rsnLower = row.rsn().toLowerCase();
                if (!currentLower.contains(rsnLower)) {
                    clanMemberRepository.markInactive(guildId, rsnLower);
                    departedLower.add(rsnLower);
                    departedNames.add(row.rsn());
                }
            }

            // The day of detection, not an exact time — a roster diff only runs when someone asks for one
            // (the daily sync, or a member updating themselves), so that's all this moment actually knows.
            // Also becomes clan_joined_at for a brand-new member automatically (see the loop below); an
            // existing member's clan_joined_at is never touched here, since for them "today" would be
            // wrong — it stays whatever's been manually backfilled for the roster as it stood when this
            // system was built.
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String todayDisplay = today.format(DateTimeFormatter.ofPattern("MMM d"));

            if (!firstSyncEver) {
                // No dedicated icon for a join/leave — the Clan Citadel icon fills in as the general
                // "clan" icon here, not RuneScore (a personal-achievement icon that doesn't fit a
                // clan-membership event).
                String icon = trackingIconCatalog.mentionForCategory("citadel");
                String iconPrefix = icon != null ? icon + " " : "";

                List<ClassifiedEntry> joinLeaveEntries = new ArrayList<>();
                for (String name : newNames) {
                    joinLeaveEntries.add(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, iconPrefix + "**" + name + "** joined the clan. (" + todayDisplay + ")"));
                    weeklyDigestRepository.recordRosterEvent(guildId, name, "JOIN");
                    clanMemberRepository.setClanJoinedAt(guildId, name, today);
                }
                for (String name : departedNames) {
                    joinLeaveEntries.add(new ClassifiedEntry(TrackingGroup.CLAN_JOINS_LEAVES, iconPrefix + "**" + name + "** left the clan. (" + todayDisplay + ")"));
                    weeklyDigestRepository.recordRosterEvent(guildId, name, "LEAVE");
                }
                trackingEventRouter.dispatchAll(guild, joinLeaveEntries);
            }

            return new RosterChange(roster, before, newNames, newLower, departedNames, departedLower);
        }
    }

    /**
     * Pairs this refresh's vanished names with its appeared ones as possible renames. Returns the vanished
     * names it couldn't rule out (see {@link RsnRenameService#detectAndNotify}); if the check itself fails,
     * that is every vanished name, so nobody is demoted on the strength of a roster diff nobody could vet.
     */
    private Set<String> detectRenames(Guild guild, RosterChange change, Map<String, ProfileResult> newMemberResults) {
        try {
            return renameService.detectAndNotify(guild, change.before(), change.roster(), change.departedLower(), change.newLower(), newMemberResults);
        } catch (Exception e) {
            log.error("Rename detection failed during clan sync for guild {}", guild.getIdLong(), e);
            return change.departedLower();
        }
    }

    /**
     * What a member updating themselves does when their RSN isn't on the stored roster yet: fetches the clan
     * list and updates the roster from it — one request and a few database writes, nobody's profile is polled
     * — and, if their name (or anyone else's) has just appeared, gives that linked player their Member role
     * straight away instead of at the next daily sync. Guild-wide, at most one fetch a minute, so a run of
     * people pressing Update doesn't turn into a run of requests to Jagex; within that minute it does nothing.
     * <p>
     * Only when a name has both vanished and appeared does it poll anything (just the appeared names), because
     * rename detection needs their profiles to tell a rename from a leave and a join.
     */
    public QuickRefresh refreshRosterIfStale(Guild guild) {
        long guildId = guild.getIdLong();
        String clanName = getClanName(guildId);
        if (clanName == null) return QuickRefresh.SKIPPED;

        Long last = lastRosterRefresh.get(guildId);
        if (last != null && System.currentTimeMillis() - last < QUICK_REFRESH_MIN_INTERVAL_MS) return QuickRefresh.SKIPPED;

        RosterChange change = refreshRoster(guild, clanName);
        if (change == null) return QuickRefresh.SKIPPED;

        Map<String, ProfileResult> newMemberResults = new HashMap<>();
        if (!change.newLower().isEmpty() && !change.departedLower().isEmpty()) {
            long floorDelayMs = botConfig.getRunescapePollDelaySeconds() * 1000L;
            pollRsns(change.newNames(), floorDelayMs, change.newLower(), newMemberResults);
        }
        Set<String> maybeRenamed = detectRenames(guild, change, newMemberResults);

        log.info("Quick roster refresh for '{}' (guild {}): {} in roster, {} new, {} departed.",
                clanName, guildId, change.roster().size(), change.newLower().size(), change.departedLower().size());
        return new QuickRefresh(true, applyMembershipRoles(guild, change, maybeRenamed));
    }

    /**
     * Brings Discord roles in line with a roster refresh: a linked player whose RSN just joined gets Member,
     * one whose RSN just left goes back to Guest (unless another of their accounts is still in the clan).
     * Skipped for a name that might be a rename — its player hasn't left, they've changed name, and an admin
     * hasn't yet said so — and for departures altogether when an implausible share of the roster vanished at
     * once, which is far likelier a bad response from Jagex than a real exodus. Returns the Discord users
     * whose joins were applied.
     */
    private Set<Long> applyMembershipRoles(Guild guild, RosterChange change, Set<String> maybeRenamedLower) {
        long guildId = guild.getIdLong();
        Set<Long> joined = new HashSet<>();

        for (String name : change.newNames()) {
            try {
                PlayerLink link = linkService.getLinkForRsn(guildId, name);
                if (link == null) continue;
                roleSyncService.syncRoles(guild, link.discordUserId(), name);
                joined.add(link.discordUserId());
            } catch (Exception e) {
                log.warn("Couldn't update roles for '{}' joining the clan", name, e);
            }
        }

        int departureCap = Math.max(MIN_DEPARTURE_CAP, change.before().size() / 5);
        if (change.departedNames().size() > departureCap) {
            log.warn("{} of {} clan members vanished from the roster at once in guild {} — treating it as a bad response and leaving their roles alone.",
                    change.departedNames().size(), change.before().size(), guildId);
            return joined;
        }
        for (String name : change.departedNames()) {
            if (maybeRenamedLower.contains(name.toLowerCase())) continue;
            try {
                PlayerLink link = linkService.getLinkForRsn(guildId, name);
                if (link != null) roleSyncService.syncLeftClan(guild, link.discordUserId());
            } catch (Exception e) {
                log.warn("Couldn't update roles for '{}' leaving the clan", name, e);
            }
        }
        return joined;
    }

    /** Whether {@code rsn} is on the stored roster as a current clan member — a single lookup, no fetch. */
    public boolean isActiveMember(long guildId, String rsn) {
        return clanMemberRepository.isActiveMember(guildId, rsn);
    }

    public record RosterPollResult(int polled, int pollFailed) {}

    /**
     * Polls every currently-active clan-roster member's RuneMetrics profile, spread evenly across
     * {@code window} — see {@link PollPacing} — instead of bursting everyone at once. Tracking dispatch
     * (drops/levels/quests/Citadel/etc., see {@link RuneScapeStatsService#pollAndSnapshotResult})
     * happens as a side effect of that poll, same as it does during {@link #syncAndPoll}. Doesn't touch
     * the roster itself or run rename detection — that's still {@link #syncAndPoll}'s job, once a day;
     * this is what keeps every member's data (and the tracking feed) fresh the rest of the time,
     * regardless of whether they've ever linked their Discord to their RSN.
     */
    public RosterPollResult pollActiveRosterOnly(long guildId, Duration window) {
        List<String> rsns = clanMemberRepository.getAll(guildId, true).stream()
                .map(ClanMemberRepository.ClanMemberRow::rsn)
                .toList();
        if (rsns.isEmpty()) return new RosterPollResult(0, 0);

        long delayMs = PollPacing.evenSpreadDelayMs(rsns.size(), window, botConfig.getRunescapePollDelaySeconds() * 1000L);
        PollTally tally = pollRsns(rsns, delayMs, Set.of(), new HashMap<>());
        return new RosterPollResult(tally.polled(), tally.pollFailed());
    }

    private record PollTally(int polled, int pollFailed) {}

    /** Shared by {@link #syncAndPoll} and {@link #pollActiveRosterOnly} — {@code newLower}/{@code newMemberResultsOut} are only meaningful for the former (rename detection needs the full profile of a name that just appeared); pass {@code Set.of()}/a throwaway map otherwise. */
    private PollTally pollRsns(List<String> rsns, long delayMs, Set<String> newLower, Map<String, ProfileResult> newMemberResultsOut) {
        int polled = 0;
        int pollFailed = 0;

        for (String rsn : rsns) {
            try {
                ProfileResult result = statsService.pollAndSnapshotResult(rsn);
                if (result instanceof ProfileResult.Found) polled++;
                else pollFailed++;

                String lower = rsn.toLowerCase();
                if (newLower.contains(lower)) newMemberResultsOut.put(lower, result);

                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Failed to poll clan member '{}'", rsn, e);
                pollFailed++;
            }
        }

        return new PollTally(polled, pollFailed);
    }
}
