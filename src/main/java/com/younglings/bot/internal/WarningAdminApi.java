package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.warning.Autopunish;
import com.younglings.bot.warning.WarningRepository;
import com.younglings.bot.warning.WarningRepository.Warning;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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
        if (parts.length == 2 && parts[1].equals("autopunish")) {
            if (method.equals("GET")) return autopunish(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveAutopunish(guild, actor, body.get());
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

    DataObject autopunish(Guild guild) {
        DataArray rules = DataArray.empty();
        for (Autopunish.Rule r : repository.autopunishRules(guild.getIdLong())) {
            rules.add(DataObject.empty().put("threshold", r.threshold()).put("action", r.action().name()).put("timeoutMinutes", r.timeoutMinutes()));
        }
        Member self = guild.getSelfMember();
        return DataObject.empty().put("rules", rules).put("maxRules", Autopunish.MAX_RULES).put("maxThreshold", Autopunish.MAX_THRESHOLD)
                .put("maxTimeoutMinutes", Autopunish.MAX_TIMEOUT_MINUTES)
                .put("canTimeout", self.hasPermission(Permission.MODERATE_MEMBERS)).put("canKick", self.hasPermission(Permission.KICK_MEMBERS))
                .put("canBan", self.hasPermission(Permission.BAN_MEMBERS));
    }

    DataObject saveAutopunish(Guild guild, Member actor, DataObject body) {
        List<Autopunish.Rule> rules = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        DataArray raw = body.hasKey("rules") && !body.isNull("rules") ? body.getArray("rules") : DataArray.empty();
        for (int i = 0; i < raw.length(); i++) {
            DataObject item = raw.getObject(i);
            Autopunish.Action action;
            try {
                action = Autopunish.Action.valueOf(item.getString("action", "").strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Choose timeout, kick or ban for every rule.");
                continue;
            }
            rules.add(new Autopunish.Rule(item.getInt("threshold", 0), action, item.getInt("timeoutMinutes", 0)));
        }
        problems.addAll(Autopunish.problems(rules));
        Member self = guild.getSelfMember();
        for (Autopunish.Rule r : rules) {
            Permission needed = switch (r.action()) {
                case TIMEOUT -> Permission.MODERATE_MEMBERS;
                case KICK -> Permission.KICK_MEMBERS;
                case BAN -> Permission.BAN_MEMBERS;
            };
            if (!self.hasPermission(needed)) problems.add("The bot needs the " + needed.getName() + " permission to " + r.action().name().toLowerCase(java.util.Locale.ROOT) + " members.");
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_autopunish_rules_can_t_be"), problems.stream().distinct().toList());
        repository.saveAutopunishRules(guild.getIdLong(), rules.stream().sorted(java.util.Comparator.comparingInt(Autopunish.Rule::threshold)).toList());
        log.info("Dashboard: {} saved {} autopunish rule(s)", actor.getId(), rules.size());
        return autopunish(guild);
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_warning"));
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawId.strip()))) throw new ApiError(404, Text.of(guild, "dashboard.that_warning_doesn_t_exist"));
        log.info("Dashboard: {} removed warning {}", actor.getId(), rawId.strip());
        return list(guild);
    }
}
