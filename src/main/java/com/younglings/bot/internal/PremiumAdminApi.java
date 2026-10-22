package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.Premium;
import com.younglings.bot.premium.PremiumFeatures;
import com.younglings.bot.premium.PremiumGuildRepository;
import com.younglings.bot.premium.PremiumGuildRepository.Grant;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.function.Supplier;

/**
 * Which servers are on a plan above the free one, which plan, until when, and the history of every change. Only the bot's owner may see or change the
 * list, because it is a privilege handed out, not a setting; anyone else just learns about the server they are looking at (its plan, its end and what
 * it includes). Reached only through {@link TicketAdminApi}, which has already checked the asker and audit-logs every change. No payments or prices.
 * <ul>
 *     <li>{@code GET premium-guilds}: the list (owner), the servers ending soon, and this server's own plan</li>
 *     <li>{@code PUT premium-guilds/{id}} {@code {label, tier?, expiresAt?, note?, source?}}: grant or change (owner)</li>
 *     <li>{@code DELETE premium-guilds/{id}}: revoke (owner)</li>
 *     <li>{@code GET premium-guilds/{id}/history}: every grant, change, revoke and expiry of that server (owner)</li>
 * </ul>
 */
@BService
public class PremiumAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(PremiumAdminApi.class);
    private static final int MAX_LABEL = 60;
    private static final int MAX_NOTE = 300;
    private static final int ENDING_SOON_DAYS = 7;
    private static final int HISTORY_LIMIT = 100;

    private final PremiumGuildRepository repository;
    private final Premium premium;
    private final BotOwners owners;

    public PremiumAdminApi(PremiumGuildRepository repository, Premium premium, BotOwners owners) {
        this.repository = repository;
        this.premium = premium;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("premium-guilds")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild, actor);
        }
        if (parts.length == 2) {
            if (method.equals("PUT")) return save(guild, actor, parts[1], body.get());
            AdminRouteExtension.requireMethod(method, "DELETE");
            return remove(guild, actor, parts[1]);
        }
        if (parts.length == 3 && parts[2].equals("history")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return history(guild, actor, parts[1]);
        }
        return null;
    }

    /** Whether this server has a plan in force. */
    boolean isPremium(Guild guild) {
        return repository.isPremium(guild.getIdLong());
    }

    DataObject list(Guild guild, Member actor) {
        return list(guild, actor, Instant.now());
    }

    DataObject list(Guild guild, Member actor, Instant now) {
        boolean owner = owners.isOwner(guild.getJDA(), actor.getIdLong());
        DataArray guilds = DataArray.empty();
        DataArray endingSoon = DataArray.empty();
        if (owner) {
            for (Grant grant : repository.grants()) {
                Guild known = guild.getJDA().getGuildById(grant.guildId());
                DataObject json = DataObject.empty().put("guildId", Long.toString(grant.guildId())).put("label", grant.label())
                        .put("botPresent", known != null).put("name", known == null ? null : known.getName())
                        .put("tier", grant.tier()).put("expiresAt", grant.expiresAt() == null ? null : grant.expiresAt().toString())
                        .put("note", grant.note()).put("source", grant.source()).put("inForce", grant.inForce(now));
                guilds.add(json);
                if (PremiumFeatures.endingSoon(grant.expiresAt(), now, ENDING_SOON_DAYS)) endingSoon.add(json);
            }
        }
        Grant mine = repository.activeGrant(guild.getIdLong());
        DataArray features = DataArray.empty();
        if (mine != null) {
            for (PremiumFeatures.Feature f : PremiumFeatures.all()) {
                if (PremiumFeatures.allows(mine.tier(), f.key())) features.add(DataObject.empty().put("key", f.key()).put("title", f.title()).put("description", f.description()));
            }
        }
        return DataObject.empty().put("guilds", guilds).put("endingSoon", endingSoon).put("canEdit", owner).put("thisServer", mine != null)
                .put("thisServerPlan", mine == null ? null : DataObject.empty().put("tier", mine.tier())
                        .put("expiresAt", mine.expiresAt() == null ? null : mine.expiresAt().toString()).put("features", features));
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        String label = body.getString("label", "").strip();
        if (label.length() > MAX_LABEL) throw new ApiError(400, Text.of(guild, "dashboard.keep_the_label_under_characters", MAX_LABEL));

        String tier = PremiumFeatures.TIER_PREMIUM;
        if (body.hasKey("tier") && !body.isNull("tier") && !body.getString("tier", "").isBlank()) {
            tier = PremiumFeatures.cleanTier(body.getString("tier", ""));
            if (tier == null) throw new ApiError(400, Text.of(guild, "dashboard.a_plan_name_is_up_to"));
        }
        Instant expiresAt = parseEnd(body, Instant.now());
        String note = body.isNull("note") ? null : body.getString("note", "").strip();
        if (note != null && note.isEmpty()) note = null;
        if (note != null && note.length() > MAX_NOTE) throw new ApiError(400, Text.of(guild, "dashboard.keep_the_note_under_characters", MAX_NOTE));
        String source = body.isNull("source") || body.getString("source", "").isBlank() ? "manual" : PremiumFeatures.cleanTier(body.getString("source", ""));
        if (source == null) throw new ApiError(400, Text.of(guild, "dashboard.the_source_is_up_to_20"));

        boolean changed = premium.grant(id, label, tier, expiresAt, note, source, actor.getIdLong());
        log.info("Dashboard: {} {} server {} ({}) on plan '{}'{}", actor.getId(), changed ? "changed" : "put", id, label, tier, expiresAt == null ? "" : " until " + expiresAt);
        return list(guild, actor);
    }

    DataObject remove(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        if (!premium.revoke(id, actor.getIdLong())) throw new ApiError(404, Text.of(guild, "dashboard.that_server_isn_t_on_a"));
        return list(guild, actor);
    }

    DataObject history(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        DataArray entries = DataArray.empty();
        for (var e : repository.history(id, HISTORY_LIMIT)) {
            entries.add(DataObject.empty().put("action", e.action()).put("tier", e.tier()).put("expiresAt", e.expiresAt() == null ? null : e.expiresAt().toString())
                    .put("byUser", e.byUser() == null ? null : Long.toString(e.byUser())).put("note", e.note()).put("at", e.at() == null ? null : e.at().toString()));
        }
        return DataObject.empty().put("guildId", Long.toString(id)).put("history", entries);
    }

    /** The end of a plan from the request: a date (the plan runs through that day, UTC) or a full time; absent or empty means no end. Must be in the future. */
    static Instant parseEnd(DataObject body, Instant now) {
        if (!body.hasKey("expiresAt") || body.isNull("expiresAt")) return null;
        String raw = body.getString("expiresAt", "").strip();
        if (raw.isEmpty()) return null;
        Instant end;
        try {
            end = raw.length() <= 10 ? LocalDate.parse(raw).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) : Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new ApiError(400, Text.en("dashboard.the_end_date_isn_t_a"));
        }
        if (!end.isAfter(now)) throw new ApiError(400, Text.en("dashboard.the_end_date_has_to_be"));
        return end;
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{15,20}")) throw new ApiError(400, Text.en("dashboard.a_discord_server_id_is_17"));
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_server"));
        }
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_change_2"));
    }
}
