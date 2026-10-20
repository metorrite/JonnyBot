package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.forms.WebFormRepository;
import com.younglings.bot.forms.WebFormService;
import com.younglings.bot.forms.WebFormService.FormProblem;
import com.younglings.bot.forms.WebForms;
import com.younglings.bot.forms.WebForms.Form;
import com.younglings.bot.forms.WebForms.Question;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The public side of web forms, under {@code /internal/forms/}: not tied to the home server, so it is checked for the shared secret and takes the
 * server and the form from the address. The website asks on behalf of the person signed in with Discord and sends their id (it must come from
 * the verified session, never from the browser). Only a form its owner switched on is ever shown, and only its questions: where the answers go
 * (ticket panel, channel, role) is never revealed.
 * <ul>
 *   <li>{@code GET {guildId}/{formId}?userId=}: the form, and whether this person may send it now</li>
 *   <li>{@code POST {guildId}/{formId}/submit} {@code {userId, answers: {questionId: text}}}</li>
 * </ul>
 */
@BService
public class FormsPublicApi {
    private static final Logger log = LoggerFactory.getLogger(FormsPublicApi.class);
    private static final String PREFIX = "/internal/forms/";
    private static final int MAX_BODY = 64 * 1024;

    private final WebFormRepository repository;
    private final WebFormService service;
    // Per person: filling a form in is slow, so a few attempts a minute is plenty, and a sustained flood is refused.
    private final RateLimiter submits = new RateLimiter(3, 20_000);
    private final RateLimiter reads = new RateLimiter(30, 1_000);

    public FormsPublicApi(WebFormRepository repository, WebFormService service) {
        this.repository = repository;
        this.service = service;
    }

    /** Called after the shared secret has been checked. */
    public void handle(HttpExchange exchange, JDA jda) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String[] parts = (path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "").split("/");
            String method = exchange.getRequestMethod().toUpperCase();
            if (parts.length < 2 || parts.length > 3) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            long guildId = parseId(parts[0]);
            long formId = parseId(parts[1]);
            Guild guild = jda.getGuildById(guildId);
            Form form = guild == null ? null : repository.get(formId).filter(f -> f.guildId() == guildId && f.enabled()).orElse(null);
            if (guild == null || form == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "That form does not exist or is closed."));
                return;
            }

            if (parts.length == 2) {
                if (!method.equals("GET")) {
                    InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                    return;
                }
                long userId = optionalUser(exchange.getRequestURI().getRawQuery());
                if (userId != 0 && !reads.tryAcquire(Long.toString(userId))) throw new FormProblem(429, "Slow down a moment.");
                InternalApiServer.sendJson(exchange, 200, definition(guild, form, userId));
                return;
            }
            if (!parts[2].equals("submit") || !method.equals("POST")) {
                InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }

            DataObject body = DataObject.fromJson(new String(exchange.getRequestBody().readNBytes(MAX_BODY), StandardCharsets.UTF_8));
            long userId = parseId(body.getString("userId", ""));
            if (!submits.tryAcquire(Long.toString(userId))) throw new FormProblem(429, "You are sending that too fast. Wait a moment and try again.");
            Map<String, String> answers = new HashMap<>();
            if (body.hasKey("answers")) {
                DataObject raw = body.getObject("answers");
                for (String key : raw.keys()) answers.put(key, raw.get(key) == null ? "" : String.valueOf(raw.get(key)));
            }
            var outcome = service.submit(guild, userId, formId, answers);
            DataArray notes = DataArray.empty();
            outcome.notes().forEach(notes::add);
            InternalApiServer.sendJson(exchange, 200, DataObject.empty().put("ok", true).put("ticketOpened", outcome.ticketOpened()).put("notes", notes));
        } catch (FormProblem e) {
            DataObject error = DataObject.empty().put("error", e.getMessage());
            if (!e.problems().isEmpty()) {
                DataArray problems = DataArray.empty();
                e.problems().forEach(problems::add);
                error.put("problems", problems);
            }
            InternalApiServer.sendJson(exchange, e.status(), error);
        } catch (IllegalArgumentException e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", "That request could not be read."));
        } catch (Exception e) {
            log.error("Forms API request {} failed", exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    private DataObject definition(Guild guild, Form form, long userId) {
        DataArray questions = DataArray.empty();
        for (Question q : form.questions()) {
            DataArray options = DataArray.empty();
            q.options().forEach(options::add);
            questions.add(DataObject.empty().put("id", q.id()).put("label", q.label()).put("kind", q.kind().name()).put("required", q.required()).put("options", options).put("maxLength", q.maxLength()));
        }
        DataObject json = DataObject.empty().put("id", Long.toString(form.id())).put("guildId", guild.getId()).put("guildName", guild.getName()).put("guildIconUrl", guild.getIconUrl())
                .put("title", form.title().isBlank() ? form.name() : form.title()).put("description", form.description()).put("questions", questions);
        if (userId != 0) {
            boolean inServer = guild.getMemberById(userId) != null || memberExists(guild, userId);
            Optional<String> refusal = inServer ? WebForms.cannotSubmit(form, repository.lastSubmission(form.id(), userId), Instant.now()) : Optional.of("Join the Discord server first, then send this form.");
            json.put("canSubmit", refusal.isEmpty()).put("reason", refusal.orElse(null));
        }
        return json;
    }

    private static boolean memberExists(Guild guild, long userId) {
        try {
            return guild.retrieveMemberById(userId).complete() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long optionalUser(String rawQuery) {
        if (rawQuery == null) return 0;
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith("userId=")) return parseId(pair.substring("userId=".length()));
        }
        return 0;
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,20}")) throw new FormProblem(400, "That is not a valid id.");
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new FormProblem(400, "That is not a valid id.");
        }
    }
}
