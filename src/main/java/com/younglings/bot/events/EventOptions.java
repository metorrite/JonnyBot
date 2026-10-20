package com.younglings.bot.events;

import com.younglings.bot.events.EventRules.Scope;
import com.younglings.bot.events.EventRules.ScoringMode;
import com.younglings.bot.events.EventService.EventInput;
import com.younglings.bot.events.PrizeRules.PrizeKind;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every option an event can be set up with, in one place: what it is called, where it lives in the event's settings, what the standard value is,
 * whether it is required (always, or only when something else is chosen), and which stage of the guided setup it belongs to. The quick, standard and
 * guided setups, saved templates and drafts all read this, so they can never disagree about what "standard" means or what is missing, and an option
 * added here (by a template or by a later feature) shows up in the guided setup with no other edit.
 * <p>
 * The answers to the options are one JSON object shaped exactly like the body that creates an event: {@code name}, {@code description},
 * {@code template}, {@code scope}, {@code scoringMode}, {@code startsAt}, {@code endsAt}, {@code maxParticipants} and the template's and the event's
 * {@code config}, plus {@code prizes}, which are saved once the event exists. An option's {@code path} is where its answer lives in that object
 * ({@code config.buyIn.amount}). Pure, so it can be tested.
 */
public final class EventOptions {
    /** The stages of the guided setup, in order. Options of a stage are shown together. */
    public enum Step {
        BASICS("The basics", "Name the event and say what it is."),
        WHEN("When", "When it starts and ends, and when signups close."),
        RULES("The rules", "What the event is made of."),
        PLAYERS("Players", "Who can take part and how they are scored."),
        MONEY("Buy-in and prizes", "What players pay to enter and what they can win."),
        DISCORD("Discord", "Where signups, the board and proof go."),
        SETUP("Setup steps and verification", "How much of the setup checklist the event follows, and whether players must be verified.");

        public final String label;
        public final String blurb;

        Step(String label, String blurb) {
            this.label = label;
            this.blurb = blurb;
        }
    }

    /** How an option is asked for: free text, a number, a switch, one of a list, a date and time, a Discord channel, or the list of prizes. */
    public enum Type { TEXT, TEXTAREA, INT, BOOL, CHOICE, DATETIME, CHANNEL, PRIZES }

    /** One of the values a choice can have. */
    public record Choice(String value, String label, String hint) {
        public Choice(String value, String label) {
            this(value, label, "");
        }
    }

    /**
     * A condition on another answer: it is set (any value but blank), or it has this value; {@code negate} turns it round. The only kind of condition the
     * setup streams need, which is what lets the website evaluate it the same way as the bot.
     */
    public record When(String path, Object value, boolean negate) {
        public static When is(String path, Object value) {
            return new When(path, value, false);
        }

        public static When isNot(String path, Object value) {
            return new When(path, value, true);
        }

        public static When isSet(String path) {
            return new When(path, null, false);
        }

        public boolean holds(DataObject answers) {
            Object actual = get(answers, path);
            boolean matches;
            if (value == null) {
                matches = actual != null && !(actual instanceof String s && s.isBlank());
            } else if (value instanceof Boolean b) {
                matches = b.equals(actual);
            } else if (value instanceof Number n) {
                matches = actual instanceof Number a ? a.longValue() == n.longValue() : actual instanceof String s && s.strip().equals(String.valueOf(n.longValue()));
            } else {
                matches = actual != null && String.valueOf(actual).equalsIgnoreCase(String.valueOf(value));
            }
            return negate != matches;
        }

        DataObject json() {
            DataObject o = DataObject.empty().put("path", path).put("negate", negate);
            if (value == null) o.put("set", true);
            else o.put("value", value);
            return o;
        }
    }

    /** Whether an option must be answered: never, always, or only when another answer says so. */
    public record Requirement(boolean always, When when) {
        public static final Requirement NEVER = new Requirement(false, null);
        public static final Requirement ALWAYS = new Requirement(true, null);

        public static Requirement when(When when) {
            return new Requirement(false, when);
        }

        boolean applies(DataObject answers) {
            return always || (when != null && when.holds(answers));
        }
    }

    /**
     * One option. {@code standard} is its standard value (null: none, so it is asked for or left empty); {@code quick} marks the few asked in the quick setup;
     * {@code widget} names a special control the website uses instead of the plain one for its type ({@code look}, {@code shape}, {@code skill}).
     * {@code numeric} says the stored value is a number even for a choice.
     */
    public record Option(String path, String label, String hint, Step step, Type type, String widget, Object standard, Requirement required, When showWhen,
                         List<Choice> choices, Long min, Long max, Integer maxLength, boolean quick, boolean numeric) {
        public Option {
            choices = choices == null ? List.of() : List.copyOf(choices);
            required = required == null ? Requirement.NEVER : required;
        }

        public static Option of(String path, String label, String hint, Step step, Type type, Object standard) {
            return new Option(path, label, hint, step, type, null, standard, Requirement.NEVER, null, List.of(), null, null, null, false, false);
        }

        public static Option choice(String path, String label, String hint, Step step, Object standard, List<Choice> choices) {
            return new Option(path, label, hint, step, Type.CHOICE, null, standard, Requirement.NEVER, null, choices, null, null, null, false, false);
        }

        public static Option number(String path, String label, String hint, Step step, long standard, long min, long max) {
            return new Option(path, label, hint, step, Type.INT, null, standard, Requirement.NEVER, null, List.of(), min, max, null, false, true);
        }

        public Option mandatory() {
            return new Option(path, label, hint, step, type, widget, standard, Requirement.ALWAYS, showWhen, choices, min, max, maxLength, quick, numeric);
        }

        public Option requiredWhen(When when) {
            return new Option(path, label, hint, step, type, widget, standard, Requirement.when(when), showWhen, choices, min, max, maxLength, quick, numeric);
        }

        public Option showWhen(When when) {
            return new Option(path, label, hint, step, type, widget, standard, required, when, choices, min, max, maxLength, quick, numeric);
        }

        public Option inQuick() {
            return new Option(path, label, hint, step, type, widget, standard, required, showWhen, choices, min, max, maxLength, true, numeric);
        }

        public Option widget(String widget) {
            return new Option(path, label, hint, step, type, widget, standard, required, showWhen, choices, min, max, maxLength, quick, numeric);
        }

        public Option maxLength(int maxLength) {
            return new Option(path, label, hint, step, type, widget, standard, required, showWhen, choices, min, max, maxLength, quick, numeric);
        }

        public Option numericChoices() {
            return new Option(path, label, hint, step, type, widget, standard, required, showWhen, choices, min, max, maxLength, quick, true);
        }

        public Option minimum(long min) {
            return new Option(path, label, hint, step, type, widget, standard, required, showWhen, choices, min, max, maxLength, quick, numeric);
        }

        boolean shown(DataObject answers) {
            return showWhen == null || showWhen.holds(answers);
        }

        DataObject json() {
            DataArray choiceArray = DataArray.empty();
            choices.forEach(c -> choiceArray.add(DataObject.empty().put("value", c.value()).put("label", c.label()).put("hint", c.hint())));
            DataObject req = DataObject.empty().put("always", required.always());
            if (required.when() != null) req.put("when", required.when().json());
            return DataObject.empty().put("path", path).put("label", label).put("hint", hint).put("step", step.name()).put("type", type.name()).put("widget", widget)
                    .put("standard", standard).put("required", req).put("showWhen", showWhen == null ? null : showWhen.json()).put("choices", choiceArray)
                    .put("min", min).put("max", max).put("maxLength", maxLength).put("quick", quick).put("numeric", numeric);
        }
    }

    /** An answer an option needs that is missing, with a sentence to show. */
    public record Missing(String path, String label, Step step, String message) {}

    /** What a new event of the standard kind starts a day from now and runs for a week, as the Discord quick create has always done. */
    public static final int STANDARD_STARTS_IN_HOURS = 24;
    public static final int STANDARD_DAYS = 7;

    private EventOptions() {}

    // ---------- the options every event has ----------

    private static List<Option> common() {
        List<Option> list = new ArrayList<>();
        list.add(Option.of("name", "Name", "What players see, at least " + EventRules.MIN_NAME + " characters.", Step.BASICS, Type.TEXT, null).mandatory().inQuick().maxLength(EventRules.MAX_NAME));
        list.add(Option.of("description", "Description", "Optional. Shown to players on the event's page.", Step.BASICS, Type.TEXTAREA, "").maxLength(EventRules.MAX_DESCRIPTION));

        list.add(Option.of("startsAt", "Starts", "When it is planned to start. Nothing starts by itself: an organiser starts the event.", Step.WHEN, Type.DATETIME, null).inQuick());
        list.add(Option.of("endsAt", "Ends", "When it is planned to end.", Step.WHEN, Type.DATETIME, null).inQuick());
        list.add(Option.of("config.signupClosesAt", "Signups close", "Optional. After this players can't join themselves and signups close; staff can still add late entries.", Step.WHEN, Type.DATETIME, null));

        list.add(Option.choice("scoringMode", "Who plays", "Teams add up points per team.", Step.PLAYERS, ScoringMode.INDIVIDUAL.name(),
                List.of(new Choice("INDIVIDUAL", "Solo players"), new Choice("TEAM", "Teams"))));
        list.add(Option.number("maxParticipants", "Most players", "0 for no limit. A full event gets a waitlist.", Step.PLAYERS, 0, 0, EventRules.MAX_PARTICIPANTS));
        list.add(Option.choice("scope", "Who can see it", "Only your clan sees it by default.", Step.PLAYERS, Scope.PRIVATE.name(),
                List.of(new Choice("PRIVATE", "Clan only"), new Choice("ALLIANCE", "Clan and allies"), new Choice("PUBLIC", "Everyone"))));
        list.add(Option.choice("config.standings.visibility", "Who sees the standings", "Staff always see them. Players always see their own points.", Step.PLAYERS, EventStandings.DEFAULT.name(),
                List.of(new Choice("PARTICIPANTS", "Everyone taking part"), new Choice("SERVER", "Everyone in the server"), new Choice("STAFF", "Staff only"),
                        new Choice("HIDDEN_UNTIL_END", "Hidden until the event ends"))));
        list.add(Option.choice("config.teamPolicy", "Who may join and share a team", "Decides whether clan members, other members of this server and players from other servers may take part and play together.", Step.PLAYERS,
                EventSettings.DEFAULT_POLICY, List.of(new Choice("everyone", "Everyone, mixed freely"), new Choice("home", "Only this server"),
                        new Choice("clanVsOutsiders", "Clan members apart from everyone else"), new Choice("separate", "Every kind on its own teams"))));
        list.add(Option.choice("config.queue", "When the event is full", "Who gets a place when someone leaves.", Step.PLAYERS, "FIRST_COME",
                List.of(new Choice("FIRST_COME", "First come, first served"), new Choice("CLAN_FIRST", "Clan members first"))));

        list.add(Option.of("config.buyIn.enabled", "Buy-in", "Players pay in-game gp to enter, and the paid total makes the pot.", Step.MONEY, Type.BOOL, false));
        list.add(Option.number("config.buyIn.amount", "Buy-in amount (gp)", "What each player pays.", Step.MONEY, 0, 1, 1_000_000_000_000L)
                .requiredWhen(When.is("config.buyIn.enabled", true)).showWhen(When.is("config.buyIn.enabled", true)).minimum(1));
        list.add(Option.of("config.buyIn.showPot", "Show the pot to players", "Players see how much is in the pot.", Step.MONEY, Type.BOOL, true).showWhen(When.is("config.buyIn.enabled", true)));
        list.add(Option.of("prizes", "Prizes", "A prize goes to one place or a range of places. Optional; you can also add them later.", Step.MONEY, Type.PRIZES, null));

        list.add(Option.of("config.signupChannelId", "Signup channel", "Where the signup is posted for players. Choose it together with the staff channel.", Step.DISCORD, Type.CHANNEL, null)
                .requiredWhen(When.isSet("config.signupAdminChannelId")));
        list.add(Option.of("config.signupAdminChannelId", "Signup staff channel", "Where staff see who signed up.", Step.DISCORD, Type.CHANNEL, null)
                .requiredWhen(When.isSet("config.signupChannelId")));

        list.add(Option.choice("config.setup.mode", "Setup steps", "Follow the steps (close signups, confirm the roster) before the event can start, or start it whenever you like.", Step.SETUP, "STANDARD",
                List.of(new Choice("STANDARD", "Follow the setup steps"), new Choice("OFF", "Start whenever I like"))));
        list.add(Option.of("config.verification.required", "Players must be verified", "Players send proof first (for example a screenshot of what they hold) and an organiser approves it.", Step.SETUP, Type.BOOL, false));
        list.add(Option.choice("config.verification.gate", "Must be verified by", "When a player who isn't verified stops counting.", Step.SETUP, "TEAMS",
                List.of(new Choice("SIGNUP", "When they sign up", "They count once approved. Until then they are on the list but not in the event."),
                        new Choice("TEAMS", "Before teams are made (usual)", "Anyone can sign up; players who aren't verified by then are left out of the teams unless you let them through."),
                        new Choice("START", "Before the event starts", "Everyone can be in a team, but the event can't start until every player is verified, waived or removed.")))
                .showWhen(When.is("config.verification.required", true)));
        list.add(Option.of("config.verification.instructions", "What players are told to send", "Shown to players who join. Up to " + EventRoster.MAX_INSTRUCTIONS + " characters.", Step.SETUP, Type.TEXTAREA, "")
                .showWhen(When.is("config.verification.required", true)).maxLength(EventRoster.MAX_INSTRUCTIONS));
        list.add(Option.of("config.channels.enabled", "Let JonnyBot make the event's roles and channels", "A category with the event's channels, and a role for the players, made when you press Set up after signups close. Fine-tune names and channels below, or later on the Channels tab.", Step.DISCORD, Type.BOOL, true));
        list.add(Option.of("config.channels.teamChannels", "A private channel for each team", "Only that team, the organisers and staff can see it.", Step.DISCORD, Type.BOOL, true)
                .showWhen(When.is("scoringMode", "TEAM")));
        list.add(Option.of("config.channels.playerChannels", "A private channel for each player", "For events where everyone plays alone. Only that player, the organisers and staff can see it.", Step.DISCORD, Type.BOOL, false)
                .showWhen(When.is("scoringMode", "INDIVIDUAL")));
        list.add(Option.of("config.submissions.require", "Proof only from the event's channels", "Players must send proof with /submit in their own event channel. Staff and organisers can still send from anywhere.", Step.DISCORD, Type.BOOL, false));
        list.add(Option.of("config.submissions.receipts", "Post a receipt in the player's channel", "Each submission also shows in the player's (or team's) own channel and is updated when staff decide.", Step.DISCORD, Type.BOOL, true));
        list.add(Option.of("config.submissions.remind", "Remind players who post a picture without /submit", "When a player posts a picture in their own event channel without using /submit, JonnyBot answers once an hour with how to send it properly. Needs the Message Content intent; staff and organisers are never reminded.", Step.DISCORD, Type.BOOL, false));
        list.add(Option.of("config.wrapup.lockOnFinish", "Make the channels read-only when the event ends", "Players can still read the channels after the event but not write in them.", Step.DISCORD, Type.BOOL, true));
        list.add(Option.number("config.wrapup.rolesDays", "Take the event roles back after (days)", "Days after the event ends. 0 is straight away, -1 keeps them until an organiser removes them.", Step.DISCORD, 7, -1, EventWrapup.MAX_DAYS));
        list.add(Option.number("config.wrapup.channelsDays", "Deal with the channels after (days)", "Days after the event ends. -1 leaves them until an organiser archives, compresses, exports or deletes them.", Step.DISCORD, -1, -1, EventWrapup.MAX_DAYS));
        list.add(Option.choice("config.wrapup.channelsAction", "Then the channels are", "What happens when that many days have passed.", Step.DISCORD, "ARCHIVE",
                List.of(new Choice("ARCHIVE", "Archived", "Moved into an archive only staff can see."), new Choice("DELETE", "Deleted", "Gone for good. Export first if you want the messages."), new Choice("NONE", "Left alone", "Nothing happens by itself.")))
                .showWhen(When.isSet("config.wrapup.channelsDays")));
        return list;
    }

    /**
     * How one of the event's single channels (the live board, the proof to review) is provided: JonnyBot makes it (with a name pattern), an existing channel
     * is used, or it is left out. These are the same choices as on the Channels tab ({@code config.channels.slots} and {@code names}), so there is one
     * place for them. Only asked when JonnyBot is set to make the event's roles and channels.
     */
    static List<Option> slotOptions(EventSpace.Kind kind, String about) {
        String path = "config.channels.slots." + kind.key();
        When on = When.is("config.channels.enabled", true);
        When existing = When.is(path + ".use", "existing");
        When create = When.is(path + ".use", "create");
        return List.of(
                Option.choice(path + ".use", kind.label(), about, Step.DISCORD, "create",
                        List.of(new Choice("create", "JonnyBot makes it", "Named from the pattern below, inside the event's category."), new Choice("existing", "Use a channel I already have", "Pick it from the list."),
                                new Choice("none", "Leave it out", "")))
                        .showWhen(on),
                Option.of(path + ".channelId", kind.label() + " channel", "The channel to use.", Step.DISCORD, Type.CHANNEL, null).showWhen(existing).requiredWhen(existing),
                Option.of("config.channels.names." + kind.key(), kind.label() + " name", "{event} is the event's name. Leave it empty for the usual: " + kind.defaultPattern() + ".", Step.DISCORD, Type.TEXT, null)
                        .showWhen(create).maxLength(100));
    }

    /** Every option for an event of {@code template}: the ones every event has and the template's own, by stage and then in the order declared. */
    public static List<Option> of(EventTemplate template) {
        List<Option> all = new ArrayList<>(common());
        all.addAll(template.options());
        // the live board and the proof to review: made by JonnyBot, an existing channel or none, the same choices as the Channels tab
        if (EventSpace.Kind.BOARD.appliesTo(template.key())) all.addAll(slotOptions(EventSpace.Kind.BOARD, "The bingo's board picture, posted when the event starts and kept up to date."));
        if (EventSpace.Kind.REVIEW.appliesTo(template.key())) all.addAll(slotOptions(EventSpace.Kind.REVIEW, "Where players' proof lands for staff to approve. Players send it with /submit."));
        all.sort(Comparator.comparingInt(o -> o.step().ordinal()));
        return all;
    }

    // ---------- reading and writing answers by path ----------

    /** The value at {@code path} ({@code config.buyIn.amount}) in {@code root}, or null when any part of the path is missing. */
    public static Object get(DataObject root, String path) {
        DataObject current = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (current == null || !current.hasKey(parts[i]) || current.isNull(parts[i])) return null;
            Object next = current.get(parts[i]);
            if (i == parts.length - 1) return next;
            if (!(next instanceof Map)) return null;
            current = current.getObject(parts[i]);
        }
        return null;
    }

    /** Puts {@code value} at {@code path} in {@code root}, creating the objects on the way. A null value removes it. */
    public static void set(DataObject root, String path, Object value) {
        String[] parts = path.split("\\.");
        DataObject current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!current.hasKey(parts[i]) || !(current.get(parts[i]) instanceof Map)) {
                if (value == null) return; // nothing to remove, and nothing to create for it
                current.put(parts[i], DataObject.empty());
            }
            current = current.getObject(parts[i]);
        }
        if (value == null) current.remove(parts[parts.length - 1]);
        else current.put(parts[parts.length - 1], value);
    }

    private static DataObject copy(DataObject o) {
        return o == null ? DataObject.empty() : DataObject.fromJson(o.toString());
    }

    // ---------- standard answers ----------

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** A name for an event nobody has named yet: the kind and the day it starts, like "Bingo · 18 Oct". */
    public static String standardName(String label, Instant startsAt) {
        return label + " · " + DAY.format(startsAt);
    }

    /**
     * The standard answers for an event of {@code template} made at {@code now}: every option at its standard value, a name made from the kind and the start
     * day, and the start a day from now running for a week. What is still empty (a boss night's boss) is what {@link #missing} reports.
     */
    public static DataObject standard(EventTemplate template, Instant now) {
        return standard(template, now, EventDefaults.NONE);
    }

    /** As above, with the server's own defaults ({@link EventDefaults}) laid over the built-in ones. */
    public static DataObject standard(EventTemplate template, Instant now, EventDefaults defaults) {
        DataObject answers = DataObject.empty().put("template", template.key());
        for (Option o : of(template)) {
            if (o.standard() != null) set(answers, o.path(), o.standard());
        }
        Instant starts = now.plus(Duration.ofHours(STANDARD_STARTS_IN_HOURS));
        answers.put("startsAt", starts.toString()).put("endsAt", starts.plus(Duration.ofDays(STANDARD_DAYS)).toString());
        answers.put("name", standardName(template.label(), starts));
        (defaults == null ? EventDefaults.NONE : defaults).applyTo(answers, template);
        return answers;
    }

    // ---------- what is missing ----------

    private static boolean filled(Option o, Object value) {
        if (value == null) return false;
        return switch (o.type()) {
            case INT -> {
                long n = value instanceof Number num ? num.longValue() : value instanceof String s && s.strip().matches("-?\\d{1,18}") ? Long.parseLong(s.strip()) : Long.MIN_VALUE;
                yield n != Long.MIN_VALUE && (o.min() == null || n >= o.min());
            }
            case BOOL -> value instanceof Boolean;
            case PRIZES -> value instanceof List<?> l && !l.isEmpty();
            case CHOICE -> o.numeric() ? value instanceof Number : !String.valueOf(value).isBlank();
            default -> !String.valueOf(value).isBlank();
        };
    }

    /** The required options that have no answer yet, in the order of the guided setup. A required option that isn't shown (its condition doesn't hold) isn't asked, so isn't missing. */
    public static List<Missing> missing(List<Option> options, DataObject answers) {
        List<Missing> missing = new ArrayList<>();
        for (Option o : options) {
            if (!o.required().applies(answers) || !o.shown(answers)) continue;
            Object value = get(answers, o.path());
            if (!filled(o, value)) missing.add(new Missing(o.path(), o.label(), o.step(), missingMessage(o)));
        }
        return missing;
    }

    private static String missingMessage(Option o) {
        return switch (o.path()) {
            case "name" -> "Give the event a name of at least " + EventRules.MIN_NAME + " characters.";
            case "config.buyIn.amount" -> "Say how much the buy-in is.";
            case "config.signupChannelId", "config.signupAdminChannelId" -> "A signup needs both a public channel and a staff channel, or neither.";
            default -> "Fill in \"" + o.label() + "\".";
        };
    }

    // ---------- from answers to an event ----------

    /**
     * The answers cleaned up so the engine accepts them: the grid is the absence of a shape (and has a size, a shape has none), "automatic" tile order is
     * left unsaid, blank channels and dates are dropped, a buy-in that isn't switched on isn't stored, and values are in their stored forms. The input is
     * not changed.
     */
    public static DataObject normalize(DataObject answers) {
        DataObject a = copy(answers);
        String layout = get(a, "config.board.layout") == null ? "" : String.valueOf(get(a, "config.board.layout")).strip();
        if (layout.isEmpty() || layout.equalsIgnoreCase("grid")) {
            set(a, "config.board.layout", null);
            set(a, "config.board.unlock", null);
        } else {
            set(a, "config.board.layout", layout);
            set(a, "config.board.size", null);
            Object unlock = get(a, "config.board.unlock");
            if (unlock == null || String.valueOf(unlock).isBlank() || String.valueOf(unlock).equalsIgnoreCase("auto")) set(a, "config.board.unlock", null);
        }
        for (String blank : new String[]{"config.signupChannelId", "config.signupAdminChannelId", "config.boardChannelId", "config.reviewChannelId", "config.signupClosesAt", "startsAt", "endsAt"}) {
            Object v = get(a, blank);
            if (v == null || String.valueOf(v).isBlank()) set(a, blank, null);
        }
        if (!Boolean.TRUE.equals(get(a, "config.buyIn.enabled"))) set(a, "config.buyIn", null);
        // roles and channels that aren't switched on need nothing stored
        if (!Boolean.TRUE.equals(get(a, "config.channels.enabled"))) set(a, "config.channels", null);
        if (a.hasKey("config") && !a.isNull("config")) {
            DataObject config = a.getObject("config");
            for (String key : List.copyOf(config.keys())) {
                if (config.isNull(key)) config.remove(key);
            }
        }
        for (String upper : new String[]{"scope", "scoringMode"}) {
            Object v = get(a, upper);
            if (v != null) set(a, upper, String.valueOf(v).strip().toUpperCase(Locale.ROOT));
        }
        Object name = get(a, "name");
        if (name != null) set(a, "name", String.valueOf(name).strip());
        return a;
    }

    private static Instant instant(DataObject a, String path, String what) {
        Object v = get(a, path);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return Instant.parse(String.valueOf(v).strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(what + " has to be a date and time.");
        }
    }

    /** The event input the normalized answers describe. Throws {@link IllegalArgumentException} with a sentence when a value can't be read. */
    public static EventInput toInput(DataObject normalized) {
        Scope scope;
        ScoringMode mode;
        try {
            scope = Scope.valueOf(get(normalized, "scope") == null ? "PRIVATE" : String.valueOf(get(normalized, "scope")));
            mode = ScoringMode.valueOf(get(normalized, "scoringMode") == null ? "INDIVIDUAL" : String.valueOf(get(normalized, "scoringMode")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Choose who may see the event (clan only, clan and allies, or everyone) and how it is scored (solo or teams).");
        }
        Object max = get(normalized, "maxParticipants");
        int maxParticipants;
        try {
            maxParticipants = max == null || String.valueOf(max).isBlank() ? 0 : max instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(max).strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("The most players has to be a number.");
        }
        Object name = get(normalized, "name");
        Object description = get(normalized, "description");
        Object template = get(normalized, "template");
        DataObject config = normalized.hasKey("config") && !normalized.isNull("config") ? copy(normalized.getObject("config")) : DataObject.empty();
        return new EventInput(name == null ? "" : String.valueOf(name), description == null ? "" : String.valueOf(description), template == null ? "custom" : String.valueOf(template),
                scope, mode, instant(normalized, "startsAt", "The start"), instant(normalized, "endsAt", "The end"), maxParticipants, config);
    }

    /** The prizes in the answers (an empty list when there are none), with what is wrong with them reported by {@link PrizePlan#problems}. */
    public static List<PrizePlan.Prize> prizesOf(DataObject answers) {
        List<PrizePlan.Prize> prizes = new ArrayList<>();
        if (!answers.hasKey("prizes") || answers.isNull("prizes")) return prizes;
        DataArray array = answers.getArray("prizes");
        for (int i = 0; i < array.length(); i++) {
            DataObject p = array.getObject(i);
            PrizeKind kind;
            PrizePlan.Split split;
            try {
                kind = PrizeKind.valueOf(p.getString("kind", "IN_GAME").strip().toUpperCase(Locale.ROOT));
                split = PrizePlan.Split.valueOf(p.getString("split", "EQUAL").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Choose the prize kind (in game, clan points or other) and how a team shares it (equal, each or captain).");
            }
            int place = p.getInt("place", 1);
            prizes.add(new PrizePlan.Prize(0, place, p.getInt("placeTo", place), kind, p.getString("description", ""), p.getLong("amount", 0), p.getInt("percentOfPot", 0), split));
        }
        return prizes;
    }

    // ---------- the schema the website renders ----------

    /** The step list, the options and the standard answers for {@code template}, as the website needs them to draw the setup. */
    public static DataObject schema(EventTemplate template, Instant now) {
        return schema(template, now, EventDefaults.NONE);
    }

    /** As above, with the server's own defaults in the standard answers. */
    public static DataObject schema(EventTemplate template, Instant now, EventDefaults defaults) {
        DataArray steps = DataArray.empty();
        for (Step s : Step.values()) steps.add(DataObject.empty().put("key", s.name()).put("label", s.label).put("blurb", s.blurb));
        DataArray options = DataArray.empty();
        of(template).forEach(o -> options.add(o.json()));
        return DataObject.empty().put("template", template.key()).put("label", template.label()).put("description", template.description()).put("steps", steps).put("options", options)
                .put("standard", standard(template, now, defaults)).put("startsInHours", STANDARD_STARTS_IN_HOURS).put("days", STANDARD_DAYS);
    }
}
