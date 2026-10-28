package com.runeclans.bot.events;

import com.runeclans.bot.events.EventSpace.Grant;
import com.runeclans.bot.events.EventSpace.Level;
import com.runeclans.bot.events.EventSpace.Principal;
import com.runeclans.bot.events.EventSpace.Who;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rules of wrapping an event up, free of Discord so they can be tested: the settings ({@code wrapup: {lockOnFinish, rolesDays, channelsDays,
 * channelsAction, archiveCategoryId}}), which steps are due and when, what a locked or archived channel lets people do, and how messages become a
 * tidy transcript (with the bot's duplicate receipts folded away).
 * <p>
 * Defaults: when the event finishes its channels go read-only, its roles are taken back after 7 days, and its channels stay until an organiser decides
 * to archive, compress, export or delete them (or sets a number of days after which one of those happens by itself).
 */
public final class EventWrapup {
    private EventWrapup() {}

    public static final int MAX_DAYS = 3650;
    public static final String SUBMISSION_PATTERN = "(submission|verification) #(\\d+)";
    private static final Pattern RECEIPT = Pattern.compile(SUBMISSION_PATTERN);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneOffset.UTC);

    // ---------- the settings ----------

    /** What to do with an event's channels on their own after {@code channelsDays}: nothing, move them out of sight, or delete them. */
    public enum ChannelsAction { NONE, ARCHIVE, DELETE }

    /**
     * {@code rolesDays} and {@code channelsDays}: days after the event finished; 0 is straight away and -1 is never (an organiser decides). The roles go
     * on their own by default; the channels do not.
     */
    /** {@code unlockOnReopen}: reopening a finished event lets the players write in the channels JonnyBot locked again (T-212, on unless switched off). */
    public record Setting(boolean lockOnFinish, int rolesDays, int channelsDays, ChannelsAction channelsAction, Long archiveCategoryId, boolean unlockOnReopen) {
        public static final Setting DEFAULT = new Setting(true, 7, -1, ChannelsAction.ARCHIVE, null, true);

        public Setting(boolean lockOnFinish, int rolesDays, int channelsDays, ChannelsAction channelsAction, Long archiveCategoryId) {
            this(lockOnFinish, rolesDays, channelsDays, channelsAction, archiveCategoryId, true);
        }
    }

    /** Whether an event that moved back from finished (to {@code now}) should have the channels JonnyBot locked opened again. */
    public static boolean unlocksOnReopen(Setting setting, EventRules.Status now, State state) {
        return setting.unlockOnReopen() && state != null && state.lockedAt() != null && now != EventRules.Status.DONE && now != EventRules.Status.CANCELLED;
    }

    public static Setting parse(DataObject config) {
        try {
            if (config == null || !config.hasKey("wrapup") || config.isNull("wrapup")) return Setting.DEFAULT;
            DataObject w = config.getObject("wrapup");
            Setting d = Setting.DEFAULT;
            ChannelsAction action = action(w.hasKey("channelsAction") && !w.isNull("channelsAction") ? w.getString("channelsAction") : d.channelsAction().name());
            return new Setting(w.opt("lockOnFinish").orElse(null) instanceof Boolean b ? b : d.lockOnFinish(), days(w, "rolesDays", d.rolesDays()), days(w, "channelsDays", d.channelsDays()),
                    action == null ? d.channelsAction() : action, id(w, "archiveCategoryId"), w.opt("unlockOnReopen").orElse(null) instanceof Boolean u ? u : d.unlockOnReopen());
        } catch (RuntimeException e) {
            return Setting.DEFAULT;
        }
    }

    public static DataObject toJson(Setting s) {
        return DataObject.empty().put("lockOnFinish", s.lockOnFinish()).put("rolesDays", s.rolesDays()).put("channelsDays", s.channelsDays())
                .put("channelsAction", s.channelsAction().name()).put("archiveCategoryId", s.archiveCategoryId() == null ? null : Long.toString(s.archiveCategoryId())).put("unlockOnReopen", s.unlockOnReopen());
    }

    /** What is wrong with the {@code wrapup} settings in {@code config}, in words, or an empty list. */
    public static List<String> problems(DataObject config) {
        List<String> problems = new ArrayList<>();
        if (config == null || !config.hasKey("wrapup") || config.isNull("wrapup")) return problems;
        try {
            DataObject w = config.getObject("wrapup");
            for (String key : new String[]{"rolesDays", "channelsDays"}) {
                if (!w.hasKey(key) || w.isNull(key)) continue;
                Integer value = intOf(w, key);
                if (value == null || value < -1 || value > MAX_DAYS) problems.add("\"" + (key.equals("rolesDays") ? "Take the roles back after" : "Deal with the channels after") + "\" has to be a number of days from 0 to " + MAX_DAYS + ", or -1 for never.");
            }
            if (w.hasKey("channelsAction") && !w.isNull("channelsAction") && action(w.getString("channelsAction")) == null) problems.add("Choose what happens to the channels: nothing, archive or delete.");
            if (w.hasKey("archiveCategoryId") && !w.isNull("archiveCategoryId") && !w.getString("archiveCategoryId", "").isBlank() && id(w, "archiveCategoryId") == null) problems.add("Choose a category for the archive (the id looks wrong).");
            if (w.hasKey("lockOnFinish") && !w.isNull("lockOnFinish") && !(w.opt("lockOnFinish").orElse(null) instanceof Boolean)) problems.add("Locking the channels has to be on or off.");
            if (w.hasKey("unlockOnReopen") && !w.isNull("unlockOnReopen") && !(w.opt("unlockOnReopen").orElse(null) instanceof Boolean)) problems.add("Unlocking the channels when the event is reopened has to be on or off.");
        } catch (RuntimeException e) {
            problems.add("The wrap-up settings couldn't be read.");
        }
        return problems;
    }

    private static int days(DataObject w, String key, int fallback) {
        if (!w.hasKey(key) || w.isNull(key)) return fallback;
        Integer value = intOf(w, key);
        return value == null || value < -1 || value > MAX_DAYS ? fallback : value;
    }

    private static Integer intOf(DataObject w, String key) {
        try {
            Object raw = w.opt(key).orElse(null);
            if (raw instanceof Number n) return n.doubleValue() == Math.rint(n.doubleValue()) ? n.intValue() : null;
            if (raw instanceof String s && s.strip().matches("-?\\d{1,5}")) return Integer.parseInt(s.strip());
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static ChannelsAction action(String raw) {
        try {
            return raw == null ? null : ChannelsAction.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long id(DataObject w, String key) {
        try {
            if (!w.hasKey(key) || w.isNull(key)) return null;
            String raw = w.getString(key).strip();
            return raw.matches("\\d{1,20}") ? Long.parseLong(raw) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------- what is due ----------

    public enum Step { LOCK, ROLES, CHANNELS }

    /** When each step was done (null = not yet) and when the event finished (null = not noted yet). */
    public record State(Instant finishedAt, Instant lockedAt, Instant rolesRemovedAt, Instant channelsDoneAt) {
        public static final State NONE = new State(null, null, null, null);
    }

    /**
     * The steps that should run now, in order. Nothing is due before the event has finished and its finish has been noted, a step runs once, and a
     * step with nothing to act on (no channels, no roles) is not due. The channels step also needs a number of days and an action other than nothing.
     */
    public static List<Step> due(Setting setting, EventRules.Status status, boolean hasChannels, boolean hasRoles, State state, Instant now) {
        List<Step> due = new ArrayList<>();
        if (status != EventRules.Status.DONE && status != EventRules.Status.CANCELLED) return due;
        if (state.finishedAt() == null) return due;
        if (setting.lockOnFinish() && hasChannels && state.lockedAt() == null) due.add(Step.LOCK);
        if (setting.rolesDays() >= 0 && hasRoles && state.rolesRemovedAt() == null && !now.isBefore(state.finishedAt().plus(Duration.ofDays(setting.rolesDays())))) due.add(Step.ROLES);
        if (setting.channelsDays() >= 0 && setting.channelsAction() != ChannelsAction.NONE && hasChannels && state.channelsDoneAt() == null
                && !now.isBefore(state.finishedAt().plus(Duration.ofDays(setting.channelsDays())))) due.add(Step.CHANNELS);
        return due;
    }

    /** When a step will happen by itself, or null when it won't (never, or already done). */
    public static Instant dueAt(Setting setting, State state, Step step) {
        if (state.finishedAt() == null) return null;
        return switch (step) {
            case LOCK -> setting.lockOnFinish() && state.lockedAt() == null ? state.finishedAt() : null;
            case ROLES -> setting.rolesDays() >= 0 && state.rolesRemovedAt() == null ? state.finishedAt().plus(Duration.ofDays(setting.rolesDays())) : null;
            case CHANNELS -> setting.channelsDays() >= 0 && setting.channelsAction() != ChannelsAction.NONE && state.channelsDoneAt() == null
                    ? state.finishedAt().plus(Duration.ofDays(setting.channelsDays())) : null;
        };
    }

    // ---------- what a locked or archived channel allows ----------

    /**
     * The access of a channel once the event is over: whoever could chat can only read. Staff and organisers keep what they had, so they can still tidy
     * up; announcements and the board were read-only already.
     */
    public static List<Grant> lockedAccess(List<Grant> grants) {
        List<Grant> locked = new ArrayList<>();
        for (Grant g : grants) {
            boolean player = g.who().who() == Who.PARTICIPANTS || g.who().who() == Who.TEAM || g.who().who() == Who.PLAYER;
            locked.add(player && g.level() == Level.CHAT ? new Grant(g.who(), Level.READ) : g);
        }
        return locked;
    }

    /** The access of an archived channel: out of sight of everyone but staff (and organisers, who may read) and JonnyBot. */
    public static List<Grant> archivedAccess() {
        return List.of(new Grant(Principal.of(Who.EVERYONE), Level.HIDDEN), new Grant(Principal.of(Who.ORGANISERS), Level.READ),
                new Grant(Principal.of(Who.STAFF), Level.MANAGE), new Grant(Principal.of(Who.BOT), Level.BOT));
    }

    // ---------- transcripts ----------

    /** One message of a channel, as the export and the transcripts keep it. */
    public record Msg(long id, String channel, long authorId, String author, boolean bot, Instant at, String content, List<String> attachments) {
        public Msg {
            content = content == null ? "" : content;
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /** The last line of a receipt, which staff's decision changes ("-# ✅ Approved"). */
    private static String statusLine(String content) {
        int cut = content.lastIndexOf("\n-# ");
        return cut < 0 ? null : content.substring(cut + 1);
    }

    /**
     * Folds the bot's duplicates into one: a submission is posted to the review channel and again as a receipt in the player's own channel, so the same
     * "submission #12" appears twice. One message is kept for each (the one in a {@code preferred} channel, else the earliest) and, if the kept one has no
     * decision line but a duplicate does, the decision is added to it. Everything else, including people's own messages, is kept in order.
     */
    public static Dedupe dedupe(List<Msg> messages, Set<String> preferred) {
        Map<String, List<Msg>> groups = new LinkedHashMap<>();
        for (Msg m : messages) {
            String key = receiptKey(m);
            if (key != null) groups.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        Map<Long, Msg> keep = new LinkedHashMap<>();
        int dropped = 0;
        for (List<Msg> group : groups.values()) {
            Msg chosen = group.stream().filter(m -> preferred.contains(m.channel())).findFirst().orElse(group.get(0));
            if (!decided(chosen.content())) {
                for (Msg other : group) {
                    String s = statusLine(other.content());
                    if (s != null && (s.contains("✅") || s.contains("❌"))) {
                        chosen = new Msg(chosen.id(), chosen.channel(), chosen.authorId(), chosen.author(), chosen.bot(), chosen.at(), chosen.content() + "\n" + s, chosen.attachments());
                        break;
                    }
                }
            }
            keep.put(chosen.id(), chosen);
            dropped += group.size() - 1;
        }
        List<Msg> result = new ArrayList<>();
        for (Msg m : messages) {
            String key = receiptKey(m);
            if (key == null) result.add(m);
            else if (keep.containsKey(m.id())) result.add(keep.get(m.id()));
        }
        return new Dedupe(result, dropped);
    }

    public record Dedupe(List<Msg> messages, int dropped) {}

    /** Whether a submission message already says what staff decided: the review message gets a line "✅ Approved by ...", a receipt a line "-# ✅ Approved". */
    private static boolean decided(String content) {
        return content.lines().anyMatch(l -> l.startsWith("✅") || l.startsWith("❌") || l.startsWith("-# ✅") || l.startsWith("-# ❌"));
    }

    private static String receiptKey(Msg m) {
        if (!m.bot()) return null;
        Matcher matcher = RECEIPT.matcher(m.content());
        return matcher.find() ? matcher.group(1) + "#" + matcher.group(2) : null;
    }

    /** A channel's messages as plain text, oldest first, one block per message. */
    public static String transcript(String title, List<Msg> messages) {
        StringBuilder out = new StringBuilder("# ").append(title).append('\n');
        out.append(messages.size()).append(messages.size() == 1 ? " message" : " messages").append("\n\n");
        for (Msg m : messages) {
            out.append('[').append(STAMP.format(m.at())).append("] ").append(m.author()).append(m.bot() ? " (bot)" : "").append(": ").append(m.content().replace("\r", "")).append('\n');
            for (String a : m.attachments()) out.append("    attachment: ").append(a).append('\n');
        }
        return out.toString();
    }

    /**
     * A table as CSV text that spreadsheets open as it is: a header row, then one row per entry, every value quoted where it needs to be. A value that starts
     * like a formula ({@code = + - @}) gets a leading apostrophe so a spreadsheet shows it as text instead of running it (players choose their own names).
     */
    public static String csv(List<String> header, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        line(out, header);
        for (List<String> row : rows) line(out, row);
        return out.toString();
    }

    private static void line(StringBuilder out, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.append(',');
            out.append(cell(values.get(i)));
        }
        out.append("\r\n");
    }

    static String cell(String raw) {
        if (raw == null) return "";
        String value = raw;
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 && !value.matches("-?\\d+(\\.\\d+)?")) value = "'" + value;
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) value = "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }

    /** A file or folder name that is safe in a zip: lower case letters, digits and hyphens. */
    public static String safeName(String raw) {
        String cleaned = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60).replaceAll("-+$", "");
        return cleaned.isEmpty() ? "channel" : cleaned;
    }
}
