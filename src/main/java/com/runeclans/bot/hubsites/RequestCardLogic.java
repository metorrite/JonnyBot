package com.runeclans.bot.hubsites;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.awt.Color;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * What a request card shows and what its buttons mean (T-734, runeclans-hub plan C-6, BUILD-A-BOT section 10). Pure: the hub's request
 * ({@code request.created} / {@code request.changed}, or a status route's answer) in, lines and buttons out, so tests need no Discord. The
 * next steps are the website's ({@code requestsLogic.ts} {@code nextSteps}), so a card never offers what the hub would refuse.
 */
final class RequestCardLogic {
    static final String PREFIX = "rcreq:";
    /** Discord's limit for all text in one message with components; the card stays well under it. */
    static final int MAX_TEXT = 3000;
    static final int MAX_ANSWERS = 10;
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final Pattern SNOWFLAKE = Pattern.compile("\\d{15,20}");
    private static final Color GREY = new Color(0x99AAB5);

    /** A button: its custom id, its words and whether it is the main one. */
    record Button(String id, String label, boolean primary) {}

    record Card(Color accent, List<String> lines, List<Button> buttons) {}

    /** What a press asks for: a status ({@code accepted}...) or {@code claim} / {@code unclaim}, on a request at the rev the card showed. */
    record Press(String action, String requestId, long rev) {
        boolean claim() {
            return action.equals("claim") || action.equals("unclaim");
        }
    }

    /**
     * What to do after the hub answered a press: show {@code card} (the request as it is now) and/or tell the presser privately
     * ({@code noteKey}, or the hub's own words in {@code hubMessage}).
     */
    record Outcome(DataObject card, String noteKey, String hubMessage) {}

    private RequestCardLogic() {}

    /** The hub route a press goes to. */
    static String pressPath(Press p) {
        return "/v1/requests/" + p.requestId() + (p.claim() ? "/assignee" : "/status");
    }

    /**
     * The body of a press: the change and who pressed. Administrator and owner come from Discord; the hub believes them only from RuneClans'
     * own bot and checks everything else (site admins, request handler roles) itself.
     */
    static DataObject pressBody(Press p, String guildId, String discordId, boolean administrator, boolean owner) {
        DataObject body = DataObject.empty().put("guildId", guildId);
        if (p.claim()) {
            if (p.action().equals("claim")) body.put("assignee", "me");
            else body.putNull("assignee");
        } else {
            body.put("status", p.action());
        }
        return body.put("by", DataObject.empty().put("discordId", discordId).put("administrator", administrator).put("owner", owner));
    }

    /** The hub's raw call with headers ({@link RuneClansHubClient#call(String, String, String, Map)}). */
    @FunctionalInterface
    interface HubCall {
        RuneClansHubClient.Answer call(String method, String path, String body, Map<String, String> headers);
    }

    /**
     * Sends a press to the hub, at the rev the card showed ({@code If-Match}), with who pressed and what Discord says about them, and words
     * the answer. A hub that can't be asked is "try again"; the hub decides who may press.
     */
    static Outcome press(Press p, String guildId, String discordId, boolean administrator, boolean owner, HubCall hub) {
        RuneClansHubClient.Answer a;
        try {
            a = hub.call("PUT", pressPath(p), pressBody(p, guildId, discordId, administrator, owner).toString(), Map.of("If-Match", Long.toString(p.rev())));
        } catch (RuntimeException e) {
            a = null;
        }
        return answered(a);
    }

    /** Words the hub's answer to a press: null (not asked) or a 5xx is "try again"; nothing is cached or retried. */
    static Outcome answered(RuneClansHubClient.Answer a) {
        if (a == null || a.status() >= 500 || a.status() == 429) return new Outcome(null, "requests.try_again", null);
        DataObject json = a.json();
        if (a.ok()) return new Outcome(json, null, null);
        if (a.status() == 409 && json != null && json.optObject("current").isPresent()) {
            return new Outcome(json.getObject("current"), "requests.changed_elsewhere", null);
        }
        String message = json == null ? null : json.getString("message", null);
        String error = json == null ? null : json.getString("error", null);
        if (a.status() == 403 && ("not_allowed".equals(error) || message == null || message.isBlank())) return new Outcome(null, "requests.not_allowed", null);
        if (a.status() == 404) return new Outcome(null, "requests.not_here", null);
        return message == null || message.isBlank() ? new Outcome(null, "requests.try_again", null) : new Outcome(null, null, clean(message, 300));
    }

    static String buttonId(String action, String requestId, long rev) {
        return PREFIX + action + ":" + requestId + ":" + rev;
    }

    /** A button id of ours, or null. */
    static Press parse(String customId) {
        if (customId == null || !customId.startsWith(PREFIX)) return null;
        String[] parts = customId.substring(PREFIX.length()).split(":");
        if (parts.length != 3 || !ID.matcher(parts[1]).matches()) return null;
        if (!List.of("accepted", "declined", "in_progress", "new", "closed", "claim", "unclaim").contains(parts[0])) return null;
        try {
            return new Press(parts[0], parts[1], Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether RuneClans itself closed a join request (the person left, joined or was removed), or it was closed before the board kept a
     * history: it stays closed, and the hub refuses the rest.
     */
    static boolean withdrawn(DataArray history) {
        for (int i = history.length() - 1; i >= 0; i--) {
            DataObject e = history.getObject(i);
            if (!"status".equals(e.getString("event", ""))) continue;
            return "hub".equals(e.getString("where", ""));
        }
        return true;
    }

    /** The statuses a request can move to next, in button order, each with its words' key. */
    static List<String[]> steps(String kind, String status, DataArray history) {
        boolean join = "join".equals(kind);
        String[] accept = {"accepted", join ? "requests.accept_member" : "requests.accept"};
        String[] decline = {"declined", "requests.decline"};
        String[] close = {"closed", "requests.close"};
        String[] reopen = {"new", "requests.reopen"};
        switch (status) {
            case "new":
                return List.<String[]>of(accept, decline, new String[] {"in_progress", "requests.in_progress"}, close);
            case "in_progress":
                return List.<String[]>of(accept, decline, new String[] {"new", "requests.back_to_new"}, close);
            case "accepted":
                return join ? List.<String[]>of(close) : List.<String[]>of(close, reopen);
            case "declined":
                return List.<String[]>of(close, reopen);
            case "closed": {
                boolean wasAccepted = false;
                for (int i = 0; i < history.length(); i++) if ("accepted".equals(history.getObject(i).getString("status", ""))) wasAccepted = true;
                return join && (withdrawn(history) || wasAccepted) ? List.<String[]>of() : List.<String[]>of(reopen);
            }
            default:
                return List.<String[]>of();
        }
    }

    /** The card for {@code request}, worded by {@code text} (the server's language). */
    static Card card(DataObject request, BiFunction<String, Object[], String> text) {
        String id = request.getString("id", "");
        String kind = request.getString("kind", "application");
        String status = request.getString("status", "new");
        long rev = request.getLong("rev", 1);
        DataArray history = request.optArray("history").orElse(DataArray.empty());
        List<String> lines = new ArrayList<>();
        lines.add("**" + text.apply("requests.kind." + kind, new Object[0]) + "** · " + text.apply("requests.status." + status, new Object[0]));
        String from = mention(request.getString("discordId", null));
        DataArray characters = request.optArray("characters").orElse(DataArray.empty());
        List<String> names = new ArrayList<>();
        for (int i = 0; i < characters.length() && i < 5; i++) names.add(clean(characters.getString(i, ""), 12));
        String name = clean(request.getString("name", ""), 80);
        String who = from != null ? from : !name.isBlank() ? name : text.apply("requests.someone", new Object[0]);
        lines.add(text.apply("requests.from", new Object[] {who}) + (names.isEmpty() ? "" : " (" + String.join(", ", names) + ")"));
        DataArray answers = request.optArray("answers").orElse(DataArray.empty());
        int used = String.join("\n", lines).length();
        for (int i = 0; i < answers.length() && i < MAX_ANSWERS; i++) {
            DataObject a = answers.getObject(i);
            String label = clean(a.getString("label", ""), 100);
            String value = clean(a.getString("value", ""), 500);
            if (value.isBlank()) continue;
            String line = "**" + (label.isBlank() ? "?" : label) + "**\n" + value;
            if (used + line.length() > MAX_TEXT) {
                lines.add(text.apply("requests.more_on_site", new Object[0]));
                break;
            }
            lines.add(line);
            used += line.length();
        }
        String assignee = request.optObject("assignee").map(o -> mention(o.getString("discordId", null))).orElse(null);
        if (assignee != null) lines.add(text.apply("requests.claimed_by", new Object[] {assignee}));
        String note = null;
        for (int i = history.length() - 1; i >= 0 && note == null; i--) {
            String n = history.getObject(i).getString("note", null);
            if (n != null && !n.isBlank()) note = clean(n, 300);
        }
        if (note != null) lines.add(text.apply("requests.last_note", new Object[] {note}));
        String changed = request.getString("updatedAt", request.getString("createdAt", null));
        Long at = epoch(changed);
        lines.add("-# " + text.apply("requests.footer", new Object[0]) + (at == null ? "" : " <t:" + at + ":R>"));
        List<Button> buttons = new ArrayList<>();
        if (ID.matcher(id).matches()) {
            for (String[] step : steps(kind, status, history)) {
                buttons.add(new Button(buttonId(step[0], id, rev), text.apply(step[1], new Object[0]), step[0].equals("accepted")));
            }
            if (status.equals("new") || status.equals("in_progress")) {
                buttons.add(assignee == null ? new Button(buttonId("claim", id, rev), text.apply("requests.claim", new Object[0]), false)
                        : new Button(buttonId("unclaim", id, rev), text.apply("requests.let_go", new Object[0]), false));
            }
        }
        return new Card(accent(status), lines, buttons);
    }

    static Color accent(String status) {
        return switch (status) {
            case "new" -> Containers.INFO;
            case "in_progress" -> Containers.PRIMARY;
            case "accepted" -> Containers.SUCCESS;
            case "declined" -> Containers.DANGER;
            default -> GREY;
        };
    }

    /** {@code <@id>} for a Discord id (posted with no mentions allowed, so nobody is pinged), else null. */
    static String mention(String discordId) {
        return discordId != null && SNOWFLAKE.matcher(discordId).matches() ? "<@" + discordId + ">" : null;
    }

    /** The person's own text: control characters out, markdown escaped, cut to {@code max}. */
    static String clean(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (s.length() > max) s = s.substring(0, max - 1) + "…";
        return MarkdownSanitizer.escape(s).replace("@", "@​");
    }

    private static Long epoch(String iso) {
        if (iso == null) return null;
        try {
            return Instant.parse(iso).getEpochSecond();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
