package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.premium.PremiumGuildRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Which servers are on the premium (faster) polling plan. Only the bot's owner may see or change the list, because it is a
 * privilege handed out, not a setting; anyone else just learns whether the server they are looking at is on it. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker and audit-logs every change.
 */
@BService
public class PremiumAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(PremiumAdminApi.class);
    private static final int MAX_LABEL = 60;

    private final PremiumGuildRepository repository;
    private final BotOwners owners;

    public PremiumAdminApi(PremiumGuildRepository repository, BotOwners owners) {
        this.repository = repository;
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
        return null;
    }

    /** Whether this server polls on the faster plan. */
    boolean isPremium(Guild guild) {
        return repository.isPremium(guild.getIdLong());
    }

    DataObject list(Guild guild, Member actor) {
        boolean owner = owners.isOwner(guild.getJDA(), actor.getIdLong());
        DataArray guilds = DataArray.empty();
        if (owner) {
            for (var premium : repository.all()) {
                Guild known = guild.getJDA().getGuildById(premium.guildId());
                guilds.add(DataObject.empty().put("guildId", Long.toString(premium.guildId())).put("label", premium.label())
                        .put("botPresent", known != null).put("name", known == null ? null : known.getName()));
            }
        }
        return DataObject.empty().put("guilds", guilds).put("canEdit", owner).put("thisServer", isPremium(guild));
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        String label = body.getString("label", "").strip();
        if (label.length() > MAX_LABEL) throw new ApiError(400, "Keep the label under " + MAX_LABEL + " characters.");

        repository.upsert(id, label, actor.getIdLong());
        log.info("Dashboard: {} put server {} ({}) on the premium polling plan", actor.getId(), id, label);
        return list(guild, actor);
    }

    DataObject remove(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        if (!repository.delete(id)) throw new ApiError(404, "That server isn't on the premium plan.");
        return list(guild, actor);
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{15,20}")) throw new ApiError(400, "A Discord server ID is 17 to 20 digits.");
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid server ID.");
        }
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, "Only the bot's owner can change the premium plan.");
    }
}
