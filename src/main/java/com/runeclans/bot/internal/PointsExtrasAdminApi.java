package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.points.PointsExtrasRepository;
import com.runeclans.bot.points.PointsExtrasRules;
import com.runeclans.bot.points.PointsExtrasRules.RoleReward;
import com.runeclans.bot.points.PointsExtrasRules.Trigger;
import com.runeclans.bot.rolepanel.RolePanelService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The role rewards and keyword triggers of a server's points, for the website. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET pointsroles}, {@code PUT pointsroles} {@code {id?, threshold, roleId, removeLower}}, {@code DELETE pointsroles/{id}}</li>
 *   <li>{@code GET pointskeywords}, {@code PUT pointskeywords} {@code {id?, keyword, points, cooldownSeconds, dailyCap, enabled}}, {@code DELETE pointskeywords/{id}}</li>
 * </ul>
 */
@BService
public class PointsExtrasAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(PointsExtrasAdminApi.class);

    private final PointsExtrasRepository repository;

    public PointsExtrasAdminApi(PointsExtrasRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || parts.length > 2) return null;
        boolean roles = parts[0].equals("pointsroles");
        if (!roles && !parts[0].equals("pointskeywords")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return roles ? roleList(guild) : triggerList(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return roles ? saveRole(guild, actor, body.get()) : saveTrigger(guild, actor, body.get());
        }
        AdminRouteExtension.requireMethod(method, "DELETE");
        long id = parseId(parts[1]);
        if (roles) {
            if (!repository.deleteRoleReward(guild.getIdLong(), id)) throw new ApiError(404, Text.of(guild, "dashboard.that_role_reward_no_longer_exists"));
            log.info("Dashboard: {} removed points role reward {}", actor.getId(), id);
            return roleList(guild);
        }
        if (!repository.deleteTrigger(guild.getIdLong(), id)) throw new ApiError(404, Text.of(guild, "dashboard.that_trigger_no_longer_exists"));
        log.info("Dashboard: {} removed points keyword trigger {}", actor.getId(), id);
        return triggerList(guild);
    }

    // ---------- role rewards ----------

    DataObject roleList(Guild guild) {
        DataArray out = DataArray.empty();
        for (RoleReward r : repository.roleRewards(guild.getIdLong())) {
            Role role = guild.getRoleById(r.roleId());
            String problem = role == null ? "This role no longer exists." : RolePanelService.canGive(guild, role) ? null
                    : "The bot can't hand out @" + role.getName() + " (it sits above the bot's role, is managed, or has powerful permissions).";
            out.add(DataObject.empty().put("id", Long.toString(r.id())).put("threshold", r.threshold()).put("roleId", Long.toString(r.roleId()))
                    .put("roleName", role == null ? null : role.getName()).put("removeLower", r.removeLower()).put("problem", problem));
        }
        return DataObject.empty().put("rewards", out).put("max", PointsExtrasRules.MAX_ROLE_REWARDS).put("maxThreshold", PointsExtrasRules.MAX_THRESHOLD);
    }

    DataObject saveRole(Guild guild, Member actor, DataObject body) {
        long guildId = guild.getIdLong();
        long id = optionalId(body);
        List<RoleReward> others = new ArrayList<>(repository.roleRewards(guildId));
        if (id != 0) {
            if (others.stream().noneMatch(r -> r.id() == id)) throw new ApiError(404, Text.of(guild, "dashboard.that_role_reward_no_longer_exists"));
            others.removeIf(r -> r.id() == id);
        }
        long threshold = body.getLong("threshold", 0);
        String rawRole = body.isNull("roleId") ? "" : body.getString("roleId", "").strip();
        List<String> problems = new ArrayList<>();
        Role role = rawRole.matches("\\d{1,20}") ? guild.getRoleById(Long.parseLong(rawRole)) : null;
        if (role == null) problems.add("Choose a role.");
        else if (!RolePanelService.canGive(guild, role)) problems.add("@" + role.getName() + " can't be handed out (it is managed by an integration, sits above the bot's role, or has powerful permissions).");
        problems.addAll(PointsExtrasRules.roleRewardProblems(threshold, role == null ? -1 : role.getIdLong(), others));
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_role_reward_can_t_be"), problems);
        boolean saved = repository.saveRoleReward(new RoleReward(id, guildId, threshold, role.getIdLong(), body.getBoolean("removeLower", false)));
        if (!saved) throw new ApiError(400, Text.of(guild, "dashboard.that_role_reward_can_t_be"), List.of("Another role reward already starts at that number of points."));
        log.info("Dashboard: {} saved a points role reward: {} points gives @{}", actor.getId(), threshold, role.getName());
        return roleList(guild);
    }

    // ---------- keyword triggers ----------

    DataObject triggerList(Guild guild) {
        DataArray out = DataArray.empty();
        for (Trigger t : repository.triggers(guild.getIdLong())) {
            out.add(DataObject.empty().put("id", Long.toString(t.id())).put("keyword", t.keyword()).put("points", t.points()).put("cooldownSeconds", t.cooldownSeconds())
                    .put("dailyCap", t.dailyCap()).put("enabled", t.enabled()));
        }
        return DataObject.empty().put("triggers", out).put("max", PointsExtrasRules.MAX_TRIGGERS).put("maxPoints", PointsExtrasRules.MAX_TRIGGER_POINTS)
                .put("maxCooldownSeconds", PointsExtrasRules.MAX_COOLDOWN_SECONDS).put("maxDailyCap", PointsExtrasRules.MAX_DAILY_CAP).put("maxReceivers", PointsExtrasRules.MAX_RECEIVERS);
    }

    DataObject saveTrigger(Guild guild, Member actor, DataObject body) {
        long guildId = guild.getIdLong();
        long id = optionalId(body);
        List<Trigger> others = new ArrayList<>(repository.triggers(guildId));
        if (id != 0) {
            if (others.stream().noneMatch(t -> t.id() == id)) throw new ApiError(404, Text.of(guild, "dashboard.that_trigger_no_longer_exists"));
            others.removeIf(t -> t.id() == id);
        }
        String keyword = PointsExtrasRules.cleanKeyword(body.isNull("keyword") ? "" : body.getString("keyword", ""));
        int points = body.getInt("points", 0);
        int cooldown = body.getInt("cooldownSeconds", -1);
        int cap = body.getInt("dailyCap", 0);
        List<String> problems = PointsExtrasRules.triggerProblems(keyword, points, cooldown, cap, others);
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_trigger_can_t_be_saved"), problems);
        boolean saved = repository.saveTrigger(new Trigger(id, guildId, keyword, points, cooldown, cap, body.getBoolean("enabled", true)));
        if (!saved) throw new ApiError(400, Text.of(guild, "dashboard.that_trigger_can_t_be_saved"), List.of("There is already a trigger for that word or phrase."));
        log.info("Dashboard: {} saved the points trigger \"{}\"", actor.getId(), keyword);
        return triggerList(guild);
    }

    private static long optionalId(DataObject body) {
        String raw = body.isNull("id") || !body.hasKey("id") ? "" : body.getString("id", "").strip();
        return raw.isEmpty() ? 0 : parseId(raw);
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_item"));
        return Long.parseLong(raw.strip());
    }
}
