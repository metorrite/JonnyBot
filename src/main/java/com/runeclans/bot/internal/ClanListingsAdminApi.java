package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ProfileRules;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Moderation of the public directory, for the bot's owner only (it is a privilege over other servers, not a setting): every shared
 * listing with its open reports, suspending or restoring a listing, and closing a report. Anyone else asking just gets
 * {@code canEdit: false} and nothing else. Reached only through {@link TicketAdminApi}, which has already checked the asker and
 * audit-logs every change.
 * <ul>
 *   <li>{@code GET clan-listings}</li>
 *   <li>{@code POST clan-listings/{guildId}/suspend} {@code {reason}} and {@code POST clan-listings/{guildId}/restore}</li>
 *   <li>{@code POST clan-reports/{id}/resolve} {@code {status: DISMISSED|ACTIONED}}</li>
 * </ul>
 */
@BService
public class ClanListingsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ClanListingsAdminApi.class);
    private static final int MAX_REASON = 300;

    private final ClanProfileRepository repository;
    private final ClanProfileService service;
    private final BotOwners owners;

    public ClanListingsAdminApi(ClanProfileRepository repository, ClanProfileService service, BotOwners owners) {
        this.repository = repository;
        this.service = service;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length >= 1 && parts[0].equals("clan-listings")) {
            if (parts.length == 1) {
                AdminRouteExtension.requireMethod(method, "GET");
                return list(guild, actor);
            }
            if (parts.length == 3) {
                AdminRouteExtension.requireMethod(method, "POST");
                if (parts[2].equals("suspend")) return suspend(guild, actor, parts[1], body.get());
                if (parts[2].equals("restore")) return restore(guild, actor, parts[1]);
            }
            return null;
        }
        if (parts.length == 3 && parts[0].equals("clan-reports") && parts[2].equals("resolve")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return resolve(guild, actor, parts[1], body.get());
        }
        return null;
    }

    DataObject list(Guild guild, Member actor) {
        boolean owner = owners.isOwner(guild.getJDA(), actor.getIdLong());
        DataArray listings = DataArray.empty();
        DataArray reports = DataArray.empty();
        if (owner) {
            var open = repository.openReports();
            Map<Long, Integer> reportCount = new HashMap<>();
            for (var r : open) reportCount.merge(r.guildId(), 1, Integer::sum);
            for (ClanProfile p : repository.all()) {
                if (p.visibility() == ClanProfile.Visibility.PRIVATE && !p.suspended() && !reportCount.containsKey(p.guildId())) continue;
                Guild known = Shards.guild(guild.getJDA(), p.guildId());
                String clan = service.clanNameOf(p.guildId());
                listings.add(DataObject.empty().put("guildId", Long.toString(p.guildId())).put("serverName", known == null ? null : known.getName())
                        .put("slug", p.slug()).put("displayName", p.displayName()).put("visibility", p.visibility().name())
                        .put("listing", ProfileRules.listing(p, clan).name()).put("verified", ProfileRules.verified(p, clan))
                        .put("suspended", p.suspended()).put("suspendedReason", p.suspendedReason()).put("openReports", reportCount.getOrDefault(p.guildId(), 0)));
            }
            for (var r : open) {
                ClanProfile p = repository.get(r.guildId()).orElse(null);
                reports.add(DataObject.empty().put("id", Long.toString(r.id())).put("guildId", Long.toString(r.guildId()))
                        .put("slug", p == null ? null : p.slug()).put("displayName", p == null ? null : p.displayName()).put("reason", r.reason())
                        .put("reporter", r.reporter()).put("createdAt", r.createdAt().toString()));
            }
        }
        return DataObject.empty().put("canEdit", owner).put("listings", listings).put("reports", reports);
    }

    DataObject suspend(Guild guild, Member actor, String rawGuildId, DataObject body) {
        requireOwner(guild, actor);
        long target = parseId(rawGuildId);
        String reason = body.getString("reason", "").strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON) throw new ApiError(400, Text.of(guild, "dashboard.give_a_reason_in_at_most", MAX_REASON));
        if (repository.get(target).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.that_server_has_no_clan_profile"));
        repository.setSuspended(target, reason);
        log.info("Dashboard: {} suspended the clan listing of guild {} ({})", actor.getId(), target, reason);
        return list(guild, actor);
    }

    DataObject restore(Guild guild, Member actor, String rawGuildId) {
        requireOwner(guild, actor);
        long target = parseId(rawGuildId);
        if (repository.get(target).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.that_server_has_no_clan_profile"));
        repository.setSuspended(target, null);
        log.info("Dashboard: {} restored the clan listing of guild {}", actor.getId(), target);
        return list(guild, actor);
    }

    DataObject resolve(Guild guild, Member actor, String rawId, DataObject body) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        String status = body.getString("status", "").strip().toUpperCase(Locale.ROOT);
        if (!status.equals("DISMISSED") && !status.equals("ACTIONED")) throw new ApiError(400, Text.of(guild, "dashboard.close_a_report_as_dismissed_or"));
        if (!repository.resolveReport(id, status, actor.getIdLong())) throw new ApiError(404, Text.of(guild, "dashboard.that_report_isn_t_open_any"));
        log.info("Dashboard: {} closed clan report {} as {}", actor.getId(), id, status);
        return list(guild, actor);
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_moderate"));
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_id"));
        }
    }
}
