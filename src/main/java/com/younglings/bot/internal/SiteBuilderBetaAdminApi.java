package com.younglings.bot.internal;

import com.younglings.bot.access.AccessArea;
import com.younglings.bot.access.AccessGrant;
import com.younglings.bot.access.AccessGrantRepository;
import com.younglings.bot.access.AccessGrantService;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * The site builder beta list (who may run a clan website for a server the bot isn't in, {@link BotlessAdminApi}), for the bot owner's console:
 * the Discord user grants of {@link AccessArea#SITE_BUILDER_BETA} on the access list, managed on their own so the console needs no area or
 * subject pickers. The bot's owner only. Reached through {@link TicketAdminApi} (the console's own server), which audit-logs every change.
 * <ul>
 *   <li>{@code GET site-builder-beta}: {@code {users: [{userId, note, grantedBy, grantedAt, expiresAt, expired}]}}</li>
 *   <li>{@code POST site-builder-beta} {@code {userId, note?}}: adds the person (once; adding them again keeps the first entry)</li>
 *   <li>{@code DELETE site-builder-beta/{userId}}: takes them off</li>
 * </ul>
 * The same entries show (and can be removed) on the full access list ({@code access-grants}) under "Site builder without the bot (beta)".
 */
@BService
public class SiteBuilderBetaAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SiteBuilderBetaAdminApi.class);
    static final String ROOT = "site-builder-beta";
    private static final int MAX_NOTE = 120;
    private static final int MAX_USERS = 500;

    private final AccessGrantRepository repository;
    private final AccessGrantService service;
    private final BotOwners owners;

    public SiteBuilderBetaAdminApi(AccessGrantRepository repository, AccessGrantService service, BotOwners owners) {
        this.repository = repository;
        this.service = service;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals(ROOT)) return null;
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_see"));
        if (parts.length == 1) {
            if (method.equals("POST")) return add(actor.getIdLong(), body.get());
            AdminRouteExtension.requireMethod(method, "GET");
            return list();
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return remove(userId(parts[1]));
        }
        return null;
    }

    private List<AccessGrant> entries() {
        return repository.all().stream().filter(g -> g.area() == AccessArea.SITE_BUILDER_BETA && g.isUser()).toList();
    }

    DataObject list() {
        Instant now = Instant.now();
        DataArray users = DataArray.empty();
        for (AccessGrant g : entries()) {
            users.add(DataObject.empty().put("userId", Long.toString(g.userId())).put("note", g.note()).put("grantedBy", Long.toString(g.grantedBy()))
                    .put("grantedAt", g.grantedAt().toString()).put("expiresAt", g.expiresAt() == null ? null : g.expiresAt().toString()).put("expired", g.expired(now)));
        }
        return DataObject.empty().put("area", AccessArea.SITE_BUILDER_BETA.key()).put("users", users);
    }

    DataObject add(long actorId, DataObject body) {
        long userId = userId(body.getString("userId", ""));
        String note = body.getString("note", "").strip();
        if (note.length() > MAX_NOTE) throw new ApiError(400, "Keep the note under " + MAX_NOTE + " characters.");
        List<AccessGrant> current = entries();
        boolean listed = current.stream().anyMatch(g -> g.userId() == userId && !g.expired(Instant.now()));
        if (!listed) {
            if (current.size() >= MAX_USERS) throw new ApiError(400, "The beta list is full. Remove someone first.");
            repository.add(AccessArea.SITE_BUILDER_BETA, userId, null, null, note, actorId, null);
            service.listChanged();
            log.info("Site builder beta: {} added user {}", actorId, userId);
        }
        return list();
    }

    DataObject remove(long userId) {
        boolean removed = false;
        for (AccessGrant g : entries()) if (g.userId() == userId) removed |= repository.delete(g.id());
        if (!removed) throw new ApiError(404, "That person isn't on the beta list.");
        service.listChanged();
        log.info("Site builder beta: user {} removed", userId);
        return list();
    }

    private static long userId(String raw) {
        String clean = raw == null ? "" : raw.strip();
        if (!clean.matches("\\d{15,20}")) throw new ApiError(400, "A Discord user ID is 17 to 20 digits.");
        try {
            return Long.parseLong(clean);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "A Discord user ID is 17 to 20 digits.");
        }
    }
}
