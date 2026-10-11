package com.younglings.bot.internal;

import com.younglings.bot.beta.BetaAccessService;
import com.younglings.bot.beta.BetaGuildRepository;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The list of trusted servers whose admin team may use JonnyBot's website while it is closed to the public. Anyone who may open
 * the clan's dashboard can read it; changing it is for the bot's owner, because it decides who gets in. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker and audit-logs every change.
 */
@BService
public class BetaAdminApi {
    private static final Logger log = LoggerFactory.getLogger(BetaAdminApi.class);
    private static final int MAX_LABEL = 60;

    private final BetaGuildRepository repository;
    private final BetaAccessService access;
    private final BotOwners owners;

    public BetaAdminApi(BetaGuildRepository repository, BetaAccessService access, BotOwners owners) {
        this.repository = repository;
        this.access = access;
        this.owners = owners;
    }

    DataObject list(Guild guild, Member actor) {
        DataArray guilds = DataArray.empty();
        for (var beta : repository.all()) {
            Guild known = guild.getJDA().getGuildById(beta.guildId());
            guilds.add(DataObject.empty().put("guildId", Long.toString(beta.guildId())).put("label", beta.label())
                    .put("botPresent", known != null).put("name", known == null ? null : known.getName()));
        }
        return DataObject.empty().put("guilds", guilds).put("canEdit", owners.isOwner(guild.getJDA(), actor.getIdLong()));
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        String label = body.getString("label", "").strip();
        if (label.length() > MAX_LABEL) throw new ApiError(400, "Keep the label under " + MAX_LABEL + " characters.");

        repository.upsert(id, label, actor.getIdLong());
        access.listChanged();
        log.info("Dashboard: {} saved beta server {} ({})", actor.getId(), id, label);
        return list(guild, actor);
    }

    DataObject remove(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        long id = parseId(rawId);
        if (!repository.delete(id)) throw new ApiError(404, "That server isn't on the list.");
        access.listChanged();
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
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, "Only the bot's owner can change the beta list.");
    }
}
