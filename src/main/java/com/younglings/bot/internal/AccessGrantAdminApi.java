package com.younglings.bot.internal;

import com.younglings.bot.access.AccessArea;
import com.younglings.bot.access.AccessGrant;
import com.younglings.bot.access.AccessGrantRepository;
import com.younglings.bot.access.AccessGrantService;
import com.younglings.bot.access.AccessLevel;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * The restricted-content access list, managed from the website's Super admin area: who may enter each restricted area (JonnyBot website, hub,
 * dashboard) without being a beta server admin. A grant is a Discord user, or a server plus the least a person must be there. Reading and changing
 * the list is for the bot's owner only. Reached only through {@link TicketAdminApi}, which has already checked the asker and audit-logs every change.
 */
@BService
public class AccessGrantAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AccessGrantAdminApi.class);
    private static final int MAX_NOTE = 120;
    private static final int MAX_GRANTS = 500;

    private final AccessGrantRepository repository;
    private final AccessGrantService service;
    private final BotOwners owners;

    public AccessGrantAdminApi(AccessGrantRepository repository, AccessGrantService service, BotOwners owners) {
        this.repository = repository;
        this.service = service;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("access-grants")) return null;
        requireOwner(guild, actor);
        if (parts.length == 1) {
            if (method.equals("POST")) return add(guild, actor, body.get());
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return remove(guild, parts[1]);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray grants = DataArray.empty();
        Instant now = Instant.now();
        for (AccessGrant g : repository.all()) {
            DataObject json = DataObject.empty().put("id", Long.toString(g.id())).put("area", g.area().key()).put("note", g.note())
                    .put("grantedBy", Long.toString(g.grantedBy())).put("grantedAt", g.grantedAt().toString())
                    .put("expiresAt", g.expiresAt() == null ? null : g.expiresAt().toString()).put("expired", g.expired(now));
            if (g.isUser()) {
                json.put("subject", "user").put("userId", Long.toString(g.userId()));
            } else {
                Guild known = guild.getJDA().getGuildById(g.guildId());
                json.put("subject", "server").put("guildId", Long.toString(g.guildId())).put("level", g.level().key()).put("levelLabel", g.level().label())
                        .put("botPresent", known != null).put("serverName", known == null ? null : known.getName());
            }
            grants.add(json);
        }
        DataArray areas = DataArray.empty();
        for (AccessArea a : AccessArea.values()) areas.add(DataObject.empty().put("key", a.key()).put("label", a.label()));
        DataArray levels = DataArray.empty();
        for (AccessLevel l : AccessLevel.values()) levels.add(DataObject.empty().put("key", l.key()).put("label", l.label()));
        return DataObject.empty().put("grants", grants).put("areas", areas).put("levels", levels);
    }

    DataObject add(Guild guild, Member actor, DataObject body) {
        AccessArea area = AccessArea.ofKey(body.getString("area", "")).orElseThrow(() -> new ApiError(400, "Choose an area."));
        String note = body.getString("note", "").strip();
        if (note.length() > MAX_NOTE) throw new ApiError(400, "Keep the note under " + MAX_NOTE + " characters.");
        Instant expires = parseExpiry(body.getString("expiresOn", ""));
        if (repository.all().size() >= MAX_GRANTS) throw new ApiError(400, "The list is full. Remove some entries first.");

        String subject = body.getString("subject", "");
        long id;
        if (subject.equals("user")) {
            long userId = parseId(body.getString("userId", ""), "A Discord user ID is 17 to 20 digits.");
            id = repository.add(area, userId, null, null, note, actor.getIdLong(), expires);
            log.info("Dashboard: {} let user {} into {}", actor.getId(), userId, area.key());
        } else if (subject.equals("server")) {
            long guildId = parseId(body.getString("guildId", ""), "A Discord server ID is 17 to 20 digits.");
            AccessLevel level = AccessLevel.ofKey(body.getString("level", "")).orElseThrow(() -> new ApiError(400, "Choose the least a person must be in that server."));
            id = repository.add(area, null, guildId, level, note, actor.getIdLong(), expires);
            log.info("Dashboard: {} let server {} ({}) into {}", actor.getId(), guildId, level.key(), area.key());
        } else {
            throw new ApiError(400, "Choose whether this is a person or a server.");
        }
        service.listChanged();
        log.debug("Access grant {} added", id);
        return list(guild);
    }

    DataObject remove(Guild guild, String rawId) {
        long id;
        try {
            id = Long.parseLong(rawId.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That is not an entry.");
        }
        if (!repository.delete(id)) throw new ApiError(404, "That entry is not on the list.");
        service.listChanged();
        return list(guild);
    }

    /** An optional last day (yyyy-MM-dd, end of that day UTC), in the future. */
    private static Instant parseExpiry(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Instant end = LocalDate.parse(raw.strip()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            if (!end.isAfter(Instant.now())) throw new ApiError(400, "The last day must not be in the past.");
            return end;
        } catch (DateTimeException e) {
            throw new ApiError(400, "Give the last day as year-month-day.");
        }
    }

    private static long parseId(String raw, String message) {
        if (raw == null || !raw.strip().matches("\\d{15,20}")) throw new ApiError(400, message);
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, message);
        }
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, "Only the bot's owner can see or change the access list.");
    }
}
