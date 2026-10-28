package com.runeclans.bot.siteaccess;

import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** The stored on/off state of each {@link SiteSwitch}. A switch with no row is off. */
@BService
public class SiteSwitchRepository {
    private static final Logger log = LoggerFactory.getLogger(SiteSwitchRepository.class);

    private final ConnectionSupplier connectionSupplier;

    public SiteSwitchRepository(ConnectionSupplier connectionSupplier, SiteSwitchDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    /** Every stored switch by key. */
    public Map<String, Boolean> all() {
        Map<String, Boolean> states = new HashMap<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT switch_key, enabled FROM younglings.site_switch");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) states.put(rs.getString("switch_key"), rs.getBoolean("enabled"));
            return states;
        } catch (SQLException e) {
            log.error("Failed to read the site access switches", e);
            throw new RuntimeException("Failed to read the site access switches", e);
        }
    }

    public void set(String key, boolean enabled, long updatedBy) {
        String sql = "INSERT INTO younglings.site_switch (switch_key, enabled, updated_by, updated_at) VALUES (?, ?, ?, now()) "
                + "ON CONFLICT (switch_key) DO UPDATE SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by, updated_at = now()";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, key);
            statement.setBoolean(2, enabled);
            statement.setLong(3, updatedBy);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to save site access switch {}", key, e);
            throw new RuntimeException("Failed to save the site access switch", e);
        }
    }
}
