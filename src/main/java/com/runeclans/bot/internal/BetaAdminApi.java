package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.access.AccessLevel;
import com.runeclans.bot.beta.BetaAccessService;
import com.runeclans.bot.beta.BetaGuildRepository;
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

import java.util.List;
import java.util.function.Supplier;

/**
 * The list of trusted servers whose admin team may use JonnyBot's website while it is closed to the public. Reading and changing it is
 * for the bot's owner, because it decides who gets in. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker and audit-logs every change.
 * <p>
 * Each server has its own level, the least a person must be there (T-345): Server owner, Administrator or Manage Server. A new server starts at
 * Administrator; servers added before levels existed keep Manage Server, the rule they always had.
 */
@BService
public class BetaAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(BetaAdminApi.class);
    private static final int MAX_LABEL = BetaGuildRepository.MAX_LABEL;
    /** The levels a beta server may have, most restrictive first. "Any member" is left out: that is what a per-area access grant is for. */
    static final List<AccessLevel> LEVELS = List.of(AccessLevel.OWNER, AccessLevel.ADMINISTRATOR, AccessLevel.MANAGE_SERVER);
    static final AccessLevel DEFAULT_LEVEL = AccessLevel.ADMINISTRATOR;

    private final BetaGuildRepository repository;
    private final BetaAccessService access;
    private final BotOwners owners;

    public BetaAdminApi(BetaGuildRepository repository, BetaAccessService access, BotOwners owners) {
        this.repository = repository;
        this.access = access;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("beta-guilds")) return null;
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

    DataObject list(Guild guild, Member actor) {
        boolean owner = owners.isOwner(guild.getJDA(), actor.getIdLong());
        DataArray guilds = DataArray.empty();
        // The list names other servers, so only the owner sees it (like the premium list); anyone else gets it empty.
        if (owner) {
            for (var beta : repository.all()) {
                Guild known = Shards.guild(guild.getJDA(), beta.guildId());
                guilds.add(DataObject.empty().put("guildId", Long.toString(beta.guildId())).put("label", beta.label())
                        .put("botPresent", known != null).put("name", known == null ? null : known.getName())
                        .put("iconUrl", known == null ? null : known.getIconUrl())
                        .put("level", beta.level() == null ? null : beta.level().key())
                        .put("levelLabel", beta.level() == null ? null : beta.level().label()));
            }
        }
        DataArray levels = DataArray.empty();
        for (AccessLevel level : LEVELS) levels.add(DataObject.empty().put("key", level.key()).put("label", level.label()));
        return DataObject.empty().put("guilds", guilds).put("canEdit", owner).put("levels", levels).put("defaultLevel", DEFAULT_LEVEL.key());
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        requireOwner(guild, actor);
        HubAccessEdits.refuseWhenInHub(access.editedInHub());
        long id = parseId(rawId);
        String label = body.getString("label", "").strip();
        if (label.length() > MAX_LABEL) throw new ApiError(400, Text.of(guild, "dashboard.keep_the_label_under_characters", MAX_LABEL));
        AccessLevel level = levelFor(guild, id, body.getString("level", "").strip());

        repository.upsert(id, label, level, actor.getIdLong());
        access.listChanged();
        log.info("Dashboard: {} saved beta server {} ({}, {})", actor.getId(), id, label, level.key());
        return list(guild, actor);
    }

    /** The level asked for; with none, the server's current one (a label change keeps it), or the default for a server new to the list. */
    private AccessLevel levelFor(Guild guild, long id, String raw) {
        if (!raw.isEmpty()) {
            return AccessLevel.ofKey(raw).filter(LEVELS::contains)
                    .orElseThrow(() -> new ApiError(400, Text.of(guild, "dashboard.choose_the_level_for_a_beta")));
        }
        return repository.all().stream().filter(b -> b.guildId() == id && b.level() != null).map(BetaGuildRepository.BetaGuild::level)
                .findFirst().orElse(DEFAULT_LEVEL);
    }

    DataObject remove(Guild guild, Member actor, String rawId) {
        requireOwner(guild, actor);
        HubAccessEdits.refuseWhenInHub(access.editedInHub());
        long id = parseId(rawId);
        if (!repository.delete(id)) throw new ApiError(404, Text.of(guild, "dashboard.that_server_isn_t_on_the"));
        access.listChanged();
        return list(guild, actor);
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
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_change"));
    }
}
