package com.younglings.bot.internal;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.internal.SiteStatsRepository.MemberRow;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.SlowPollQueue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard's views and small tools beyond plain settings: the roster as an operations table (who's verified,
 * when each member was last refreshed and when they're due next, Citadel status, points), a "needs attention" list,
 * the bot's health, the audit log, private staff notes on members, and messages scheduled to post later.
 * Admin-tier only — it exposes Discord names next to RuneScape names, which the public site never does.
 */
@BService
public class AdminOpsApi {
    /** Every roster member is refreshed once per cycle (RosterPollScheduler spreads them across this window). */
    static final Duration POLL_CYCLE = Duration.ofHours(3);
    private static final Duration STALE_AFTER = Duration.ofHours(8);
    private static final Duration INACTIVE_AFTER = Duration.ofDays(30);
    private static final int MAX_NOTE = 1000;

    private final SiteStatsRepository stats;
    private final PlayerLinkRepository links;
    private final AdminToolsStore store;
    private final ClanAdminApi clanAdmin;
    private final SlowPollQueue slowPolls;
    private final BotConfig config;

    public AdminOpsApi(SiteStatsRepository stats, PlayerLinkRepository links, AdminToolsStore store, ClanAdminApi clanAdmin, SlowPollQueue slowPolls, BotConfig config) {
        this.stats = stats;
        this.links = links;
        this.store = store;
        this.clanAdmin = clanAdmin;
        this.slowPolls = slowPolls;
        this.config = config;
    }

    private static String iso(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    // ---------- the roster ----------

    private record Roster(List<MemberRow> rows, Map<String, PlayerLink> linkByRsn, Map<String, OffsetDateTime> lastActivity, Map<String, Boolean> citadelThisWeek,
                          Map<String, Integer> notes, LocalDate weekStart) {}

    private Roster roster(Guild guild) {
        long guildId = guild.getIdLong();
        LocalDate weekStart = RecapPeriod.citadelWeekStart(LocalDate.now(ZoneOffset.UTC));
        Map<String, PlayerLink> linkByRsn = new HashMap<>();
        for (PlayerLink link : links.getAllLinks(guildId)) linkByRsn.putIfAbsent(link.rsn().toLowerCase(Locale.ROOT), link);
        Map<String, Boolean> citadel = new HashMap<>();
        for (var week : stats.citadelGrid(guildId, 1)) if (week.weekStart().equals(weekStart)) citadel.put(week.rsn().toLowerCase(Locale.ROOT), week.capped());
        return new Roster(stats.members(guildId), linkByRsn, store.lastActivity(guildId), citadel, store.noteCounts(guildId), weekStart);
    }

    DataObject members(Guild guild) {
        Roster r = roster(guild);
        boolean autoPoll = config.getRunescapeAutoPollEnabled();
        DataArray rows = DataArray.empty();
        r.rows().stream().sorted(Comparator.comparingInt(MemberRow::rankOrder).reversed().thenComparing(m -> m.rsn().toLowerCase(Locale.ROOT))).forEach(m -> {
            String key = m.rsn().toLowerCase(Locale.ROOT);
            PlayerLink link = r.linkByRsn().get(key);
            Member discord = link == null ? null : guild.getMemberById(link.discordUserId());
            Boolean capped = r.citadelThisWeek().get(key);
            OffsetDateTime last = m.lastPolled();
            rows.add(DataObject.empty().put("rsn", m.rsn()).put("rank", m.clanRank()).put("rankOrder", m.rankOrder())
                    .put("points", m.points()).put("promotionNeeded", m.promotionNeeded())
                    .put("verified", m.verified()).put("verificationMethod", link == null ? null : link.verificationMethod()).put("verifiedAt", link == null ? null : iso(link.verifiedAt()))
                    .put("discordId", link == null ? null : Long.toString(link.discordUserId())).put("discordName", discord == null ? null : discord.getEffectiveName())
                    .put("totalLevel", m.totalLevel()).put("combatLevel", m.combatLevel()).put("totalXp", m.totalXp()).put("kills", m.kills())
                    .put("joinedAt", m.clanJoinedAt() == null ? null : m.clanJoinedAt().toString()).put("firstSeen", iso(m.firstSeen()))
                    .put("lastPolled", iso(last)).put("nextPoll", autoPoll && last != null ? iso(last.plus(POLL_CYCLE)) : null)
                    .put("lastActivity", iso(r.lastActivity().get(key)))
                    .put("visitedThisWeek", capped != null).put("cappedThisWeek", capped != null && capped)
                    .put("notes", r.notes().getOrDefault(key, 0)));
        });
        return DataObject.empty().put("members", rows).put("pollCycleSeconds", POLL_CYCLE.toSeconds()).put("autoPoll", autoPoll)
                .put("weekStart", r.weekStart().toString()).put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
    }

    // ---------- needs attention ----------

    private static DataObject item(String rsn, String detail) {
        return DataObject.empty().put("rsn", rsn).put("detail", detail);
    }

    DataObject attention(Guild guild) {
        Roster r = roster(guild);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        DataArray unverified = DataArray.empty();
        DataArray stale = DataArray.empty();
        DataArray inactive = DataArray.empty();
        DataArray promotions = DataArray.empty();
        DataArray notCapped = DataArray.empty();
        for (MemberRow m : r.rows()) {
            String key = m.rsn().toLowerCase(Locale.ROOT);
            if (!m.verified()) unverified.add(item(m.rsn(), m.clanRank() + " · no Discord account linked"));
            if (m.lastPolled() == null) stale.add(item(m.rsn(), "never refreshed"));
            else if (Duration.between(m.lastPolled(), now).compareTo(STALE_AFTER) > 0) stale.add(item(m.rsn(), "last refreshed " + Duration.between(m.lastPolled(), now).toHours() + "h ago"));
            OffsetDateTime activity = r.lastActivity().get(key);
            boolean newcomer = m.firstSeen() != null && Duration.between(m.firstSeen(), now).toDays() < 7;
            if (!newcomer && (activity == null || Duration.between(activity, now).compareTo(INACTIVE_AFTER) > 0)) {
                inactive.add(item(m.rsn(), activity == null ? "no adventure-log activity recorded" : "quiet for " + Duration.between(activity, now).toDays() + " days"));
            }
            if (m.promotionNeeded()) promotions.add(item(m.rsn(), m.clanRank() + " · " + m.points() + " points"));
            Boolean capped = r.citadelThisWeek().get(key);
            if (capped == null || !capped) notCapped.add(item(m.rsn(), capped == null ? "hasn't visited the Citadel this week" : "visited but hasn't capped"));
        }
        return DataObject.empty().put("total", r.rows().size()).put("weekStart", r.weekStart().toString())
                .put("unverified", unverified).put("stale", stale).put("inactive", inactive).put("promotions", promotions).put("notCapped", notCapped);
    }

    // ---------- health ----------

    DataObject health(Guild guild) {
        long guildId = guild.getIdLong();
        Runtime rt = Runtime.getRuntime();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MemberRow> rows = stats.members(guildId);
        long polledRecently = rows.stream().filter(m -> m.lastPolled() != null && Duration.between(m.lastPolled(), now).compareTo(POLL_CYCLE.plusHours(1)) <= 0).count();
        long stale = rows.stream().filter(m -> m.lastPolled() == null || Duration.between(m.lastPolled(), now).compareTo(STALE_AFTER) > 0).count();
        OffsetDateTime newestPoll = rows.stream().map(MemberRow::lastPolled).filter(t -> t != null).max(Comparator.naturalOrder()).orElse(null);
        long dbMs = store.pingDatabaseMillis();

        return DataObject.empty()
                .put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000)
                .put("startedAt", iso(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(ManagementFactory.getRuntimeMXBean().getUptime() / 1000)))
                .put("memoryUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).put("memoryMaxMb", rt.maxMemory() / (1024 * 1024))
                .put("discord", DataObject.empty().put("status", guild.getJDA().getStatus().name()).put("gatewayPingMs", guild.getJDA().getGatewayPing()).put("members", guild.getMemberCount()))
                .put("database", DataObject.empty().put("ok", dbMs >= 0).put("pingMs", dbMs))
                .put("environment", DataObject.empty().put("live", config.getLiveEnvironment()).put("siteUrlConfigured", config.getSiteUrl() != null).put("autoPoll", config.getRunescapeAutoPollEnabled()))
                .put("polling", DataObject.empty().put("rosterSize", rows.size()).put("refreshedRecently", polledRecently).put("stale", stale).put("newestRefresh", iso(newestPoll))
                        .put("cycleSeconds", POLL_CYCLE.toSeconds()).put("rateLimitedQueue", slowPolls.size()).put("delaySeconds", config.getRunescapePollDelaySeconds()))
                .put("data", DataObject.empty().put("newestActivity", iso(store.newestActivity(guildId))).put("firstSnapshot", iso(stats.firstSnapshotAt(guildId))))
                .put("scheduledPending", store.scheduled(guildId, 0).size());
    }

    // ---------- audit log ----------

    DataObject audit(Guild guild, String limitRaw, String actor, String search) {
        int limit = 100;
        try {
            if (limitRaw != null) limit = Math.max(1, Math.min(500, Integer.parseInt(limitRaw.trim())));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        DataArray entries = DataArray.empty();
        for (var e : store.recentAudit(guild.getIdLong(), limit, actor, search)) {
            entries.add(DataObject.empty().put("id", e.id()).put("actorId", e.actorId()).put("actorName", e.actorName()).put("method", e.method()).put("path", e.path())
                    .put("status", e.status()).put("at", iso(e.at())));
        }
        return DataObject.empty().put("entries", entries);
    }

    // ---------- notes ----------

    DataObject notes(Guild guild, String rsn) {
        if (rsn == null || rsn.isBlank()) throw new ApiError(400, "Say whose notes.");
        DataArray out = DataArray.empty();
        for (var n : store.notes(guild.getIdLong(), rsn.trim())) {
            out.add(DataObject.empty().put("id", n.id()).put("rsn", n.rsn()).put("note", n.note()).put("authorId", n.authorId()).put("authorName", n.authorName()).put("at", iso(n.at())));
        }
        return DataObject.empty().put("notes", out);
    }

    DataObject addNote(Guild guild, Member actor, DataObject body) {
        String rsn = body.getString("rsn", "").strip();
        String note = body.getString("note", "").strip();
        if (rsn.isEmpty()) throw new ApiError(400, "Choose a member.");
        if (note.isEmpty()) throw new ApiError(400, "Write the note first.");
        if (note.length() > MAX_NOTE) throw new ApiError(400, "Notes are up to " + MAX_NOTE + " characters.");
        if (stats.members(guild.getIdLong()).stream().noneMatch(m -> m.rsn().equalsIgnoreCase(rsn))) throw new ApiError(400, "That name isn't in the clan roster.");
        store.addNote(guild.getIdLong(), rsn, note, actor.getId(), actor.getEffectiveName());
        return notes(guild, rsn);
    }

    DataObject deleteNote(Guild guild, long id, String rsn) {
        if (!store.deleteNote(guild.getIdLong(), id)) throw new ApiError(404, "That note is already gone.");
        return rsn == null || rsn.isBlank() ? DataObject.empty().put("ok", true) : notes(guild, rsn);
    }

    // ---------- scheduled posts ----------

    DataObject scheduled(Guild guild) {
        DataArray out = DataArray.empty();
        for (var p : store.scheduled(guild.getIdLong(), 15)) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, p.channelId());
            out.add(DataObject.empty().put("id", p.id()).put("channelId", Long.toString(p.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("text", p.text()).put("convert", p.convert()).put("sendAt", iso(p.sendAt())).put("status", p.status()).put("createdByName", p.createdByName())
                    .put("createdAt", iso(p.createdAt())).put("error", p.error()));
        }
        return DataObject.empty().put("posts", out);
    }

    DataObject schedulePost(Guild guild, Member actor, DataObject body) {
        OffsetDateTime sendAt;
        try {
            sendAt = OffsetDateTime.parse(body.getString("sendAt", ""));
        } catch (RuntimeException e) {
            throw new ApiError(400, "Pick when it should be posted.");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (sendAt.isBefore(now.plusMinutes(1))) throw new ApiError(400, "Pick a time at least a minute from now.");
        if (sendAt.isAfter(now.plusDays(365))) throw new ApiError(400, "That's more than a year away.");

        long channelId;
        try {
            channelId = Long.parseLong(body.getString("channelId", ""));
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Choose a channel.");
        }
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, "That isn't a text channel in this server.");
        if (!channel.canTalk()) throw new ApiError(400, "JonnyBot can't post in #" + channel.getName() + ".");

        // The same markup checks the live post makes, without sending anything.
        clanAdmin.post(guild, actor, DataObject.empty().put("text", body.getString("text", "")).put("convert", body.getBoolean("convert", false)).put("dryRun", true));

        long id = store.schedule(guild.getIdLong(), channelId, body.getString("text"), body.getBoolean("convert", false), sendAt, actor.getId(), actor.getEffectiveName());
        return DataObject.empty().put("ok", true).put("id", id);
    }

    DataObject cancelScheduled(Guild guild, long id) {
        if (!store.cancel(guild.getIdLong(), id)) throw new ApiError(404, "That post has already been sent or cancelled.");
        return scheduled(guild);
    }

    /** Posts everything that is due; called by the runner. A failure is recorded on the post rather than retried forever. */
    void runDue(net.dv8tion.jda.api.JDA jda) {
        for (var due : store.due()) {
            Guild guild = jda.getGuildById(due.getKey());
            var post = due.getValue();
            if (guild == null) continue; // not connected yet — try again next tick
            try {
                DataObject body = DataObject.empty().put("text", post.text()).put("convert", post.convert()).put("channelId", Long.toString(post.channelId()));
                clanAdmin.post(guild, guild.getSelfMember(), body);
                store.finish(post.id(), "SENT", null);
            } catch (ApiError e) {
                store.finish(post.id(), "FAILED", e.getMessage());
            } catch (RuntimeException e) {
                store.finish(post.id(), "FAILED", "Unexpected error: " + e.getClass().getSimpleName());
            }
        }
    }
}
