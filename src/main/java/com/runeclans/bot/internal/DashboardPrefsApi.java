package com.runeclans.bot.internal;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * What each dashboard admin has set for themselves in one server, kept with the bot so it follows them from one computer to the next. For now: the starred
 * pages of the sidebar ({@code prefs/favorites}) and the Basic/Advanced view ({@code prefs/view}, {@code {mode: "basic"|"advanced"|null}}; null means never chosen). {@code GET} answers {@code {paths: ["/settings", ...]}} in the order they were starred, and {@code PUT}
 * with the same body replaces the list. A page is a dashboard path like {@code /event-manager} or a tab of a section like {@code /roles/self-roles}; the website knows which exist, the bot only checks the shape
 * and keeps at most {@value #MAX_FAVORITES}. Everyone sees and changes only their own: the person is the one the request was made for.
 */
@BService
public class DashboardPrefsApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(DashboardPrefsApi.class);
    public static final int MAX_FAVORITES = 20;
    static final Set<String> VIEW_MODES = Set.of("basic", "advanced");
    private static final String SHAPE = "/[a-z][a-z0-9-]{0,39}(/[a-z][a-z0-9-]{0,39})?";

    private final ConnectionSupplier connectionSupplier;

    public DashboardPrefsApi(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "dashboard favorites", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.dashboard_favorite (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    path TEXT NOT NULL,
                    position INT NOT NULL,
                    PRIMARY KEY (guild_id, user_id, path)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.dashboard_view (
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    mode TEXT NOT NULL,
                    PRIMARY KEY (guild_id, user_id)
                );
                """));
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 2 || !parts[0].equals("prefs")) return null;
        long guildId = guild.getIdLong();
        long userId = actor.getIdLong();
        if (parts[1].equals("view")) {
            if (method.equals("GET")) return viewAnswer(view(guildId, userId));
            AdminRouteExtension.requireMethod(method, "PUT");
            String mode = body.get().getString("mode", "");
            if (!VIEW_MODES.contains(mode)) throw new ApiError(400, Text.of(guild, "dashboard.the_view_is_basic_or_advanced"));
            saveView(guildId, userId, mode);
            return viewAnswer(mode);
        }
        if (!parts[1].equals("favorites")) return null;
        if (method.equals("GET")) return answer(favorites(guildId, userId));
        AdminRouteExtension.requireMethod(method, "PUT");
        DataObject b = body.get();
        if (!b.hasKey("paths") || b.isNull("paths")) throw new ApiError(400, Text.of(guild, "dashboard.send_the_list_of_pages"));
        List<String> paths = clean(b.getArray("paths"));
        save(guildId, userId, paths);
        return answer(paths);
    }

    /** The pages in the list, each once, in order, only those that look like a dashboard page; too many or something that isn't a page is refused. */
    static List<String> clean(DataArray array) {
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String path;
            try {
                path = array.getString(i).strip();
            } catch (RuntimeException e) {
                throw new ApiError(400, Text.en("dashboard.every_page_has_to_be_a"));
            }
            if (!path.matches(SHAPE)) throw new ApiError(400, Text.en("dashboard.isn_t_a_dashboard_page", path));
            seen.add(path);
        }
        if (seen.size() > MAX_FAVORITES) throw new ApiError(400, Text.en("dashboard.you_can_star_up_to_pages", MAX_FAVORITES));
        return new ArrayList<>(seen);
    }

    private static DataObject viewAnswer(String mode) {
        return DataObject.empty().put("mode", mode);
    }

    /** The view this admin chose in this server, or null when they never have (the browser then decides). */
    String view(long guildId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT mode FROM younglings.dashboard_view WHERE guild_id = ? AND user_id = ?")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the dashboard view", e);
        }
    }

    void saveView(long guildId, long userId, String mode) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.dashboard_view (guild_id, user_id, mode) VALUES (?, ?, ?) ON CONFLICT (guild_id, user_id) DO UPDATE SET mode = EXCLUDED.mode")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setString(3, mode);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the dashboard view", e);
        }
    }

    private static DataObject answer(List<String> paths) {
        DataArray array = DataArray.empty();
        paths.forEach(array::add);
        return DataObject.empty().put("paths", array);
    }

    List<String> favorites(long guildId, long userId) {
        List<String> paths = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT path FROM younglings.dashboard_favorite WHERE guild_id = ? AND user_id = ? ORDER BY position")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) paths.add(rs.getString(1));
            }
            return paths;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the starred pages", e);
        }
    }

    void save(long guildId, long userId, List<String> paths) {
        try (Connection c = connectionSupplier.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.dashboard_favorite WHERE guild_id = ? AND user_id = ?");
                 PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.dashboard_favorite (guild_id, user_id, path, position) VALUES (?, ?, ?, ?)")) {
                delete.setLong(1, guildId);
                delete.setLong(2, userId);
                delete.executeUpdate();
                for (int i = 0; i < paths.size(); i++) {
                    insert.setLong(1, guildId);
                    insert.setLong(2, userId);
                    insert.setString(3, paths.get(i));
                    insert.setInt(4, i);
                    insert.addBatch();
                }
                insert.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the starred pages", e);
        }
    }
}
