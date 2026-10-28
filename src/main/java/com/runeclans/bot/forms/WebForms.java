package com.runeclans.bot.forms;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Web forms: a server builds a form on the dashboard, a signed-in Discord user fills it in on the website, and the answers open a ticket
 * and/or post to a channel. This file holds the shapes and every rule that needs no database or Discord, so it can be tested on its own.
 */
public final class WebForms {
    public static final int MAX_FORMS_PER_SERVER = 10;
    public static final int MAX_QUESTIONS = 15;
    public static final int MAX_OPTIONS = 25;
    public static final int MAX_LABEL = 80;
    public static final int MAX_NAME = 60;
    public static final int MAX_TITLE = 100;
    public static final int MAX_DESCRIPTION = 1000;
    public static final int DEFAULT_ANSWER_LENGTH = 300;
    public static final int MAX_ANSWER_LENGTH = 1000;
    public static final int MAX_COOLDOWN_HOURS = 24 * 365;

    private static final Pattern RSN = Pattern.compile("[A-Za-z0-9 _-]{1,12}");
    private static final Pattern QUESTION_ID = Pattern.compile("[a-z0-9]{1,12}");

    private WebForms() {}

    public enum Kind {
        SHORT, PARAGRAPH, CHOICE, CHECKBOX, RSN;

        public static Optional<Kind> ofKey(String key) {
            for (Kind kind : values()) if (kind.name().equalsIgnoreCase(key)) return Optional.of(kind);
            return Optional.empty();
        }
    }

    /** One question. {@code id} is stable across edits, so old submissions still line up; {@code options} only matters for CHOICE. */
    public record Question(String id, String label, Kind kind, boolean required, List<String> options, int maxLength) {}

    /**
     * A form as saved. {@code panelId} is the ticket panel a submission opens a ticket in, {@code channelId} where it is also posted and
     * {@code roleId} a role handed to the submitter (all optional). {@code cooldownHours} is the wait before the same person may submit again
     * (0 for none); {@code onePerUser} allows a single submission ever.
     */
    public record Form(long id, long guildId, String name, String title, String description, boolean enabled, Long panelId, Long channelId, Long roleId,
                       boolean onePerUser, int cooldownHours, List<Question> questions) {}

    public record Answer(String questionId, String label, String value) {}

    public record Submission(long id, long formId, long userId, Instant createdAt, List<Answer> answers, Long ticketId) {}

    // ---------- checking a form the owner is saving ----------

    /** Why a form can't be saved yet, each worded for the person editing it. Empty when it can. */
    public static List<String> problemsWith(Form form) {
        List<String> problems = new ArrayList<>();
        if (form.name().isBlank()) problems.add("Give the form a name.");
        if (form.name().length() > MAX_NAME) problems.add("Keep the name under " + MAX_NAME + " characters.");
        if (form.title().length() > MAX_TITLE) problems.add("Keep the title under " + MAX_TITLE + " characters.");
        if (form.description().length() > MAX_DESCRIPTION) problems.add("Keep the description under " + MAX_DESCRIPTION + " characters.");
        if (form.cooldownHours() < 0 || form.cooldownHours() > MAX_COOLDOWN_HOURS) problems.add("The wait between submissions must be 0 to " + MAX_COOLDOWN_HOURS + " hours.");
        if (form.questions().size() > MAX_QUESTIONS) problems.add("At most " + MAX_QUESTIONS + " questions.");
        if (form.enabled() && form.questions().isEmpty()) problems.add("Add at least one question before switching the form on.");
        if (form.enabled() && form.panelId() == null && form.channelId() == null) problems.add("Choose where answers go: a ticket panel, a channel, or both. Otherwise nobody would see them.");

        List<String> ids = new ArrayList<>();
        for (Question q : form.questions()) {
            if (q.label().isBlank()) problems.add("Every question needs its text.");
            if (q.label().length() > MAX_LABEL) problems.add("Keep each question under " + MAX_LABEL + " characters.");
            if (!QUESTION_ID.matcher(q.id()).matches() || ids.contains(q.id())) problems.add("A question has a bad or repeated id.");
            ids.add(q.id());
            if (q.kind() == Kind.CHOICE) {
                if (q.options().size() < 2) problems.add("\"" + q.label() + "\" needs at least two choices.");
                if (q.options().size() > MAX_OPTIONS) problems.add("\"" + q.label() + "\" can have at most " + MAX_OPTIONS + " choices.");
                if (q.options().stream().anyMatch(o -> o.isBlank() || o.length() > MAX_LABEL)) problems.add("\"" + q.label() + "\" has an empty or too long choice.");
            }
            if (q.maxLength() < 1 || q.maxLength() > MAX_ANSWER_LENGTH) problems.add("\"" + q.label() + "\" must allow 1 to " + MAX_ANSWER_LENGTH + " characters.");
        }
        return problems;
    }

    // ---------- checking a submission ----------

    /** The checked answers in question order, or the problems found (never both). Answers are keyed by question id. */
    public record Checked(List<Answer> answers, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static Checked check(Form form, Map<String, String> raw) {
        List<Answer> answers = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Question q : form.questions()) {
            String value = raw.getOrDefault(q.id(), "");
            value = value == null ? "" : value.strip().replaceAll("[\\p{Cntrl}&&[^\\n]]", "");
            switch (q.kind()) {
                case CHECKBOX -> {
                    boolean yes = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                    if (q.required() && !yes) problems.add("\"" + q.label() + "\" has to be ticked.");
                    value = yes ? "Yes" : "No";
                }
                case CHOICE -> {
                    final String picked = value;
                    if (picked.isEmpty()) {
                        if (q.required()) problems.add("Choose an answer for \"" + q.label() + "\".");
                    } else if (q.options().stream().noneMatch(o -> o.equals(picked))) {
                        problems.add("\"" + q.label() + "\": that is not one of the choices.");
                        value = "";
                    }
                }
                case RSN -> {
                    value = value.replaceAll("\\s+", " ");
                    if (value.isEmpty()) {
                        if (q.required()) problems.add("Enter your RuneScape name for \"" + q.label() + "\".");
                    } else if (!RSN.matcher(value).matches()) {
                        problems.add("RuneScape names are 1 to 12 letters, numbers, spaces, hyphens or underscores.");
                    }
                }
                default -> {
                    if (value.isEmpty() && q.required()) problems.add("Answer \"" + q.label() + "\".");
                    if (value.length() > q.maxLength()) problems.add("\"" + q.label() + "\" is longer than " + q.maxLength() + " characters.");
                }
            }
            answers.add(new Answer(q.id(), q.label(), value));
        }
        return problems.isEmpty() ? new Checked(answers, List.of()) : new Checked(List.of(), problems);
    }

    /**
     * Whether the person may submit now, given their last submission: {@code Optional.empty()} if yes, otherwise the reason. One-per-person
     * wins over the wait.
     */
    public static Optional<String> cannotSubmit(Form form, Instant lastSubmission, Instant now) {
        if (!form.enabled()) return Optional.of("This form is not taking answers right now.");
        if (lastSubmission == null) return Optional.empty();
        if (form.onePerUser()) return Optional.of("You have already filled this form in.");
        if (form.cooldownHours() > 0) {
            Instant next = lastSubmission.plus(Duration.ofHours(form.cooldownHours()));
            if (next.isAfter(now)) {
                long hours = Math.max(1, (Duration.between(now, next).toMinutes() + 59) / 60);
                return Optional.of("You can fill this form in again in about " + hours + (hours == 1 ? " hour." : " hours."));
            }
        }
        return Optional.empty();
    }

    /** The answers as text for a channel post, labelled; empty answers are shown as a dash. */
    public static Map<String, String> asLines(List<Answer> answers) {
        Map<String, String> lines = new LinkedHashMap<>();
        for (Answer a : answers) lines.put(a.label(), a.value().isBlank() ? "-" : a.value());
        return lines;
    }

    // ---------- answers sent from a clan site (T-590) ----------

    /** The most a clan site's application form sends (its widget's limit is the same as {@link #MAX_QUESTIONS}). */
    public static final int MAX_SITE_ANSWERS = MAX_QUESTIONS;

    /**
     * The form as a clan site's application form widget asked it (T-590): the widget has its own questions, so the answers keep the words the
     * site showed and every one is a paragraph the bot doesn't check again (the website did, with these same rules). Everything else (where
     * answers go, the wait between sends) is the form's. Labels are trimmed to {@link #MAX_LABEL}; blank ones and anything past
     * {@link #MAX_SITE_ANSWERS} are left out.
     */
    public static Form asSiteForm(Form form, List<String> labels) {
        List<Question> questions = new ArrayList<>();
        for (String raw : labels) {
            if (questions.size() >= MAX_SITE_ANSWERS) break;
            String label = raw == null ? "" : raw.strip().replaceAll("\\p{Cntrl}", "");
            if (label.isEmpty()) continue;
            if (label.length() > MAX_LABEL) label = label.substring(0, MAX_LABEL);
            questions.add(new Question("q" + (questions.size() + 1), label, Kind.PARAGRAPH, false, List.of(), MAX_ANSWER_LENGTH));
        }
        return new Form(form.id(), form.guildId(), form.name(), form.title(), form.description(), form.enabled(), form.panelId(), form.channelId(),
                form.roleId(), form.onePerUser(), form.cooldownHours(), questions);
    }

    // ---------- templates ----------

    /** A form to start from, switched off with nowhere for answers to go until the owner chooses. */
    public static Optional<Form> template(String key, long guildId) {
        if (!"clan-application".equals(key.toLowerCase(Locale.ROOT))) return Optional.empty();
        List<Question> questions = List.of(
                new Question("rsn", "RuneScape name", Kind.RSN, true, List.of(), 12),
                new Question("age", "How old are you?", Kind.SHORT, false, List.of(), 20),
                new Question("timezone", "Your timezone and when you usually play", Kind.SHORT, true, List.of(), 120),
                new Question("why", "Why do you want to join?", Kind.PARAGRAPH, true, List.of(), 600),
                new Question("clans", "Which clans are you in now, or have you been in?", Kind.PARAGRAPH, false, List.of(), 400),
                new Question("rules", "I have read the rules and agree to follow them", Kind.CHECKBOX, true, List.of(), 3));
        return Optional.of(new Form(0, guildId, "Clan application", "Apply to join", "Tell us a little about yourself. A member of the team will get back to you.", false,
                null, null, null, false, 168, questions));
    }
}
