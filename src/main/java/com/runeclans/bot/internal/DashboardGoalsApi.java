package com.runeclans.bot.internal;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.permission.DashboardAccess;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * "Set up by goal" (T-607): what a server said its clan is about on the dashboard ({@code prefs/goals}), so the dashboard's sidebar can show the
 * matching features first and fold the rest behind "Show all features". One answer per server, not per admin: {@code GET} answers
 * {@code {goals: ["pvm", "events"], chosen: true, canChange: true}} ({@code chosen} false and an empty list when nobody picked yet) and {@code PUT
 * {goals: [...]}} replaces the list; an empty list clears it, which brings back the full sidebar.
 * <p>
 * Only an Admin may change it (a Developer reads it). It is a dashboard preference only: it never switches a bot feature on or off, so nothing a
 * server already uses changes. The website decides which tabs each goal shows (runeclans packages/dashboard/src/lib/goalsLogic.ts) and is
 * switched off by default there ({@code SETUP_BY_GOAL}); with that switch off nothing reads this route.
 */
@BService
public class DashboardGoalsApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(DashboardGoalsApi.class);
    /** The goals, in the order the dashboard shows them. */
    static final List<String> GOALS = List.of("pvm", "skilling", "social", "events");

    private final ConnectionSupplier connectionSupplier;
    private final DashboardAccess access;

    public DashboardGoalsApi(ConnectionSupplier connectionSupplier, DashboardAccess access) {
        this.connectionSupplier = connectionSupplier;
        this.access = access;
        SchemaBootstrapper.run(connectionSupplier, log, "dashboard goals", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.dashboard_goals (
                    guild_id BIGINT PRIMARY KEY,
                    goals TEXT NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """));
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 2 || !parts[0].equals("prefs") || !parts[1].equals("goals")) return null;
        long guildId = guild.getIdLong();
        boolean admin = access.tierOf(guild, actor) == DashboardAccess.Tier.ADMIN;
        if (method.equals("GET")) return answer(goals(guildId), admin);
        AdminRouteExtension.requireMethod(method, "PUT");
        if (!admin) throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_change_the_goals"));
        DataObject b = body.get();
        if (!b.hasKey("goals") || b.isNull("goals")) throw new ApiError(400, Text.of(guild, "dashboard.send_the_list_of_goals"));
        List<String> goals = clean(b.getArray("goals"));
        save(guildId, goals);
        return answer(goals, true);
    }

    /** The goals in the list, each once, in the dashboard's order; anything that isn't one of {@link #GOALS} is refused. */
    static List<String> clean(DataArray array) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String goal;
            try {
                goal = array.getString(i).strip().toLowerCase();
            } catch (RuntimeException e) {
                throw new ApiError(400, Text.en("dashboard.isn_t_a_goal", "?"));
            }
            if (!GOALS.contains(goal)) throw new ApiError(400, Text.en("dashboard.isn_t_a_goal", goal));
            seen.add(goal);
        }
        return GOALS.stream().filter(seen::contains).toList();
    }

    private static DataObject answer(List<String> goals, boolean canChange) {
        DataArray array = DataArray.empty();
        goals.forEach(array::add);
        return DataObject.empty().put("goals", array).put("chosen", !goals.isEmpty()).put("canChange", canChange);
    }

    /** This server's goals in the dashboard's order; empty when nobody picked any. */
    List<String> goals(long guildId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT goals FROM younglings.dashboard_goals WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return List.of();
                Set<String> stored = new LinkedHashSet<>(Arrays.asList(rs.getString(1).split(",")));
                // a goal a later version dropped is simply left out
                return new ArrayList<>(GOALS.stream().filter(stored::contains).toList());
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the dashboard goals", e);
        }
    }

    void save(long guildId, List<String> goals) {
        String sql = goals.isEmpty()
                ? "DELETE FROM younglings.dashboard_goals WHERE guild_id = ?"
                : "INSERT INTO younglings.dashboard_goals (guild_id, goals, updated_at) VALUES (?, ?, now()) "
                + "ON CONFLICT (guild_id) DO UPDATE SET goals = EXCLUDED.goals, updated_at = now()";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            if (!goals.isEmpty()) s.setString(2, String.join(",", goals));
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the dashboard goals", e);
        }
    }
}
