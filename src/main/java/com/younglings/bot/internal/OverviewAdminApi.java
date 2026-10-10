package com.younglings.bot.internal;

import com.younglings.bot.commands.poll.PollService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.notice.Notice;
import com.younglings.bot.notice.NoticeRepository;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * What the dashboard's Overview shows: a quick read of what the bot tracks for one server (members, open tickets, signups, polls,
 * whether the bot itself is healthy) and the notices the bot's owner has posted for every server. Read-only apart from the
 * notices, which only the bot's owner may add or remove. Reached only through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard in this server and audit-logs every write.
 */
@BService
public class OverviewAdminApi {
    private static final Logger log = LoggerFactory.getLogger(OverviewAdminApi.class);

    private final AdminOpsApi ops;
    private final TicketRepository tickets;
    private final SiteStatsRepository siteStats;
    private final PollService polls;
    private final NoticeRepository notices;
    private final BotOwners owners;

    public OverviewAdminApi(AdminOpsApi ops, TicketRepository tickets, SiteStatsRepository siteStats, PollService polls, NoticeRepository notices, BotOwners owners) {
        this.ops = ops;
        this.tickets = tickets;
        this.siteStats = siteStats;
        this.polls = polls;
        this.notices = notices;
        this.owners = owners;
    }

    boolean isOwner(Guild guild, long userId) {
        return owners.isOwner(guild.getJDA(), userId);
    }

    DataObject overview(Guild guild, Member actor) {
        long guildId = guild.getIdLong();
        DataObject attention = ops.attention(guild);
        DataObject health = ops.health(guild);

        int total = attention.getInt("total", 0);
        int unverified = attention.getArray("unverified").length();
        DataObject members = DataObject.empty()
                .put("rosterSize", total).put("linked", total - unverified).put("unverified", unverified)
                .put("stale", attention.getArray("stale").length()).put("inactive", attention.getArray("inactive").length())
                .put("promotionsDue", attention.getArray("promotions").length())
                .put("discordMembers", guild.getMemberCount());

        DataObject result = DataObject.empty()
                .put("members", members)
                .put("community", DataObject.empty().put("openSignups", siteStats.activeSignups(guildId).size()).put("openPolls", polls.activePolls(guildId).size()))
                .put("health", healthSummary(health))
                .put("notices", noticeList())
                .put("isOwner", isOwner(guild, actor.getIdLong()));

        try {
            var stats = tickets.stats(guildId);
            // A server that has never used tickets gets no ticket card: nothing is open and nothing was ever opened.
            boolean used = stats.open() + stats.closed() > 0 || !stats.byPanel().isEmpty();
            if (used) result.put("tickets", DataObject.empty().put("open", stats.open()).put("escalated", stats.escalated()).put("flagged", stats.flagged()));
        } catch (Exception e) {
            log.warn("Couldn't read ticket stats for the overview of guild {}", guildId, e);
        }
        return result;
    }

    /** The few things worth a green tick or a red cross at a glance, with the plain-language reasons when it isn't fine. */
    private static DataObject healthSummary(DataObject health) {
        List<String> problems = new ArrayList<>();
        String discord = health.getObject("discord").getString("status", "UNKNOWN");
        if (!discord.equals("CONNECTED")) problems.add("The bot isn't fully connected to Discord (" + discord.toLowerCase() + ").");
        if (!health.getObject("database").getBoolean("ok", false)) problems.add("The bot's database isn't answering.");
        DataObject polling = health.getObject("polling");
        if (polling.getInt("rateLimitedQueue", 0) > 0) problems.add("RuneScape is rate limiting the bot, so some updates are running slower than usual.");

        DataArray list = DataArray.empty();
        problems.forEach(list::add);
        return DataObject.empty().put("ok", problems.isEmpty()).put("problems", list)
                .put("uptimeSeconds", health.getLong("uptimeSeconds", 0)).put("autoPoll", health.getObject("environment").getBoolean("autoPoll", false));
    }

    // ---------- notices ----------

    private DataArray noticeList() {
        DataArray array = DataArray.empty();
        for (Notice notice : notices.all()) {
            array.add(DataObject.empty().put("id", Long.toString(notice.id())).put("severity", notice.severity()).put("body", notice.body()).put("createdAt", notice.createdAt().toString()));
        }
        return array;
    }

    DataObject listNotices() {
        return DataObject.empty().put("notices", noticeList());
    }

    DataObject addNotice(Guild guild, Member actor, DataObject body) {
        requireOwner(guild, actor);
        String text = body.getString("body", "").trim();
        String severity = body.getString("severity", "info").trim().toLowerCase();
        if (text.isEmpty()) throw new ApiError(400, "Write the message first.");
        if (text.length() > Notice.MAX_BODY) throw new ApiError(400, "Keep it under " + Notice.MAX_BODY + " characters.");
        if (!Notice.SEVERITIES.contains(severity)) throw new ApiError(400, "Pick info, warning or issue.");
        if (notices.all().size() >= Notice.MAX_ACTIVE) throw new ApiError(400, "There are already " + Notice.MAX_ACTIVE + " notices up. Remove one first.");

        notices.add(severity, text, actor.getIdLong());
        return listNotices();
    }

    DataObject removeNotice(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id;
        try {
            id = Long.parseLong(rawId);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a notice.");
        }
        if (!notices.delete(id)) throw new ApiError(404, "That notice is already gone.");
        return listNotices();
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!isOwner(guild, actor.getIdLong())) throw new ApiError(403, "Only the bot's owner can post notices.");
    }
}
