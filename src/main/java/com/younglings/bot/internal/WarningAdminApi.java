package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.warning.WarningRepository;
import com.younglings.bot.warning.WarningRepository.Warning;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * A server's warnings for the website: the latest ones, and taking one back. Giving a warning is done with {@code /warn} so it can
 * check role hierarchy against the moderator. Reached only through {@link TicketAdminApi}, which has already checked the asker may use
 * the dashboard in this server and audit-logs every change.
 */
@BService
public class WarningAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(WarningAdminApi.class);
    private static final int LIMIT = 100;

    private final WarningRepository repository;

    public WarningAdminApi(WarningRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("warnings")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray warnings = DataArray.empty();
        for (Warning w : repository.recent(guild.getIdLong(), LIMIT)) {
            warnings.add(DataObject.empty().put("id", Long.toString(w.id())).put("userId", Long.toString(w.userId())).put("userName", name(guild, w.userId()))
                    .put("moderatorId", Long.toString(w.moderatorId())).put("moderatorName", name(guild, w.moderatorId()))
                    .put("reason", w.reason()).put("createdAt", w.createdAt().toString()));
        }
        return DataObject.empty().put("warnings", warnings).put("limit", LIMIT);
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, "That isn't a valid warning.");
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawId.strip()))) throw new ApiError(404, "That warning doesn't exist.");
        log.info("Dashboard: {} removed warning {}", actor.getId(), rawId.strip());
        return list(guild);
    }
}
