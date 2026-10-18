package com.younglings.bot.events;

import com.younglings.bot.database.SchemaBootstrapper;
import com.younglings.bot.events.EventRules.Evidence;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventRules.Source;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.PrizePlan.Split;
import com.younglings.bot.events.PrizeRules.AwardStatus;
import com.younglings.bot.events.PrizeRules.BuyInStatus;
import com.younglings.bot.events.PrizeRules.PrizeKind;
import com.younglings.bot.events.TeamRules.Kind;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The event engine's tables: events, teams, participants, objectives, the append-only progress log, and claims (proof waiting for an
 * organiser). The log is never edited; a mistake is corrected with an opposite entry. Every write that must not happen twice is one SQL
 * statement (a conditional insert or update), so two organisers acting at once cannot both win.
 */
@BService
public class EventRepository {
    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);

    public record Event(long id, long guildId, String template, String name, String description, Status status, Scope scope, ScoringMode scoringMode,
                        Instant startsAt, Instant endsAt, int maxParticipants, String config, long createdBy, Instant createdAt, Long signupId, boolean archived) {}

    public record Team(long id, long eventId, String name, Long captainId) {}

    /**
     * {@code waiting} is true while the player is on the waitlist of a full event; {@code buyIn} is their buy-in stage. {@code verification} is what is
     * stored (PENDING until someone decides); how it reads depends on whether the event asks for verification, see {@link EventRoster#effective}.
     */
    public record Participant(long eventId, long userId, long guildId, Long teamId, Kind kind, boolean waiting, BuyInStatus buyIn, Instant joinedAt,
                              EventRoster.Verification verification, Long verifiedBy, Instant verifiedAt, String verificationNote, Integer rating, RosterSource source) {}

    /**
     * Where an event is in its setup: when signups were closed, the roster confirmed and the teams called final, and by whom, and when its roles and channels
     * were set up (and whether everything wanted is still there, with a note on what is missing if not). Nothing set until an organiser does it.
     */
    public record Setup(Instant signupsClosedAt, Long signupsClosedBy, Instant rosterConfirmedAt, Long rosterConfirmedBy, Instant teamsFinalisedAt, Long teamsFinalisedBy,
                        Instant spaceAppliedAt, Long spaceAppliedBy, boolean spaceComplete, String spaceNote) {
        public static final Setup NONE = new Setup(null, null, null, null, null, null, null, null, false, "");

        public boolean spaceApplied() {
            return spaceAppliedAt != null;
        }

        public boolean signupsClosed() {
            return signupsClosedAt != null;
        }

        public boolean rosterConfirmed() {
            return rosterConfirmedAt != null;
        }

        public boolean teamsFinalised() {
            return teamsFinalisedAt != null;
        }
    }

    /** What one person is owed for one prize at one place, and where that stands. */
    public record AwardRow(long id, long eventId, long prizeId, int place, long userId, PrizeKind kind, String description, long amount, AwardStatus status,
                           Instant createdAt, Instant updatedAt) {}

    /** One step in an award's history: who moved it from one stage to another, and when. */
    public record AwardStep(long id, long awardId, AwardStatus from, AwardStatus to, Long by, String note, Instant at) {}

    public record Progress(long id, long eventId, String objectiveKey, long userId, Long teamId, long amount, Source source, String sourceKey,
                           Long recordedBy, String note, Instant createdAt) {}

    public enum ClaimStatus { PENDING, APPROVED, DENIED }

    /** What a claim is for: OBJECTIVE is proof for a tile or objective, VERIFICATION is a player's proof for the event's pre-start verification (no objective, no points). */
    public enum ClaimKind { OBJECTIVE, VERIFICATION }

    public record Claim(long id, long eventId, String objectiveKey, long userId, long amount, String note, String proofUrl, ClaimStatus status,
                        Long decidedBy, String decisionNote, Long progressId, Instant createdAt, Instant decidedAt, ClaimKind kind,
                        Long receiptChannelId, Long receiptMessageId) {}

    private final ConnectionSupplier connectionSupplier;

    public EventRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "events", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    template TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'DRAFT',
                    scope TEXT NOT NULL DEFAULT 'PRIVATE',
                    scoring_mode TEXT NOT NULL DEFAULT 'INDIVIDUAL',
                    starts_at TIMESTAMPTZ NULL,
                    ends_at TIMESTAMPTZ NULL,
                    max_participants INT NOT NULL DEFAULT 0,
                    config TEXT NOT NULL DEFAULT '{}',
                    created_by BIGINT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_guild ON younglings.event (guild_id, status, id DESC);",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_team (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    captain_id BIGINT NULL
                );
                """,
                "CREATE UNIQUE INDEX IF NOT EXISTS event_team_name ON younglings.event_team (event_id, LOWER(name));",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_participant (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    guild_id BIGINT NOT NULL,
                    team_id BIGINT NULL,
                    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, user_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.event_objective (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    key TEXT NOT NULL,
                    label TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    points BIGINT NOT NULL DEFAULT 0,
                    evidence TEXT NOT NULL DEFAULT 'EITHER',
                    target BIGINT NOT NULL DEFAULT 1,
                    repeat_limit INT NOT NULL DEFAULT 1,
                    prerequisites TEXT NOT NULL DEFAULT '',
                    position INT NOT NULL DEFAULT 0,
                    UNIQUE (event_id, key)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.event_progress (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    objective_key TEXT NOT NULL,
                    user_id BIGINT NOT NULL,
                    team_id BIGINT NULL,
                    amount BIGINT NOT NULL,
                    source TEXT NOT NULL,
                    source_key TEXT NULL,
                    recorded_by BIGINT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_progress_event ON younglings.event_progress (event_id, objective_key);",
                // the idempotency key: one source (an approved claim, a plugin event) is only ever counted once per event
                "CREATE UNIQUE INDEX IF NOT EXISTS event_progress_source ON younglings.event_progress (event_id, source_key) WHERE source_key IS NOT NULL;",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_claim (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    objective_key TEXT NOT NULL,
                    user_id BIGINT NOT NULL,
                    amount BIGINT NOT NULL DEFAULT 1,
                    note TEXT NOT NULL DEFAULT '',
                    proof_url TEXT NULL,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    decided_by BIGINT NULL,
                    decision_note TEXT NULL,
                    progress_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    decided_at TIMESTAMPTZ NULL
                );
                """,
                "CREATE INDEX IF NOT EXISTS event_claim_event ON younglings.event_claim (event_id, status, id);",
                // /submit: what the claim is for, and the receipt message in the player's own (or team's) channel that is edited when staff decide
                "ALTER TABLE younglings.event_claim ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'OBJECTIVE';",
                "ALTER TABLE younglings.event_claim ADD COLUMN IF NOT EXISTS receipt_channel_id BIGINT NULL;",
                "ALTER TABLE younglings.event_claim ADD COLUMN IF NOT EXISTS receipt_message_id BIGINT NULL;",
                // the dedicated signup attached to the event (a signup panel in Discord), until it is closed
                "ALTER TABLE younglings.event ADD COLUMN IF NOT EXISTS signup_id BIGINT NULL;",
                "ALTER TABLE younglings.event ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'HOME_CLAN';",
                // each team's or player's own bingo board message ("t:<teamId>" or "u:<userId>"), with a fingerprint of the tiles it shows so an unchanged board isn't sent again
                """
                CREATE TABLE IF NOT EXISTS younglings.event_board_panel (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    subject TEXT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL,
                    state_hash TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (event_id, subject)
                );
                """,
                // where the player came from: SIGNUP follows the Discord signup both ways, SELF joined by themselves, STAFF was added by staff
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'STAFF';",
                // players an organiser removed and barred from this event again, with the reason
                """
                CREATE TABLE IF NOT EXISTS younglings.event_ban (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    banned_by BIGINT NOT NULL,
                    banned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, user_id)
                );
                """,
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'ACTIVE';",
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS buy_in_status TEXT NOT NULL DEFAULT 'NONE';",
                """
                CREATE TABLE IF NOT EXISTS younglings.event_prize (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    place INT NOT NULL,
                    place_to INT NOT NULL,
                    kind TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    amount BIGINT NOT NULL DEFAULT 0,
                    percent_of_pot INT NOT NULL DEFAULT 0,
                    split TEXT NOT NULL DEFAULT 'EQUAL'
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.event_award (
                    id BIGSERIAL PRIMARY KEY,
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    prize_id BIGINT NOT NULL,
                    place INT NOT NULL,
                    user_id BIGINT NOT NULL,
                    kind TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    amount BIGINT NOT NULL DEFAULT 0,
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (event_id, prize_id, place, user_id)
                );
                """,
                """
                CREATE TABLE IF NOT EXISTS younglings.event_award_log (
                    id BIGSERIAL PRIMARY KEY,
                    award_id BIGINT NOT NULL REFERENCES younglings.event_award (id) ON DELETE CASCADE,
                    from_status TEXT NOT NULL,
                    to_status TEXT NOT NULL,
                    by_user BIGINT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                """,
                // where an event is in its setup (signups closed, roster confirmed); a row only exists once an organiser has done one of them
                """
                CREATE TABLE IF NOT EXISTS younglings.event_setup (
                    event_id BIGINT PRIMARY KEY REFERENCES younglings.event (id) ON DELETE CASCADE,
                    signups_closed_at TIMESTAMPTZ NULL,
                    signups_closed_by BIGINT NULL,
                    roster_confirmed_at TIMESTAMPTZ NULL,
                    roster_confirmed_by BIGINT NULL
                );
                """,
                // verification: PENDING means nobody has decided yet, and only matters when the event asks for verification
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS verification TEXT NOT NULL DEFAULT 'PENDING';",
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS verified_by BIGINT NULL;",
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ NULL;",
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS verification_note TEXT NOT NULL DEFAULT '';",
                // what an organiser thinks of a player's strength (1 to 5), for balancing teams; null = not rated
                "ALTER TABLE younglings.event_participant ADD COLUMN IF NOT EXISTS rating SMALLINT NULL;",
                // when the teams were called final, and by whom
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS teams_finalised_at TIMESTAMPTZ NULL;",
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS teams_finalised_by BIGINT NULL;",
                // when the event's roles and channels were set up, whether everything wanted is still there, and what is missing if not
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS space_applied_at TIMESTAMPTZ NULL;",
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS space_applied_by BIGINT NULL;",
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS space_complete BOOLEAN NOT NULL DEFAULT FALSE;",
                "ALTER TABLE younglings.event_setup ADD COLUMN IF NOT EXISTS space_note TEXT NOT NULL DEFAULT '';",
                // Discord signups the event's rules turned away, and why, so staff can see them and the player is told once
                """
                CREATE TABLE IF NOT EXISTS younglings.event_signup_refusal (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    reason TEXT NOT NULL,
                    at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, user_id)
                );
                """,
                // the live board message the bot keeps up to date for an event
                """
                CREATE TABLE IF NOT EXISTS younglings.event_panel (
                    event_id BIGINT PRIMARY KEY REFERENCES younglings.event (id) ON DELETE CASCADE,
                    channel_id BIGINT NOT NULL,
                    message_id BIGINT NOT NULL
                );
                """,
                // where a player's tracked stat stood when a competition began (or when they joined it), so gains are counted from there
                """
                CREATE TABLE IF NOT EXISTS younglings.event_tracking_baseline (
                    event_id BIGINT NOT NULL REFERENCES younglings.event (id) ON DELETE CASCADE,
                    user_id BIGINT NOT NULL,
                    baseline_xp BIGINT NOT NULL,
                    counted_xp BIGINT NOT NULL DEFAULT 0, -- no longer used: what is counted is read from the progress log
                    taken_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    PRIMARY KEY (event_id, user_id)
                );
                """));
    }

    // ---------- events ----------

    public long createEvent(long guildId, String template, String name, String description, Scope scope, ScoringMode mode, Instant startsAt, Instant endsAt,
                            int maxParticipants, String config, long createdBy) {
        String sql = """
                INSERT INTO younglings.event (guild_id, template, name, description, scope, scoring_mode, starts_at, ends_at, max_participants, config, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setString(2, template);
            s.setString(3, name);
            s.setString(4, description);
            s.setString(5, scope.name());
            s.setString(6, mode.name());
            setInstant(s, 7, startsAt);
            setInstant(s, 8, endsAt);
            s.setInt(9, maxParticipants);
            s.setString(10, config);
            s.setLong(11, createdBy);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create the event", e);
        }
    }

    public Optional<Event> event(long guildId, long id) {
        return events("SELECT * FROM younglings.event WHERE guild_id = ? AND id = ?", guildId, id).stream().findFirst();
    }

    /** The server's events, newest first, optionally only those in the given stages. */
    public List<Event> events(long guildId, List<Status> statuses, int limit) {
        List<Event> list = new ArrayList<>();
        String filter = statuses == null || statuses.isEmpty() ? "" : " AND status = ANY (?)";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event WHERE guild_id = ?" + filter + " ORDER BY id DESC LIMIT ?")) {
            int i = 1;
            s.setLong(i++, guildId);
            if (!filter.isEmpty()) s.setArray(i++, c.createArrayOf("text", statuses.stream().map(Enum::name).toArray()));
            s.setInt(i, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the events", e);
        }
    }

    public int countOpen(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.event WHERE guild_id = ? AND status NOT IN ('DONE', 'CANCELLED')")) {
            s.setLong(1, guildId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the events", e);
        }
    }

    /** Changes the settings of an event that is still being built. False when it doesn't exist or has moved past signups. */
    public boolean updateEvent(long guildId, long id, String name, String description, Scope scope, ScoringMode mode, Instant startsAt, Instant endsAt,
                               int maxParticipants, String config) {
        String sql = """
                UPDATE younglings.event SET name = ?, description = ?, scope = ?, scoring_mode = ?, starts_at = ?, ends_at = ?, max_participants = ?, config = ?, updated_at = NOW()
                WHERE guild_id = ? AND id = ? AND status IN ('DRAFT', 'SIGNUPS')
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, name);
            s.setString(2, description);
            s.setString(3, scope.name());
            s.setString(4, mode.name());
            setInstant(s, 5, startsAt);
            setInstant(s, 6, endsAt);
            s.setInt(7, maxParticipants);
            s.setString(8, config);
            s.setLong(9, guildId);
            s.setLong(10, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update the event", e);
        }
    }

    /** Hides or shows a finished event in the dashboard's list. False when it isn't finished (done or cancelled) or doesn't exist. */
    public boolean setArchived(long guildId, long id, boolean archived) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event SET archived = ? WHERE guild_id = ? AND id = ? AND status IN ('DONE', 'CANCELLED')")) {
            s.setBoolean(1, archived);
            s.setLong(2, guildId);
            s.setLong(3, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to archive the event", e);
        }
    }

    /** Archives every finished event of a server that isn't archived yet. Returns how many. */
    public int archiveFinished(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event SET archived = TRUE WHERE guild_id = ? AND archived = FALSE AND status IN ('DONE', 'CANCELLED')")) {
            s.setLong(1, guildId);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to archive the finished events", e);
        }
    }

    /** Replaces just the settings of an event, at any stage (organisers can change while it runs). False when the event doesn't exist. */
    public boolean setConfig(long guildId, long id, String config) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event SET config = ?, updated_at = NOW() WHERE guild_id = ? AND id = ?")) {
            s.setString(1, config);
            s.setLong(2, guildId);
            s.setLong(3, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the event's settings", e);
        }
    }

    /** Moves an event from one stage to another. False when it wasn't in {@code from} any more, so two clicks can't both move it. */
    public boolean moveStage(long guildId, long id, Status from, Status to) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event SET status = ?, updated_at = NOW() WHERE guild_id = ? AND id = ? AND status = ?")) {
            s.setString(1, to.name());
            s.setLong(2, guildId);
            s.setLong(3, id);
            s.setString(4, from.name());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to move the event", e);
        }
    }

    /** Attaches a signup to an event, or detaches it when {@code signupId} is null. */
    public void setSignup(long eventId, Long signupId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event SET signup_id = ? WHERE id = ?")) {
            if (signupId == null) s.setNull(1, Types.BIGINT); else s.setLong(1, signupId);
            s.setLong(2, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to attach the signup", e);
        }
    }

    /** Events (in every server) that still have a signup attached, for the sweep that syncs and closes them. */
    public List<Event> eventsWithSignup() {
        List<Event> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event WHERE signup_id IS NOT NULL ORDER BY id");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) list.add(mapEvent(rs));
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the events with signups", e);
        }
    }

    /** Events (in every server) in {@code status}, for sweeps that act on all of them. */
    public List<Event> eventsInStatus(Status status) {
        List<Event> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event WHERE status = ? ORDER BY id")) {
            s.setString(1, status.name());
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the events", e);
        }
    }

    // ---------- setup: closing signups, confirming the roster ----------

    public Setup setup(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT signups_closed_at, signups_closed_by, roster_confirmed_at, roster_confirmed_by, teams_finalised_at, teams_finalised_by, space_applied_at, space_applied_by, space_complete, space_note FROM younglings.event_setup WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                if (!rs.next()) return Setup.NONE;
                Timestamp closed = rs.getTimestamp("signups_closed_at");
                Timestamp confirmed = rs.getTimestamp("roster_confirmed_at");
                Timestamp finalised = rs.getTimestamp("teams_finalised_at");
                Timestamp applied = rs.getTimestamp("space_applied_at");
                return new Setup(closed == null ? null : closed.toInstant(), nullableLong(rs, "signups_closed_by"), confirmed == null ? null : confirmed.toInstant(),
                        nullableLong(rs, "roster_confirmed_by"), finalised == null ? null : finalised.toInstant(), nullableLong(rs, "teams_finalised_by"),
                        applied == null ? null : applied.toInstant(), nullableLong(rs, "space_applied_by"), rs.getBoolean("space_complete"), rs.getString("space_note"));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's setup", e);
        }
    }

    /** Marks signups closed. False when they already were (so closing twice does nothing and can't say it worked). */
    public boolean closeSignups(long eventId, long by) {
        String sql = """
                INSERT INTO younglings.event_setup (event_id, signups_closed_at, signups_closed_by) VALUES (?, NOW(), ?)
                ON CONFLICT (event_id) DO UPDATE SET signups_closed_at = NOW(), signups_closed_by = EXCLUDED.signups_closed_by
                WHERE younglings.event_setup.signups_closed_at IS NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setLong(2, by);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to close the signups", e);
        }
    }

    /** Opens signups again, which also takes back a confirmed roster and final teams (both were made from a roster that is about to change). False when they weren't closed. */
    public boolean reopenSignups(long eventId) {
        String sql = """
                UPDATE younglings.event_setup SET signups_closed_at = NULL, signups_closed_by = NULL, roster_confirmed_at = NULL, roster_confirmed_by = NULL,
                       teams_finalised_at = NULL, teams_finalised_by = NULL
                WHERE event_id = ? AND signups_closed_at IS NOT NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reopen the signups", e);
        }
    }

    /** Marks the roster confirmed. False unless signups are closed and it wasn't confirmed already. */
    public boolean confirmRoster(long eventId, long by) {
        String sql = """
                UPDATE younglings.event_setup SET roster_confirmed_at = NOW(), roster_confirmed_by = ?
                WHERE event_id = ? AND signups_closed_at IS NOT NULL AND roster_confirmed_at IS NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, by);
            s.setLong(2, eventId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to confirm the roster", e);
        }
    }

    /** Records that the event's roles and channels were set up (or brought up to date), whether everything wanted is there, and what is missing if not. */
    public void markSpace(long eventId, Long by, boolean complete, String note) {
        String sql = """
                INSERT INTO younglings.event_setup (event_id, space_applied_at, space_applied_by, space_complete, space_note) VALUES (?, NOW(), ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET space_applied_at = COALESCE(younglings.event_setup.space_applied_at, NOW()),
                       space_applied_by = COALESCE(younglings.event_setup.space_applied_by, EXCLUDED.space_applied_by),
                       space_complete = EXCLUDED.space_complete, space_note = EXCLUDED.space_note
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            if (by == null) s.setNull(2, Types.BIGINT);
            else s.setLong(2, by);
            s.setBoolean(3, complete);
            s.setString(4, note == null ? "" : note);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the event's roles and channels", e);
        }
    }

    /** Forgets that the event's roles and channels were set up (they were removed). */
    public void clearSpace(long eventId) {
        String sql = "UPDATE younglings.event_setup SET space_applied_at = NULL, space_applied_by = NULL, space_complete = FALSE, space_note = '' WHERE event_id = ?";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear the event's roles and channels", e);
        }
    }

    /** Records an organiser's decision on a player's verification. False when the player isn't in the event. */
    public boolean setVerification(long eventId, long userId, EventRoster.Verification to, Long by, String note) {
        String sql = """
                UPDATE younglings.event_participant SET verification = ?, verified_by = ?, verified_at = NOW(), verification_note = ?
                WHERE event_id = ? AND user_id = ?
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, to.name());
            if (by == null) s.setNull(2, Types.BIGINT); else s.setLong(2, by);
            s.setString(3, note == null ? "" : note);
            s.setLong(4, eventId);
            s.setLong(5, userId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the verification", e);
        }
    }

    /** Events hosted in {@code guildId} that {@code userId} is a player of (waiting or not), newest first. Drafts are never shown to players. */
    public List<Event> eventsForUser(long guildId, long userId, int limit) {
        List<Event> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                SELECT e.* FROM younglings.event e JOIN younglings.event_participant p ON p.event_id = e.id
                WHERE e.guild_id = ? AND p.user_id = ? AND e.status <> 'DRAFT' ORDER BY e.id DESC LIMIT ?""")) {
            s.setLong(1, guildId);
            s.setLong(2, userId);
            s.setInt(3, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a player's events", e);
        }
    }

    /** A player an organiser removed and barred from taking part again, with the reason (shown to staff, and to the player if they try to come back). */
    public record Ban(long userId, String reason, long bannedBy, Instant at) {}

    /** Where a player on the roster came from: {@code SIGNUP} follows the event's Discord signup both ways, {@code SELF} joined on their own, {@code STAFF} was added by staff. */
    public enum RosterSource { SIGNUP, SELF, STAFF }

    public void setSource(long eventId, long userId, RosterSource source) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_participant SET source = ? WHERE event_id = ? AND user_id = ?")) {
            s.setString(1, source.name());
            s.setLong(2, eventId);
            s.setLong(3, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to note where a player came from", e);
        }
    }

    public Optional<Ban> ban(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id, reason, banned_by, banned_at FROM younglings.event_ban WHERE event_id = ? AND user_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Ban(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant())) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a ban", e);
        }
    }

    public List<Ban> bans(long eventId) {
        List<Ban> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id, reason, banned_by, banned_at FROM younglings.event_ban WHERE event_id = ? ORDER BY banned_at")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Ban(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getTimestamp(4).toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the bans", e);
        }
    }

    /** Bars a player from the event (or changes the reason of a ban that is already there). */
    public void addBan(long eventId, long userId, String reason, long bannedBy) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.event_ban (event_id, user_id, reason, banned_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, banned_by = EXCLUDED.banned_by, banned_at = NOW()""")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            s.setString(3, reason);
            s.setLong(4, bannedBy);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to ban a player", e);
        }
    }

    public boolean removeBan(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_ban WHERE event_id = ? AND user_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            return s.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to lift a ban", e);
        }
    }

    /** A signup that was turned away, and the reason in words. */
    public record Refusal(long userId, String reason, Instant at) {}

    /** Remembers why a signup was turned away. True when this is new (first time, or the reason changed), so the player is only told once. */
    public boolean recordRefusal(long eventId, long userId, String reason) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.event_signup_refusal (event_id, user_id, reason) VALUES (?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET reason = EXCLUDED.reason, at = NOW() WHERE younglings.event_signup_refusal.reason <> EXCLUDED.reason
                RETURNING 1""")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            s.setString(3, reason);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record a refused signup", e);
        }
    }

    public List<Refusal> refusals(long eventId) {
        List<Refusal> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT user_id, reason, at FROM younglings.event_signup_refusal WHERE event_id = ? ORDER BY at")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(new Refusal(rs.getLong(1), rs.getString(2), rs.getTimestamp(3).toInstant()));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the refused signups", e);
        }
    }

    public void clearRefusal(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_signup_refusal WHERE event_id = ? AND user_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear a refused signup", e);
        }
    }

    /** Every own-board message of the event, by subject ("t:<teamId>" or "u:<userId>"). */
    public java.util.Map<String, EventBoards.Posted> boardPanels(long eventId) {
        java.util.Map<String, EventBoards.Posted> map = new java.util.LinkedHashMap<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT subject, channel_id, message_id, state_hash FROM younglings.event_board_panel WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) map.put(rs.getString(1), new EventBoards.Posted(rs.getLong(2), rs.getLong(3), rs.getString(4)));
            }
            return map;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event's own boards", e);
        }
    }

    public void setBoardPanel(long eventId, String subject, long channelId, long messageId, String hash) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.event_board_panel (event_id, subject, channel_id, message_id, state_hash) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id, subject) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id, state_hash = EXCLUDED.state_hash""")) {
            s.setLong(1, eventId);
            s.setString(2, subject);
            s.setLong(3, channelId);
            s.setLong(4, messageId);
            s.setString(5, hash);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remember a board message", e);
        }
    }

    /** The message an event's live board is kept in. */
    public record Panel(long channelId, long messageId) {}

    public Optional<Panel> panel(long eventId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT channel_id, message_id FROM younglings.event_panel WHERE event_id = ?")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Panel(rs.getLong(1), rs.getLong(2))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read an event's board message", e);
        }
    }

    public void setPanel(long eventId, long channelId, long messageId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("""
                INSERT INTO younglings.event_panel (event_id, channel_id, message_id) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET channel_id = EXCLUDED.channel_id, message_id = EXCLUDED.message_id""")) {
            s.setLong(1, eventId);
            s.setLong(2, channelId);
            s.setLong(3, messageId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save an event's board message", e);
        }
    }

    /** Remembers the receipt message posted for a claim in the player's own channel, so it can be updated when staff decide. */
    public void setClaimReceipt(long eventId, long claimId, long channelId, long messageId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event_claim SET receipt_channel_id = ?, receipt_message_id = ? WHERE event_id = ? AND id = ?")) {
            s.setLong(1, channelId);
            s.setLong(2, messageId);
            s.setLong(3, eventId);
            s.setLong(4, claimId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update a claim's receipt", e);
        }
    }

    /** Points a claim's proof at a different address (the review message, which outlives Discord's expiring attachment links). */
    public void setClaimProof(long eventId, long claimId, String url) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_claim SET proof_url = ? WHERE event_id = ? AND id = ?")) {
            s.setString(1, url);
            s.setLong(2, eventId);
            s.setLong(3, claimId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to update a claim's proof", e);
        }
    }

    /** Running events of one template in every server, for the sweep that feeds tracked progress into them. */
    public List<Event> runningEvents(String template) {
        List<Event> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event WHERE template = ? AND status = 'RUNNING' ORDER BY id")) {
            s.setString(1, template);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the running events", e);
        }
    }

    /** The running events a player has joined, in every server, for feeding what the plugin reports into them. Waitlisted players are filtered later by the engine. */
    public List<Event> runningEventsForUser(long userId) {
        List<Event> list = new ArrayList<>();
        String sql = """
                SELECT e.* FROM younglings.event e
                JOIN younglings.event_participant p ON p.event_id = e.id
                WHERE p.user_id = ? AND e.status = 'RUNNING' AND NOT e.archived
                ORDER BY e.id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the player's running events", e);
        }
    }

    /** Where a player's tracked stat stood when the competition began for them. */
    public OptionalLong baseline(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT baseline_xp FROM younglings.event_tracking_baseline WHERE event_id = ? AND user_id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read a tracking baseline", e);
        }
    }

    /** Keeps the first baseline taken for a player: a later call changes nothing. */
    public void setBaseline(long eventId, long userId, long xp) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(
                "INSERT INTO younglings.event_tracking_baseline (event_id, user_id, baseline_xp) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            s.setLong(3, xp);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save a tracking baseline", e);
        }
    }

    /** Deletes an event with everything that belongs to it, but only a draft or one that is over (done or cancelled). */
    public boolean deleteEvent(long guildId, long id) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event WHERE guild_id = ? AND id = ? AND status IN ('DRAFT', 'DONE', 'CANCELLED')")) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to delete the event", e);
        }
    }

    // ---------- objectives ----------

    public List<ObjectiveSpec> objectives(long eventId) {
        List<ObjectiveSpec> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_objective WHERE event_id = ? ORDER BY position, id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String prerequisites = rs.getString("prerequisites");
                    list.add(new ObjectiveSpec(rs.getString("key"), rs.getString("label"), rs.getString("description"), rs.getLong("points"),
                            Evidence.valueOf(rs.getString("evidence")), rs.getLong("target"), rs.getInt("repeat_limit"),
                            prerequisites.isBlank() ? List.of() : Arrays.asList(prerequisites.split(","))));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the objectives", e);
        }
    }

    /** Replaces the whole objective list in one transaction, in the given order (the service has already validated the set). */
    public void replaceObjectives(long eventId, List<ObjectiveSpec> objectives) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement d = c.prepareStatement("DELETE FROM younglings.event_objective WHERE event_id = ?");
                 PreparedStatement i = c.prepareStatement("""
                         INSERT INTO younglings.event_objective (event_id, key, label, description, points, evidence, target, repeat_limit, prerequisites, position)
                         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                         """)) {
                d.setLong(1, eventId);
                d.executeUpdate();
                int position = 0;
                for (ObjectiveSpec o : objectives) {
                    i.setLong(1, eventId);
                    i.setString(2, o.key());
                    i.setString(3, o.label());
                    i.setString(4, o.description());
                    i.setLong(5, o.points());
                    i.setString(6, o.evidence().name());
                    i.setLong(7, o.target());
                    i.setInt(8, o.repeatLimit());
                    i.setString(9, String.join(",", o.prerequisites()));
                    i.setInt(10, position++);
                    i.addBatch();
                }
                i.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the objectives", e);
        }
    }

    // ---------- teams and participants ----------

    public OptionalLong addTeam(long eventId, String name, Long captainId, int maxTeams) {
        String sql = """
                INSERT INTO younglings.event_team (event_id, name, captain_id)
                SELECT ?, ?, ? WHERE (SELECT COUNT(*) FROM younglings.event_team WHERE event_id = ?) < ?
                ON CONFLICT DO NOTHING RETURNING id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, name);
            if (captainId == null) s.setNull(3, Types.BIGINT); else s.setLong(3, captainId);
            s.setLong(4, eventId);
            s.setInt(5, maxTeams);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add the team", e);
        }
    }

    public List<Team> teams(long eventId) {
        List<Team> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_team WHERE event_id = ? ORDER BY id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new Team(rs.getLong("id"), eventId, rs.getString("name"), nullableLong(rs, "captain_id")));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the teams", e);
        }
    }

    /** Removes a team; its players stay in the event, without a team. */
    public boolean removeTeam(long eventId, long teamId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement free = c.prepareStatement("UPDATE younglings.event_participant SET team_id = NULL WHERE event_id = ? AND team_id = ?");
             PreparedStatement del = c.prepareStatement("DELETE FROM younglings.event_team WHERE event_id = ? AND id = ?")) {
            free.setLong(1, eventId);
            free.setLong(2, teamId);
            free.executeUpdate();
            del.setLong(1, eventId);
            del.setLong(2, teamId);
            return del.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove the team", e);
        }
    }

    /**
     * Adds a player, with a place when the event has room and on the waitlist when it doesn't (decided in the same statement, so two people
     * taking the last place can't both get it). Empty when they were already in. {@code maxParticipants} 0 means no limit.
     */
    /**
     * Adds a player, or reports they are already in. Returns whether they were put on the waitlist (true) or given a place (false), or empty when
     * they were already in the event. The place count and the insert happen under a lock on the event, so two people joining the last place at the
     * same moment can't both get it.
     */
    public Optional<Boolean> join(long eventId, long userId, long guildId, Long teamId, Kind kind, BuyInStatus buyIn, int maxParticipants) {
        String sql = """
                INSERT INTO younglings.event_participant (event_id, user_id, guild_id, team_id, kind, status, buy_in_status)
                SELECT ?, ?, ?, ?, ?,
                       CASE WHEN ? = 0 OR (SELECT COUNT(*) FROM younglings.event_participant WHERE event_id = ? AND status = 'ACTIVE') < ? THEN 'ACTIVE' ELSE 'WAITING' END, ?
                ON CONFLICT DO NOTHING RETURNING status
                """;
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockParticipants(c, eventId);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, eventId);
                    s.setLong(2, userId);
                    s.setLong(3, guildId);
                    if (teamId == null) s.setNull(4, Types.BIGINT); else s.setLong(4, teamId);
                    s.setString(5, kind.name());
                    s.setInt(6, maxParticipants);
                    s.setLong(7, eventId);
                    s.setInt(8, maxParticipants);
                    s.setString(9, buyIn.name());
                    Optional<Boolean> result;
                    try (ResultSet rs = s.executeQuery()) {
                        result = rs.next() ? Optional.of(rs.getString(1).equals("WAITING")) : Optional.empty();
                    }
                    c.commit();
                    return result;
                }
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to add the player", e);
        }
    }

    /** Takes a lock on an event's player list until the transaction ends, so joins and promotions for one event happen one at a time. */
    private static void lockParticipants(Connection c, long eventId) throws SQLException {
        try (PreparedStatement lock = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            lock.setString(1, "event-participants:" + eventId);
            lock.execute();
        }
    }

    /** Moves a waiting player into a place, if there is one free. False when they weren't waiting or the event filled up first. */
    public boolean promote(long eventId, long userId, int maxParticipants) {
        String sql = """
                UPDATE younglings.event_participant SET status = 'ACTIVE'
                WHERE event_id = ? AND user_id = ? AND status = 'WAITING'
                  AND (? = 0 OR (SELECT COUNT(*) FROM younglings.event_participant WHERE event_id = ? AND status = 'ACTIVE') < ?)
                """;
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockParticipants(c, eventId);
                try (PreparedStatement s = c.prepareStatement(sql)) {
                    s.setLong(1, eventId);
                    s.setLong(2, userId);
                    s.setInt(3, maxParticipants);
                    s.setLong(4, eventId);
                    s.setInt(5, maxParticipants);
                    boolean moved = s.executeUpdate() == 1;
                    c.commit();
                    return moved;
                }
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to promote the player", e);
        }
    }

    /** Changes a player's buy-in stage. False when they weren't in {@code from}, so only one change wins. */
    public boolean moveBuyIn(long eventId, long userId, BuyInStatus from, BuyInStatus to) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event_participant SET buy_in_status = ? WHERE event_id = ? AND user_id = ? AND buy_in_status = ?")) {
            s.setString(1, to.name());
            s.setLong(2, eventId);
            s.setLong(3, userId);
            s.setString(4, from.name());
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the buy-in", e);
        }
    }

    // ---------- prizes and awards ----------

    public void replacePrizes(long eventId, List<PrizePlan.Prize> prizes) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement d = c.prepareStatement("DELETE FROM younglings.event_prize WHERE event_id = ?");
                 PreparedStatement i = c.prepareStatement("INSERT INTO younglings.event_prize (event_id, place, place_to, kind, description, amount, percent_of_pot, split) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                d.setLong(1, eventId);
                d.executeUpdate();
                for (PrizePlan.Prize p : prizes) {
                    i.setLong(1, eventId);
                    i.setInt(2, p.place());
                    i.setInt(3, p.placeTo());
                    i.setString(4, p.kind().name());
                    i.setString(5, p.description());
                    i.setLong(6, p.amount());
                    i.setInt(7, p.percentOfPot());
                    i.setString(8, p.split().name());
                    i.addBatch();
                }
                i.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the prizes", e);
        }
    }

    public List<PrizePlan.Prize> prizes(long eventId) {
        List<PrizePlan.Prize> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_prize WHERE event_id = ? ORDER BY place, id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new PrizePlan.Prize(rs.getLong("id"), rs.getInt("place"), rs.getInt("place_to"), PrizeKind.valueOf(rs.getString("kind")), rs.getString("description"),
                            rs.getLong("amount"), rs.getInt("percent_of_pot"), Split.valueOf(rs.getString("split"))));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the prizes", e);
        }
    }

    /** Records what someone is owed. Empty when that exact award already exists, so working the awards out twice is harmless. */
    public OptionalLong addAward(long eventId, PrizePlan.Award a) {
        String sql = "INSERT INTO younglings.event_award (event_id, prize_id, place, user_id, kind, description, amount) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setLong(2, a.prizeId());
            s.setInt(3, a.place());
            s.setLong(4, a.userId());
            s.setString(5, a.kind().name());
            s.setString(6, a.description());
            s.setLong(7, a.amount());
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the award", e);
        }
    }

    public List<AwardRow> awards(long eventId) {
        List<AwardRow> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_award WHERE event_id = ? ORDER BY place, id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapAward(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the awards", e);
        }
    }

    public Optional<AwardRow> award(long eventId, long id) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_award WHERE event_id = ? AND id = ?")) {
            s.setLong(1, eventId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(mapAward(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the award", e);
        }
    }

    /** Moves an award from one stage to another and writes the step to its history, together. False when it wasn't in {@code from} any more. */
    public boolean moveAward(long eventId, long id, AwardStatus from, AwardStatus to, Long by, String note) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement u = c.prepareStatement("UPDATE younglings.event_award SET status = ?, updated_at = NOW() WHERE event_id = ? AND id = ? AND status = ?");
                 PreparedStatement l = c.prepareStatement("INSERT INTO younglings.event_award_log (award_id, from_status, to_status, by_user, note) VALUES (?, ?, ?, ?, ?)")) {
                u.setString(1, to.name());
                u.setLong(2, eventId);
                u.setLong(3, id);
                u.setString(4, from.name());
                if (u.executeUpdate() != 1) {
                    c.rollback();
                    return false;
                }
                l.setLong(1, id);
                l.setString(2, from.name());
                l.setString(3, to.name());
                if (by == null) l.setNull(4, Types.BIGINT); else l.setLong(4, by);
                l.setString(5, note == null ? "" : note);
                l.executeUpdate();
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to move the award", e);
        }
    }

    public List<AwardStep> awardHistory(long awardId) {
        List<AwardStep> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_award_log WHERE award_id = ? ORDER BY id")) {
            s.setLong(1, awardId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    list.add(new AwardStep(rs.getLong("id"), awardId, AwardStatus.valueOf(rs.getString("from_status")), AwardStatus.valueOf(rs.getString("to_status")),
                            nullableLong(rs, "by_user"), rs.getString("note"), rs.getTimestamp("at").toInstant()));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the award history", e);
        }
    }

    private static AwardRow mapAward(ResultSet rs) throws SQLException {
        return new AwardRow(rs.getLong("id"), rs.getLong("event_id"), rs.getLong("prize_id"), rs.getInt("place"), rs.getLong("user_id"), PrizeKind.valueOf(rs.getString("kind")),
                rs.getString("description"), rs.getLong("amount"), AwardStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public boolean leave(long eventId, long userId) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("DELETE FROM younglings.event_participant WHERE event_id = ? AND user_id = ?")) {
                s.setLong(1, eventId);
                s.setLong(2, userId);
                boolean removed = s.executeUpdate() == 1;
                clearStrayCaptains(c, eventId);
                c.commit();
                return removed;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to remove the player", e);
        }
    }

    /** A team's captain has to be on it: whoever isn't any more (moved, taken off, or gone from the event) stops being captain. */
    private static void clearStrayCaptains(Connection c, long eventId) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                UPDATE younglings.event_team t SET captain_id = NULL
                WHERE t.event_id = ? AND t.captain_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM younglings.event_participant p WHERE p.event_id = t.event_id AND p.user_id = t.captain_id AND p.team_id = t.id)""")) {
            s.setLong(1, eventId);
            s.executeUpdate();
        }
    }

    public boolean setTeam(long eventId, long userId, Long teamId) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement s = c.prepareStatement("UPDATE younglings.event_participant SET team_id = ? WHERE event_id = ? AND user_id = ?")) {
                if (teamId == null) s.setNull(1, Types.BIGINT); else s.setLong(1, teamId);
                s.setLong(2, eventId);
                s.setLong(3, userId);
                boolean changed = s.executeUpdate() == 1;
                clearStrayCaptains(c, eventId);
                c.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the team", e);
        }
    }

    /**
     * Puts many players on teams (or takes them off, a null team) in one go: all of it or none of it. Returns how many players were changed; a player who isn't
     * in the event, or a team that doesn't exist for it, fails the whole change.
     */
    public int setTeams(long eventId, Map<Long, Long> teamOf) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                lockParticipants(c, eventId);
                int changed = 0;
                try (PreparedStatement s = c.prepareStatement("UPDATE younglings.event_participant SET team_id = ? WHERE event_id = ? AND user_id = ?")) {
                    for (Map.Entry<Long, Long> e : teamOf.entrySet()) {
                        if (e.getValue() == null) s.setNull(1, Types.BIGINT); else s.setLong(1, e.getValue());
                        s.setLong(2, eventId);
                        s.setLong(3, e.getKey());
                        changed += s.executeUpdate();
                    }
                }
                if (changed != teamOf.size()) throw new SQLException("A player in the change isn't in the event");
                clearStrayCaptains(c, eventId);
                c.commit();
                return changed;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to change the teams", e);
        }
    }

    /** Renames a team: 1 when done, 0 when the event has no such team, -1 when another team of the event already has that name. */
    public int renameTeam(long eventId, long teamId, String name) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_team SET name = ? WHERE event_id = ? AND id = ?")) {
            s.setString(1, name);
            s.setLong(2, eventId);
            s.setLong(3, teamId);
            return s.executeUpdate();
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) return -1;
            throw new RuntimeException("Failed to rename the team", e);
        }
    }

    /** Names a team's captain, who has to be an active player on that team (null for none). False when the team isn't there or the player isn't on it. */
    public boolean setCaptain(long eventId, long teamId, Long captainId) {
        String sql = """
                UPDATE younglings.event_team SET captain_id = ?
                WHERE event_id = ? AND id = ?
                  AND (?::BIGINT IS NULL OR EXISTS (SELECT 1 FROM younglings.event_participant p WHERE p.event_id = ? AND p.user_id = ? AND p.team_id = ? AND p.status = 'ACTIVE'))
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            // the captain is wanted three times (to set, to see whether there is one, and to find them on the team); the event and the team twice
            for (int index : new int[]{1, 4, 6}) {
                if (captainId == null) s.setNull(index, Types.BIGINT); else s.setLong(index, captainId);
            }
            s.setLong(2, eventId);
            s.setLong(3, teamId);
            s.setLong(5, eventId);
            s.setLong(7, teamId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to set the captain", e);
        }
    }

    /** Records how strong an organiser thinks a player is (1 to 5), or clears it with null. False when the player isn't in the event. */
    public boolean setRating(long eventId, long userId, Integer rating) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_participant SET rating = ? WHERE event_id = ? AND user_id = ?")) {
            if (rating == null) s.setNull(1, Types.SMALLINT); else s.setInt(1, rating);
            s.setLong(2, eventId);
            s.setLong(3, userId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save the rating", e);
        }
    }

    /** Calls the teams final. False when they already were (so doing it twice can't say it worked). */
    public boolean finaliseTeams(long eventId, long by) {
        String sql = """
                INSERT INTO younglings.event_setup (event_id, teams_finalised_at, teams_finalised_by) VALUES (?, NOW(), ?)
                ON CONFLICT (event_id) DO UPDATE SET teams_finalised_at = NOW(), teams_finalised_by = EXCLUDED.teams_finalised_by
                WHERE younglings.event_setup.teams_finalised_at IS NULL
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setLong(2, by);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to finalise the teams", e);
        }
    }

    /** Takes back "the teams are final". False when they weren't. */
    public boolean reopenTeams(long eventId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("UPDATE younglings.event_setup SET teams_finalised_at = NULL, teams_finalised_by = NULL WHERE event_id = ? AND teams_finalised_at IS NOT NULL")) {
            s.setLong(1, eventId);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to reopen the teams", e);
        }
    }

    public Optional<Participant> participant(long eventId, long userId) {
        return participants("SELECT * FROM younglings.event_participant WHERE event_id = ? AND user_id = ?", eventId, userId).stream().findFirst();
    }

    public List<Participant> participants(long eventId) {
        return participants("SELECT * FROM younglings.event_participant WHERE event_id = ? ORDER BY joined_at, user_id", eventId, null);
    }

    // ---------- the progress log ----------

    /** Appends a progress entry. Empty when {@code sourceKey} was already counted for this event, so a retry or double click can't count twice. */
    public OptionalLong addProgress(long eventId, String objectiveKey, long userId, Long teamId, long amount, Source source, String sourceKey, Long recordedBy, String note) {
        String sql = """
                INSERT INTO younglings.event_progress (event_id, objective_key, user_id, team_id, amount, source, source_key, recorded_by, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id
                """;
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, objectiveKey);
            s.setLong(3, userId);
            if (teamId == null) s.setNull(4, Types.BIGINT); else s.setLong(4, teamId);
            s.setLong(5, amount);
            s.setString(6, source.name());
            if (sourceKey == null) s.setNull(7, Types.VARCHAR); else s.setString(7, sourceKey);
            if (recordedBy == null) s.setNull(8, Types.BIGINT); else s.setLong(8, recordedBy);
            s.setString(9, note == null ? "" : note);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the progress", e);
        }
    }

    public List<Progress> progress(long eventId) {
        List<Progress> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM younglings.event_progress WHERE event_id = ? ORDER BY id")) {
            s.setLong(1, eventId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long team = rs.getLong("team_id");
                    boolean noTeam = rs.wasNull();
                    long by = rs.getLong("recorded_by");
                    boolean noBy = rs.wasNull();
                    list.add(new Progress(rs.getLong("id"), eventId, rs.getString("objective_key"), rs.getLong("user_id"), noTeam ? null : team, rs.getLong("amount"),
                            Source.valueOf(rs.getString("source")), rs.getString("source_key"), noBy ? null : by, rs.getString("note"), rs.getTimestamp("created_at").toInstant()));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the progress", e);
        }
    }

    /** What one subject (a player, or a team when {@code byTeam}) has so far on one objective: the sum of its entries, never below zero. */
    public long total(long eventId, String objectiveKey, long subjectId, boolean byTeam) {
        String column = byTeam ? "team_id" : "user_id";
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM younglings.event_progress WHERE event_id = ? AND objective_key = ? AND " + column + " = ?")) {
            s.setLong(1, eventId);
            s.setString(2, objectiveKey);
            s.setLong(3, subjectId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return Math.max(0, rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to total the progress", e);
        }
    }

    // ---------- claims ----------

    public long addClaim(long eventId, String objectiveKey, long userId, long amount, String note, String proofUrl) {
        return addClaim(eventId, ClaimKind.OBJECTIVE, objectiveKey, userId, amount, note, proofUrl);
    }

    public long addClaim(long eventId, ClaimKind kind, String objectiveKey, long userId, long amount, String note, String proofUrl) {
        String sql = "INSERT INTO younglings.event_claim (event_id, objective_key, user_id, amount, note, proof_url, kind) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            s.setString(2, objectiveKey);
            s.setLong(3, userId);
            s.setLong(4, amount);
            s.setString(5, note);
            if (proofUrl == null) s.setNull(6, Types.VARCHAR); else s.setString(6, proofUrl);
            s.setString(7, kind.name());
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to record the claim", e);
        }
    }

    public Optional<Claim> claim(long eventId, long id) {
        return claims("SELECT * FROM younglings.event_claim WHERE event_id = ? AND id = ?", eventId, id, null).stream().findFirst();
    }

    /** A player's own claims in an event, newest first. */
    public List<Claim> claimsOf(long eventId, long userId, int limit) {
        return claims("SELECT * FROM younglings.event_claim WHERE event_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?", eventId, userId, limit);
    }

    public List<Claim> pendingClaims(long eventId, int limit) {
        return claims("SELECT * FROM younglings.event_claim WHERE event_id = ? AND status = 'PENDING' ORDER BY id LIMIT ?", eventId, null, limit);
    }

    public List<Claim> decidedClaims(long eventId, int limit) {
        return claims("SELECT * FROM younglings.event_claim WHERE event_id = ? AND status <> 'PENDING' ORDER BY decided_at DESC, id DESC LIMIT ?", eventId, null, limit);
    }

    /** The proof for tiles and objectives a player has waiting (verification proof is counted by {@link #pendingVerificationClaimsFor}). */
    public int pendingClaimsFor(long eventId, long userId) {
        return countPending(eventId, userId, "OBJECTIVE");
    }

    public int pendingVerificationClaimsFor(long eventId, long userId) {
        return countPending(eventId, userId, "VERIFICATION");
    }

    private int countPending(long eventId, long userId, String kind) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM younglings.event_claim WHERE event_id = ? AND user_id = ? AND status = 'PENDING' AND kind = '" + kind + "'")) {
            s.setLong(1, eventId);
            s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count the claims", e);
        }
    }

    /** Decides a pending claim. False when someone else already did, so an approval can't be applied twice. */
    public boolean decideClaim(long eventId, long id, ClaimStatus status, long decidedBy, String note) {
        String sql = "UPDATE younglings.event_claim SET status = ?, decided_by = ?, decision_note = ?, decided_at = NOW() WHERE event_id = ? AND id = ? AND status = 'PENDING'";
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, status.name());
            s.setLong(2, decidedBy);
            if (note == null) s.setNull(3, Types.VARCHAR); else s.setString(3, note);
            s.setLong(4, eventId);
            s.setLong(5, id);
            return s.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to decide the claim", e);
        }
    }

    public void linkClaimProgress(long claimId, long progressId) {
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement("UPDATE younglings.event_claim SET progress_id = ? WHERE id = ?")) {
            s.setLong(1, progressId);
            s.setLong(2, claimId);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to link the claim", e);
        }
    }

    // ---------- mapping ----------

    private List<Event> events(String sql, long guildId, long id) {
        List<Event> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, guildId);
            s.setLong(2, id);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) list.add(mapEvent(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the event", e);
        }
    }

    private static Event mapEvent(ResultSet rs) throws SQLException {
        Timestamp starts = rs.getTimestamp("starts_at");
        Timestamp ends = rs.getTimestamp("ends_at");
        return new Event(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("template"), rs.getString("name"), rs.getString("description"),
                Status.valueOf(rs.getString("status")), Scope.valueOf(rs.getString("scope")), ScoringMode.valueOf(rs.getString("scoring_mode")),
                starts == null ? null : starts.toInstant(), ends == null ? null : ends.toInstant(), rs.getInt("max_participants"), rs.getString("config"),
                rs.getLong("created_by"), rs.getTimestamp("created_at").toInstant(), nullableLong(rs, "signup_id"), rs.getBoolean("archived"));
    }

    private List<Participant> participants(String sql, long eventId, Long userId) {
        List<Participant> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, eventId);
            if (userId != null) s.setLong(2, userId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    Timestamp verifiedAt = rs.getTimestamp("verified_at");
                    list.add(new Participant(eventId, rs.getLong("user_id"), rs.getLong("guild_id"), nullableLong(rs, "team_id"), Kind.valueOf(rs.getString("kind")),
                            rs.getString("status").equals("WAITING"), BuyInStatus.valueOf(rs.getString("buy_in_status")), rs.getTimestamp("joined_at").toInstant(),
                            EventRoster.Verification.valueOf(rs.getString("verification")), nullableLong(rs, "verified_by"), verifiedAt == null ? null : verifiedAt.toInstant(),
                            rs.getString("verification_note"), nullableInt(rs, "rating"), RosterSource.valueOf(rs.getString("source"))));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the players", e);
        }
    }

    private List<Claim> claims(String sql, long eventId, Long id, Integer limit) {
        List<Claim> list = new ArrayList<>();
        try (Connection c = connectionSupplier.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            int i = 1;
            s.setLong(i++, eventId);
            if (id != null) s.setLong(i++, id);
            if (limit != null) s.setInt(i, limit);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    long by = rs.getLong("decided_by");
                    boolean noBy = rs.wasNull();
                    long progress = rs.getLong("progress_id");
                    boolean noProgress = rs.wasNull();
                    Timestamp decided = rs.getTimestamp("decided_at");
                    list.add(new Claim(rs.getLong("id"), eventId, rs.getString("objective_key"), rs.getLong("user_id"), rs.getLong("amount"), rs.getString("note"),
                            rs.getString("proof_url"), ClaimStatus.valueOf(rs.getString("status")), noBy ? null : by, rs.getString("decision_note"),
                            noProgress ? null : progress, rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(),
                            ClaimKind.valueOf(rs.getString("kind")), nullableLong(rs, "receipt_channel_id"), nullableLong(rs, "receipt_message_id")));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to read the claims", e);
        }
    }

    /** A nullable BIGINT column, read so that SQL NULL stays null (wasNull() only speaks for the column read just before it). */
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void setInstant(PreparedStatement s, int index, Instant value) throws SQLException {
        if (value == null) s.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE); else s.setTimestamp(index, Timestamp.from(value));
    }
}
