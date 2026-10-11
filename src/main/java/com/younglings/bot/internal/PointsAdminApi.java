package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.points.PointsRepository;
import com.younglings.bot.points.PointsRepository.Balance;
import com.younglings.bot.points.PointsRepository.Entry;
import com.younglings.bot.points.PointsRepository.Settings;
import com.younglings.bot.points.PointsRules;
import com.younglings.bot.points.PointsRules.Category;
import com.younglings.bot.points.PointsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * A server's points for the website: the settings, the leaderboard, the latest ledger entries, and awarding. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every change.
 */
@BService
public class PointsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(PointsAdminApi.class);
    private static final int TOP = 25;
    private static final int RECENT = 50;

    private final PointsRepository repository;
    private final PointsService service;

    public PointsAdminApi(PointsRepository repository, PointsService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("points")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return overview(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("award")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return award(guild, actor, body.get());
        }
        return null;
    }

    DataObject overview(Guild guild) {
        long guildId = guild.getIdLong();
        Settings settings = repository.settings(guildId);
        DataArray top = DataArray.empty();
        for (Balance b : repository.top(guildId, TOP)) {
            top.add(DataObject.empty().put("userId", Long.toString(b.userId())).put("userName", name(guild, b.userId())).put("points", b.points()));
        }
        DataArray recent = DataArray.empty();
        for (Entry e : repository.recent(guildId, RECENT)) {
            recent.add(DataObject.empty().put("id", Long.toString(e.id())).put("userId", Long.toString(e.userId())).put("userName", name(guild, e.userId()))
                    .put("points", e.points()).put("reason", e.reason()).put("category", e.category().name())
                    .put("awardedBy", e.awardedBy() == null ? null : Long.toString(e.awardedBy())).put("awardedByName", e.awardedBy() == null ? null : name(guild, e.awardedBy()))
                    .put("createdAt", e.createdAt().toString()));
        }
        PointsRepository.Totals totals = repository.totals(guildId);
        return DataObject.empty().put("enabled", settings.enabled()).put("unit", settings.unit())
                .put("top", top).put("recent", recent)
                .put("totals", DataObject.empty().put("members", totals.members()).put("outstanding", totals.outstanding()).put("awarded", totals.awarded()).put("spent", totals.spent()))
                .put("maxPerEntry", PointsRules.MAX_PER_ENTRY);
    }

    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        String unit = body.getString("unit", "points").strip();
        var problems = PointsRules.settingsProblems(unit);
        if (!problems.isEmpty()) throw new ApiError(400, "The points settings can't be saved yet.", problems);
        repository.saveSettings(new Settings(guild.getIdLong(), body.getBoolean("enabled", false), unit));
        log.info("Dashboard: {} saved the points settings", actor.getId());
        return overview(guild);
    }

    DataObject award(Guild guild, Member actor, DataObject body) {
        String rawId = body.getString("userId", "").strip();
        if (!rawId.matches("\\d{1,20}")) throw new ApiError(400, "Pick a member.");
        long userId = Long.parseLong(rawId);
        Member target = guild.getMemberById(userId);
        if (target == null) throw new ApiError(400, "That member isn't in this server.");
        if (target.getUser().isBot()) throw new ApiError(400, "Bots can't hold points.");
        long points;
        try {
            points = body.getLong("points", 0);
        } catch (RuntimeException e) {
            throw new ApiError(400, "The amount must be a whole number.");
        }
        var result = service.apply(guild.getIdLong(), userId, points, body.getString("reason", ""), Category.MANUAL, null, actor.getIdLong());
        if (result.outcome() != PointsService.Outcome.DONE) throw new ApiError(400, result.message());
        log.info("Dashboard: {} gave {} point(s) to {}", actor.getId(), points, userId);
        return overview(guild);
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }
}
