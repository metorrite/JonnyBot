package com.younglings.bot.premium;

import com.younglings.bot.premium.BillingRules.Subscription;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Each server's paid subscription as the website last forwarded it, and the events already taken (tables in {@link PremiumDatabaseInitializer}). */
@BService
public class BillingRepository {
    private static final Logger log = LoggerFactory.getLogger(BillingRepository.class);
    private static final String COLUMNS = "guild_id, customer_id, subscription_id, status, current_period_end, cancel_at_period_end, event_created, event_id";

    private final ConnectionSupplier connectionSupplier;

    public BillingRepository(ConnectionSupplier connectionSupplier, PremiumDatabaseInitializer initializer) {
        this.connectionSupplier = connectionSupplier;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Subscription map(ResultSet rs) throws SQLException {
        return new Subscription(rs.getLong("guild_id"), rs.getString("customer_id"), rs.getString("subscription_id"), rs.getString("status"),
                instant(rs, "current_period_end"), rs.getBoolean("cancel_at_period_end"), instant(rs, "event_created"), rs.getString("event_id"));
    }

    /** Whether this event was taken before. */
    public boolean seen(String eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT 1 FROM younglings.billing_event WHERE event_id = ?")) {
            s.setString(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log.error("Failed to look up a billing event", e);
            throw new RuntimeException("Failed to look up the billing event", e);
        }
    }

    public Optional<Subscription> subscription(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.premium_subscription WHERE guild_id = ?")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read the subscription of guild {}", guildId, e);
            throw new RuntimeException("Failed to read the subscription", e);
        }
    }

    /** Every stored subscription, for the daily reconcile. */
    public List<Subscription> all() {
        List<Subscription> subs = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT " + COLUMNS + " FROM younglings.premium_subscription ORDER BY guild_id");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) subs.add(map(rs));
            return subs;
        } catch (SQLException e) {
            log.error("Failed to read the subscriptions", e);
            throw new RuntimeException("Failed to read the subscriptions", e);
        }
    }

    /**
     * Records the event and, when {@code store} is true, makes {@code sub} the server's stored subscription, in one transaction. Returns false (and
     * changes nothing) when the event was already recorded.
     */
    public boolean record(Subscription sub, boolean store, String outcome) {
        try (Connection c = connectionSupplier.getConnection()) {
            boolean autoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement s = c.prepareStatement("INSERT INTO younglings.billing_event (event_id, guild_id, outcome) VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING")) {
                    s.setString(1, sub.eventId());
                    s.setLong(2, sub.guildId());
                    s.setString(3, outcome);
                    if (s.executeUpdate() == 0) {
                        c.rollback();
                        return false;
                    }
                }
                if (store) {
                    String sql = """
                            INSERT INTO younglings.premium_subscription (guild_id, customer_id, subscription_id, status, current_period_end, cancel_at_period_end, event_created, event_id, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                            ON CONFLICT (guild_id) DO UPDATE SET customer_id = EXCLUDED.customer_id, subscription_id = EXCLUDED.subscription_id, status = EXCLUDED.status,
                                current_period_end = EXCLUDED.current_period_end, cancel_at_period_end = EXCLUDED.cancel_at_period_end,
                                event_created = EXCLUDED.event_created, event_id = EXCLUDED.event_id, updated_at = now()
                            """;
                    try (PreparedStatement s = c.prepareStatement(sql)) {
                        s.setLong(1, sub.guildId());
                        s.setString(2, sub.customerId());
                        s.setString(3, sub.subscriptionId());
                        s.setString(4, sub.status());
                        if (sub.currentPeriodEnd() == null) s.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
                        else s.setTimestamp(5, Timestamp.from(sub.currentPeriodEnd()));
                        s.setBoolean(6, sub.cancelAtPeriodEnd());
                        s.setTimestamp(7, Timestamp.from(sub.created()));
                        s.setString(8, sub.eventId());
                        s.executeUpdate();
                    }
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            log.error("Failed to record a billing event for guild {}", sub.guildId(), e);
            throw new RuntimeException("Failed to record the billing event", e);
        }
    }
}
