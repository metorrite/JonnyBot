package com.runeclans.bot.forms;

import com.runeclans.bot.database.SchemaBootstrapper;
import com.runeclans.bot.forms.WebForms.Answer;
import com.runeclans.bot.forms.WebForms.Form;
import com.runeclans.bot.forms.WebForms.Kind;
import com.runeclans.bot.forms.WebForms.Question;
import com.runeclans.bot.forms.WebForms.Submission;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Storage for web forms and their submissions. A form's questions and a submission's answers are kept as JSON in one column each. */
@BService
public class WebFormRepository {
    private static final Logger log = LoggerFactory.getLogger(WebFormRepository.class);

    private static final String FORM_COLUMNS = "id, guild_id, name, title, description, enabled, panel_id, channel_id, role_id, one_per_user, cooldown_hours, questions";

    private final ConnectionSupplier connectionSupplier;

    public WebFormRepository(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "web forms", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.web_form (
                    id BIGSERIAL PRIMARY KEY,
                    guild_id BIGINT NOT NULL,
                    name TEXT NOT NULL,
                    title TEXT NOT NULL DEFAULT '',
                    description TEXT NOT NULL DEFAULT '',
                    enabled BOOLEAN NOT NULL DEFAULT FALSE,
                    panel_id BIGINT NULL,
                    channel_id BIGINT NULL,
                    role_id BIGINT NULL,
                    one_per_user BOOLEAN NOT NULL DEFAULT FALSE,
                    cooldown_hours INT NOT NULL DEFAULT 0,
                    questions TEXT NOT NULL DEFAULT '[]',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,
                "CREATE INDEX IF NOT EXISTS web_form_guild_idx ON younglings.web_form (guild_id);",
                """
                CREATE TABLE IF NOT EXISTS younglings.web_form_submission (
                    id BIGSERIAL PRIMARY KEY,
                    form_id BIGINT NOT NULL REFERENCES younglings.web_form (id) ON DELETE CASCADE,
                    guild_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    answers TEXT NOT NULL DEFAULT '[]',
                    ticket_id BIGINT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                );
                """,
                "CREATE INDEX IF NOT EXISTS web_form_submission_form_user_idx ON younglings.web_form_submission (form_id, user_id, created_at DESC);"
        ));
    }

    // ---------- forms ----------

    public List<Form> list(long guildId) {
        List<Form> forms = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + FORM_COLUMNS + " FROM younglings.web_form WHERE guild_id = ? ORDER BY id")) {
            statement.setLong(1, guildId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) forms.add(read(rs));
            }
            return forms;
        } catch (SQLException e) {
            log.error("Failed to list web forms of guild {}", guildId, e);
            throw new RuntimeException("Failed to list the web forms", e);
        }
    }

    public Optional<Form> get(long formId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + FORM_COLUMNS + " FROM younglings.web_form WHERE id = ?")) {
            statement.setLong(1, formId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(read(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            log.error("Failed to read web form {}", formId, e);
            throw new RuntimeException("Failed to read the web form", e);
        }
    }

    /** Adds a form (its {@code id} is ignored) and returns it with its new id. */
    public Form create(Form form) {
        String sql = "INSERT INTO younglings.web_form (guild_id, name, title, description, enabled, panel_id, channel_id, role_id, one_per_user, cooldown_hours, questions) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, form, 1);
            statement.setLong(1, form.guildId());
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return get(rs.getLong(1)).orElseThrow();
            }
        } catch (SQLException e) {
            log.error("Failed to create a web form", e);
            throw new RuntimeException("Failed to create the web form", e);
        }
    }

    /** Replaces everything about the form (never its server). Returns false if there is no such form in that server. */
    public boolean save(Form form) {
        String sql = "UPDATE younglings.web_form SET name = ?, title = ?, description = ?, enabled = ?, panel_id = ?, channel_id = ?, role_id = ?, one_per_user = ?, cooldown_hours = ?, questions = ? "
                + "WHERE id = ? AND guild_id = ?";
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, form.name());
            statement.setString(i++, form.title());
            statement.setString(i++, form.description());
            statement.setBoolean(i++, form.enabled());
            setNullableLong(statement, i++, form.panelId());
            setNullableLong(statement, i++, form.channelId());
            setNullableLong(statement, i++, form.roleId());
            statement.setBoolean(i++, form.onePerUser());
            statement.setInt(i++, form.cooldownHours());
            statement.setString(i++, questionsJson(form.questions()));
            statement.setLong(i++, form.id());
            statement.setLong(i, form.guildId());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to save web form {}", form.id(), e);
            throw new RuntimeException("Failed to save the web form", e);
        }
    }

    public boolean delete(long guildId, long formId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM younglings.web_form WHERE id = ? AND guild_id = ?")) {
            statement.setLong(1, formId);
            statement.setLong(2, guildId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete web form {}", formId, e);
            throw new RuntimeException("Failed to delete the web form", e);
        }
    }

    // ---------- submissions ----------

    public long addSubmission(Form form, long userId, List<Answer> answers) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO younglings.web_form_submission (form_id, guild_id, user_id, answers) VALUES (?, ?, ?, ?) RETURNING id")) {
            statement.setLong(1, form.id());
            statement.setLong(2, form.guildId());
            statement.setLong(3, userId);
            statement.setString(4, answersJson(answers));
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.error("Failed to save a submission of web form {}", form.id(), e);
            throw new RuntimeException("Failed to save the submission", e);
        }
    }

    public void setTicket(long submissionId, long ticketId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE younglings.web_form_submission SET ticket_id = ? WHERE id = ?")) {
            statement.setLong(1, ticketId);
            statement.setLong(2, submissionId);
            statement.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to link submission {} to its ticket", submissionId, e);
            throw new RuntimeException("Failed to link the submission to its ticket", e);
        }
    }

    /** When this person last submitted this form, or null. */
    public Instant lastSubmission(long formId, long userId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT max(created_at) FROM younglings.web_form_submission WHERE form_id = ? AND user_id = ?")) {
            statement.setLong(1, formId);
            statement.setLong(2, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        } catch (SQLException e) {
            log.error("Failed to read the last submission of web form {}", formId, e);
            throw new RuntimeException("Failed to read the last submission", e);
        }
    }

    public int countSubmissions(long formId) {
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM younglings.web_form_submission WHERE form_id = ?")) {
            statement.setLong(1, formId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count submissions of web form {}", formId, e);
            throw new RuntimeException("Failed to count the submissions", e);
        }
    }

    /** The newest submissions of a form first. */
    public List<Submission> submissions(long formId, int limit) {
        List<Submission> found = new ArrayList<>();
        try (Connection connection = connectionSupplier.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT id, form_id, user_id, answers, ticket_id, created_at FROM younglings.web_form_submission WHERE form_id = ? ORDER BY id DESC LIMIT ?")) {
            statement.setLong(1, formId);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long ticket = rs.getLong("ticket_id");
                    found.add(new Submission(rs.getLong("id"), rs.getLong("form_id"), rs.getLong("user_id"), rs.getTimestamp("created_at").toInstant(),
                            answersOf(rs.getString("answers")), rs.wasNull() ? null : ticket));
                }
            }
            return found;
        } catch (SQLException e) {
            log.error("Failed to read submissions of web form {}", formId, e);
            throw new RuntimeException("Failed to read the submissions", e);
        }
    }

    // ---------- reading and writing the columns ----------

    private static Form read(ResultSet rs) throws SQLException {
        return new Form(rs.getLong("id"), rs.getLong("guild_id"), rs.getString("name"), rs.getString("title"), rs.getString("description"), rs.getBoolean("enabled"),
                nullableLong(rs, "panel_id"), nullableLong(rs, "channel_id"), nullableLong(rs, "role_id"), rs.getBoolean("one_per_user"), rs.getInt("cooldown_hours"),
                questionsOf(rs.getString("questions")));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT); else statement.setLong(index, value);
    }

    /** Fills parameters 2 to 11 of the insert (parameter 1 is the guild). */
    private static void bind(PreparedStatement statement, Form form, int first) throws SQLException {
        int i = first + 1;
        statement.setString(i++, form.name());
        statement.setString(i++, form.title());
        statement.setString(i++, form.description());
        statement.setBoolean(i++, form.enabled());
        setNullableLong(statement, i++, form.panelId());
        setNullableLong(statement, i++, form.channelId());
        setNullableLong(statement, i++, form.roleId());
        statement.setBoolean(i++, form.onePerUser());
        statement.setInt(i++, form.cooldownHours());
        statement.setString(i, questionsJson(form.questions()));
    }

    static String questionsJson(List<Question> questions) {
        DataArray array = DataArray.empty();
        for (Question q : questions) {
            DataArray options = DataArray.empty();
            q.options().forEach(options::add);
            array.add(DataObject.empty().put("id", q.id()).put("label", q.label()).put("kind", q.kind().name()).put("required", q.required())
                    .put("options", options).put("maxLength", q.maxLength()));
        }
        return array.toString();
    }

    static List<Question> questionsOf(String json) {
        List<Question> questions = new ArrayList<>();
        try {
            DataArray array = DataArray.fromJson(json);
            for (int i = 0; i < array.length(); i++) {
                DataObject o = array.getObject(i);
                List<String> options = new ArrayList<>();
                if (o.hasKey("options")) {
                    DataArray raw = o.getArray("options");
                    for (int j = 0; j < raw.length(); j++) options.add(raw.getString(j));
                }
                questions.add(new Question(o.getString("id"), o.getString("label"), Kind.ofKey(o.getString("kind", "SHORT")).orElse(Kind.SHORT), o.getBoolean("required", false),
                        options, o.getInt("maxLength", WebForms.DEFAULT_ANSWER_LENGTH)));
            }
        } catch (RuntimeException e) {
            log.warn("A web form's questions could not be read; treating them as none", e);
            return List.of();
        }
        return questions;
    }

    static String answersJson(List<Answer> answers) {
        DataArray array = DataArray.empty();
        for (Answer a : answers) array.add(DataObject.empty().put("id", a.questionId()).put("label", a.label()).put("value", a.value()));
        return array.toString();
    }

    static List<Answer> answersOf(String json) {
        List<Answer> answers = new ArrayList<>();
        try {
            DataArray array = DataArray.fromJson(json);
            for (int i = 0; i < array.length(); i++) {
                DataObject o = array.getObject(i);
                answers.add(new Answer(o.getString("id", ""), o.getString("label", ""), o.getString("value", "")));
            }
        } catch (RuntimeException e) {
            log.warn("A web form submission's answers could not be read", e);
        }
        return answers;
    }
}
