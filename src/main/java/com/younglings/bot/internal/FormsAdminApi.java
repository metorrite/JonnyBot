package com.younglings.bot.internal;

import com.younglings.bot.forms.WebFormRepository;
import com.younglings.bot.forms.WebForms;
import com.younglings.bot.forms.WebForms.Answer;
import com.younglings.bot.forms.WebForms.Form;
import com.younglings.bot.forms.WebForms.Kind;
import com.younglings.bot.forms.WebForms.Question;
import com.younglings.bot.forms.WebForms.Submission;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketRepository;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The dashboard's form builder ({@code forms}): list, create (blank or from a template), save, delete, and read the latest submissions. Reached
 * only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server, rate-limits writes and
 * audit-logs every change. Everything is scoped to the server in the request, so one server can never touch another's forms.
 */
@BService
public class FormsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(FormsAdminApi.class);
    private static final int SUBMISSIONS_SHOWN = 50;

    private final WebFormRepository repository;
    private final TicketRepository tickets;

    public FormsAdminApi(WebFormRepository repository, TicketRepository tickets) {
        this.repository = repository;
        this.tickets = tickets;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("forms")) return null;
        if (parts.length == 1) {
            if (method.equals("POST")) return create(guild, body.get());
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        long id = parseId(parts[1]);
        if (parts.length == 2) {
            if (method.equals("PUT")) return save(guild, id, body.get());
            AdminRouteExtension.requireMethod(method, "DELETE");
            return remove(guild, id);
        }
        if (parts.length == 3 && parts[2].equals("submissions")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return submissions(guild, id);
        }
        return null;
    }

    // ---------- reading ----------

    DataObject list(Guild guild) {
        DataArray forms = DataArray.empty();
        for (Form form : repository.list(guild.getIdLong())) forms.add(formJson(form).put("submissionCount", repository.countSubmissions(form.id())));
        DataArray panels = DataArray.empty();
        for (Panel panel : tickets.getPanels(guild.getIdLong())) panels.add(DataObject.empty().put("id", Long.toString(panel.id())).put("name", panel.name()).put("enabled", panel.enabled()));
        return DataObject.empty().put("forms", forms).put("panels", panels).put("max", WebForms.MAX_FORMS_PER_SERVER)
                .put("limits", DataObject.empty().put("questions", WebForms.MAX_QUESTIONS).put("options", WebForms.MAX_OPTIONS).put("answerLength", WebForms.MAX_ANSWER_LENGTH)
                        .put("cooldownHours", WebForms.MAX_COOLDOWN_HOURS));
    }

    DataObject submissions(Guild guild, long formId) {
        Form form = owned(guild, formId);
        DataArray array = DataArray.empty();
        for (Submission s : repository.submissions(form.id(), SUBMISSIONS_SHOWN)) {
            Member member = guild.getMemberById(s.userId());
            DataArray answers = DataArray.empty();
            for (Answer a : s.answers()) answers.add(DataObject.empty().put("label", a.label()).put("value", a.value()));
            array.add(DataObject.empty().put("id", Long.toString(s.id())).put("userId", Long.toString(s.userId())).put("userName", member == null ? null : member.getEffectiveName())
                    .put("at", s.createdAt().toString()).put("ticketId", s.ticketId() == null ? null : Long.toString(s.ticketId())).put("answers", answers));
        }
        return DataObject.empty().put("formId", Long.toString(form.id())).put("submissions", array).put("total", repository.countSubmissions(form.id()));
    }

    // ---------- writing ----------

    /** A new form: {@code {"template": "clan-application"}} starts from that template, anything else from a blank one. Always starts switched off. */
    DataObject create(Guild guild, DataObject body) {
        long guildId = guild.getIdLong();
        if (repository.list(guildId).size() >= WebForms.MAX_FORMS_PER_SERVER) throw new ApiError(400, Text.of(guild, "dashboard.a_server_can_have_at_most", WebForms.MAX_FORMS_PER_SERVER));
        Form start = WebForms.template(body.getString("template", ""), guildId).orElseGet(() -> new Form(0, guildId, "New form", "", "", false, null, null, null, false, 0, List.of()));
        Form created = repository.create(start);
        log.info("Created web form {} in guild {}", created.id(), guildId);
        return list(guild);
    }

    DataObject save(Guild guild, long formId, DataObject body) {
        Form existing = owned(guild, formId);
        Form form = parse(guild, existing, body);
        List<String> problems = WebForms.problemsWith(form);
        problems.addAll(placementProblems(guild, form));
        if (!problems.isEmpty()) throw new ApiError(400, problems.getFirst(), problems);
        if (!repository.save(form)) throw new ApiError(404, Text.of(guild, "dashboard.that_form_is_gone_reload_the"));
        return list(guild);
    }

    DataObject remove(Guild guild, long formId) {
        owned(guild, formId);
        if (!repository.delete(guild.getIdLong(), formId)) throw new ApiError(404, Text.of(guild, "dashboard.that_form_is_gone_reload_the"));
        return list(guild);
    }

    // ---------- helpers ----------

    private Form owned(Guild guild, long formId) {
        return repository.get(formId).filter(f -> f.guildId() == guild.getIdLong()).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_form_does_not_exist")));
    }

    /** The form as the dashboard sent it, with a stable id for every question and nothing taken on trust. */
    private static Form parse(Guild guild, Form existing, DataObject body) {
        List<Question> questions = new ArrayList<>();
        if (body.hasKey("questions")) {
            DataArray raw = body.getArray("questions");
            for (int i = 0; i < raw.length() && i <= WebForms.MAX_QUESTIONS; i++) {
                DataObject q = raw.getObject(i);
                Kind kind = Kind.ofKey(q.getString("kind", "SHORT")).orElseThrow(() -> new ApiError(400, Text.of(guild, "dashboard.a_question_has_an_unknown_type")));
                List<String> options = new ArrayList<>();
                if (kind == Kind.CHOICE && q.hasKey("options")) {
                    DataArray rawOptions = q.getArray("options");
                    for (int j = 0; j < rawOptions.length() && j <= WebForms.MAX_OPTIONS; j++) options.add(rawOptions.getString(j).strip());
                }
                String id = q.getString("id", "").strip();
                if (!id.matches("[a-z0-9]{1,12}")) id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                int max = kind == Kind.SHORT || kind == Kind.RSN ? Math.min(q.getInt("maxLength", WebForms.DEFAULT_ANSWER_LENGTH), 200) : q.getInt("maxLength", WebForms.DEFAULT_ANSWER_LENGTH);
                if (kind == Kind.RSN) max = 12;
                if (kind == Kind.CHECKBOX) max = 3;
                questions.add(new Question(id, q.getString("label", "").strip(), kind, q.getBoolean("required", false), options, max));
            }
        } else {
            questions = existing.questions();
        }
        return new Form(existing.id(), guild.getIdLong(), body.getString("name", existing.name()).strip(), body.getString("title", existing.title()).strip(),
                body.getString("description", existing.description()).strip(), body.getBoolean("enabled", existing.enabled()),
                optionalId(body, "panelId", existing.panelId()), optionalId(body, "channelId", existing.channelId()), optionalId(body, "roleId", existing.roleId()),
                body.getBoolean("onePerUser", existing.onePerUser()), body.getInt("cooldownHours", existing.cooldownHours()), questions);
    }

    /** A snowflake from the body: absent keeps {@code current}, null or empty clears it. */
    private static Long optionalId(DataObject body, String key, Long current) {
        if (!body.hasKey(key)) return current;
        if (body.isNull(key)) return null;
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        return parseId(raw);
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, Text.en("dashboard.that_is_not_a_valid_id"));
        }
    }

    /** The panel, channel and role must really belong to this server (and the role must be one the bot may hand out). */
    private List<String> placementProblems(Guild guild, Form form) {
        List<String> problems = new ArrayList<>();
        if (form.panelId() != null) {
            Panel panel = tickets.getPanel(form.panelId());
            if (panel == null || panel.guildId() != guild.getIdLong()) problems.add("Choose a ticket panel from this server.");
        }
        if (form.channelId() != null && guild.getTextChannelById(form.channelId()) == null) problems.add("Choose a text channel from this server for the answers.");
        if (form.roleId() != null) {
            Role role = guild.getRoleById(form.roleId());
            if (role == null) problems.add("Choose a role from this server.");
            else if (!MemberApi.safeToAssign(guild, role)) problems.add("JonnyBot cannot hand out @" + role.getName() + ": it is above the bot's own role or carries powerful permissions.");
        }
        return problems;
    }

    static DataObject formJson(Form form) {
        DataArray questions = DataArray.empty();
        for (Question q : form.questions()) {
            DataArray options = DataArray.empty();
            q.options().forEach(options::add);
            questions.add(DataObject.empty().put("id", q.id()).put("label", q.label()).put("kind", q.kind().name()).put("required", q.required()).put("options", options).put("maxLength", q.maxLength()));
        }
        return DataObject.empty().put("id", Long.toString(form.id())).put("name", form.name()).put("title", form.title()).put("description", form.description()).put("enabled", form.enabled())
                .put("panelId", form.panelId() == null ? null : Long.toString(form.panelId())).put("channelId", form.channelId() == null ? null : Long.toString(form.channelId()))
                .put("roleId", form.roleId() == null ? null : Long.toString(form.roleId())).put("onePerUser", form.onePerUser()).put("cooldownHours", form.cooldownHours())
                .put("questions", questions);
    }
}
