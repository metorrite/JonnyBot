package com.runeclans.bot.automod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automod's rules: what counts as a violation of invites, links, blocked words, shouting, mention spam, fast spam, repeats and emoji
 * floods. Pure (no Discord, no database), so it can be tested; the listener feeds it each message and acts on the answer.
 */
public final class AutomodRules {
    public static final int MAX_WORDS = 100;
    public static final int MAX_WORD_LENGTH = 40;
    public static final int MAX_DOMAINS = 50;

    /** The things automod can check, with the plain-English name the dashboard and the notice use. */
    public enum Rule {
        INVITES("Discord invites", "posting a Discord invite link"),
        LINKS("Links", "posting a link"),
        PHISHING("Phishing links", "posting a link from a known scam website"),
        BLOCKED_WORDS("Blocked words", "using a word that isn't allowed here"),
        CAPS("Too many capitals", "writing mostly in capitals"),
        MENTIONS("Mention spam", "mentioning too many people"),
        SPAM("Fast spam", "sending messages too fast"),
        REPEAT("Repeated messages", "repeating the same message"),
        EMOJI("Emoji floods", "using too many emoji"),
        IMAGE_SPAM("Image spam", "sending too many images too fast"),
        NEWLINES("Newline spam", "using too many line breaks"),
        STICKERS("Sticker spam", "sending too many stickers"),
        SPOILERS("Spoiler spam", "using too many spoiler tags"),
        MASKED_LINKS("Masked links", "hiding a link behind other words"),
        ZALGO("Zalgo text", "using glitchy stacked-accent text");

        private final String label;
        private final String reason;

        Rule(String label, String reason) {
            this.label = label;
            this.reason = reason;
        }

        public String label() {
            return label;
        }

        /** Finishes "Please stop ...", e.g. "posting a link". */
        public String reason() {
            return reason;
        }
    }

    /**
     * One rule's settings. {@code limit} and {@code seconds} mean different things per rule (see {@link #defaults}); {@code words} is the
     * blocked words for BLOCKED_WORDS and the allowed domains for LINKS. {@code warn} also gives the writer a warning (and so counts toward Autopunish).
     */
    public record RuleConfig(boolean enabled, boolean warn, int limit, int seconds, List<String> words, String response, long logChannelId) {
        public static final int MAX_RESPONSE = 300;

        public RuleConfig {
            words = words == null ? List.of() : List.copyOf(words);
            response = response == null ? "" : response.strip();
        }

        /** A rule with the standard response and no log channel. */
        public RuleConfig(boolean enabled, boolean warn, int limit, int seconds, List<String> words) {
            this(enabled, warn, limit, seconds, words, "", 0);
        }

        public RuleConfig withEnabled(boolean on) {
            return new RuleConfig(on, warn, limit, seconds, words, response, logChannelId);
        }

        /**
         * What is said to the writer when this rule removes their message: the custom response if there is one, otherwise the standard note.
         * {user} is the writer, {rule} the rule's name and {reason} what they did.
         */
        public String note(Rule rule, String userMention) {
            String text = response.isEmpty() ? "Please stop {reason}, {user}." : response;
            return text.replace("{user}", userMention).replace("{rule}", rule.label()).replace("{reason}", rule.reason());
        }
    }

    /** What automod knows about a message. {@code phishingHost} says whether a website name is on the scam list (never null). */
    public record Facts(String text, int mentions, int images, int stickers, Predicate<String> phishingHost) {
        public Facts(String text, int mentions) {
            this(text, mentions, 0, 0, host -> false);
        }
    }

    private AutomodRules() {}

    /** Sensible starting settings, all off. CAPS: limit = percent capitals, seconds = least letters before it counts. MENTIONS and EMOJI: limit = most allowed. SPAM: limit messages within seconds. REPEAT: limit identical messages within seconds. */
    public static RuleConfig defaults(Rule rule) {
        return switch (rule) {
            case CAPS -> new RuleConfig(false, false, 70, 12, List.of());
            case MENTIONS -> new RuleConfig(false, false, 5, 0, List.of());
            case SPAM -> new RuleConfig(false, false, 6, 5, List.of());
            case REPEAT -> new RuleConfig(false, false, 3, 30, List.of());
            case EMOJI -> new RuleConfig(false, false, 10, 0, List.of());
            case IMAGE_SPAM -> new RuleConfig(false, false, 4, 10, List.of());
            case NEWLINES -> new RuleConfig(false, false, 15, 0, List.of());
            case STICKERS -> new RuleConfig(false, false, 3, 10, List.of());
            case SPOILERS -> new RuleConfig(false, false, 4, 0, List.of());
            case ZALGO -> new RuleConfig(false, false, 4, 0, List.of());
            default -> new RuleConfig(false, false, 0, 0, List.of());
        };
    }

    public static Map<Rule, RuleConfig> allDefaults() {
        Map<Rule, RuleConfig> map = new EnumMap<>(Rule.class);
        for (Rule r : Rule.values()) map.put(r, defaults(r));
        return map;
    }

    /** What is wrong with a rule's settings, in words, or an empty list. */
    public static List<String> problems(Rule rule, RuleConfig c) {
        List<String> problems = new ArrayList<>();
        String name = rule.label();
        switch (rule) {
            case CAPS -> {
                if (c.limit() < 40 || c.limit() > 100) problems.add(name + ": the percentage of capitals has to be between 40 and 100.");
                if (c.seconds() < 5 || c.seconds() > 200) problems.add(name + ": the least letters before it counts has to be between 5 and 200.");
            }
            case MENTIONS -> {
                if (c.limit() < 2 || c.limit() > 50) problems.add(name + ": the most mentions allowed has to be between 2 and 50.");
            }
            case SPAM -> {
                if (c.limit() < 3 || c.limit() > 30) problems.add(name + ": the number of messages has to be between 3 and 30.");
                if (c.seconds() < 2 || c.seconds() > 60) problems.add(name + ": the time window has to be between 2 and 60 seconds.");
            }
            case REPEAT -> {
                if (c.limit() < 2 || c.limit() > 10) problems.add(name + ": the number of repeats has to be between 2 and 10.");
                if (c.seconds() < 5 || c.seconds() > 300) problems.add(name + ": the time window has to be between 5 and 300 seconds.");
            }
            case EMOJI -> {
                if (c.limit() < 3 || c.limit() > 100) problems.add(name + ": the most emoji allowed has to be between 3 and 100.");
            }
            case BLOCKED_WORDS -> {
                if (c.words().size() > MAX_WORDS) problems.add(name + ": at most " + MAX_WORDS + " words.");
                if (c.enabled() && c.words().isEmpty()) problems.add(name + ": add at least one word, or switch the rule off.");
                for (String w : c.words()) if (w.length() > MAX_WORD_LENGTH || w.isBlank()) problems.add(name + ": every word has to be 1 to " + MAX_WORD_LENGTH + " characters.");
            }
            case IMAGE_SPAM -> {
                if (c.limit() < 2 || c.limit() > 30) problems.add(name + ": the number of images has to be between 2 and 30.");
                if (c.seconds() < 2 || c.seconds() > 120) problems.add(name + ": the time window has to be between 2 and 120 seconds.");
            }
            case NEWLINES -> {
                if (c.limit() < 4 || c.limit() > 200) problems.add(name + ": the most line breaks allowed has to be between 4 and 200.");
            }
            case STICKERS -> {
                if (c.limit() < 2 || c.limit() > 20) problems.add(name + ": the number of stickers has to be between 2 and 20.");
                if (c.seconds() < 2 || c.seconds() > 120) problems.add(name + ": the time window has to be between 2 and 120 seconds.");
            }
            case SPOILERS -> {
                if (c.limit() < 1 || c.limit() > 50) problems.add(name + ": the most spoiler tags allowed has to be between 1 and 50.");
            }
            case ZALGO -> {
                if (c.limit() < 3 || c.limit() > 20) problems.add(name + ": the most stacked accents allowed has to be between 3 and 20.");
            }
            case LINKS, MASKED_LINKS -> {
                if (c.words().size() > MAX_DOMAINS) problems.add(name + ": at most " + MAX_DOMAINS + " allowed websites.");
                for (String d : c.words()) if (!d.matches("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+")) problems.add(name + ": \"" + d + "\" isn't a website name like example.com.");
            }
            default -> { }
        }
        if (c.response().length() > RuleConfig.MAX_RESPONSE) problems.add(name + ": the response can be at most " + RuleConfig.MAX_RESPONSE + " characters.");
        return problems.stream().distinct().toList();
    }

    /** Lower-cases, trims and de-duplicates a list typed by an admin (one entry per line or comma-separated). */
    public static List<String> cleanList(String raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,\\n]")) {
            String w = part.strip().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && !out.contains(w)) out.add(w);
        }
        return out;
    }

    // ---------- detection ----------

    private static final Pattern INVITE = Pattern.compile("(?:discord(?:app)?\\.com/invite|discord\\.gg|discord\\.me|discord\\.io)/[\\w-]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)([^\\s/<>]+)[^\\s]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern CUSTOM_EMOJI = Pattern.compile("<a?:\\w+:\\d+>");
    private static final Pattern UNICODE_EMOJI = Pattern.compile("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{1F000}-\\x{1F2FF}]");

    private static final Pattern MASKED = Pattern.compile("\\[([^\\]]{1,200})\\]\\(\\s*<?(https?://[^)\\s>]+)>?[^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPOILER = Pattern.compile("\\|\\|.+?\\|\\|", Pattern.DOTALL);
    private static final Pattern HOSTNAME = Pattern.compile("(?<![\\w.-])((?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,})(?![\\w-])", Pattern.CASE_INSENSITIVE);

    /** Line breaks in the message. */
    public static int newlineCount(String text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** How many ||spoiler|| tags the message has. */
    public static int spoilerCount(String text) {
        Matcher m = SPOILER.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * Whether the message hides a link behind other words, like {@code [click here](https://example.com)}. A link whose words are the address
     * itself is not masked, and a link to an allowed website is left alone.
     */
    public static boolean hasMaskedLink(String text, List<String> allowedDomains) {
        Matcher m = MASKED.matcher(text);
        while (m.find()) {
            String label = m.group(1).strip();
            String target = m.group(2);
            if (label.equalsIgnoreCase(target) || label.equalsIgnoreCase(target.replaceFirst("^https?://", ""))) continue;
            Matcher host = URL.matcher(target);
            String name = host.find() ? host.group(1).toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "").replaceFirst("^www\\.", "") : "";
            boolean allowed = allowedDomains.stream().anyMatch(d -> name.equals(d) || name.endsWith("." + d));
            if (!allowed) return true;
        }
        return false;
    }

    /** Whether some run of combining accents is at least {@code run} long, the stacked-accent "zalgo" look. Ordinary accents (one or two on a letter) pass. */
    public static boolean hasZalgo(String text, int run) {
        int current = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) {
                if (++current >= run) return true;
            } else {
                current = 0;
            }
        }
        return false;
    }

    /** Whether the message names a website that {@code phishingHost} knows as a scam (the site itself or any parent domain of it counts). */
    public static boolean hasPhishingLink(String text, Predicate<String> phishingHost) {
        Matcher m = HOSTNAME.matcher(text);
        while (m.find()) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            while (host.contains(".")) {
                if (phishingHost.test(host)) return true;
                host = host.substring(host.indexOf('.') + 1);
            }
        }
        return false;
    }

    public static boolean hasInvite(String text) {
        return INVITE.matcher(text).find();
    }

    /** Whether the text links somewhere that isn't on the allowed list. Invites are left to their own rule. */
    public static boolean hasDisallowedLink(String text, List<String> allowedDomains) {
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String host = m.group(1).toLowerCase(Locale.ROOT).replaceAll(":\\d+$", "").replaceFirst("^www\\.", "");
            if (INVITE.matcher(m.group()).find()) continue;
            boolean allowed = allowedDomains.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
            if (!allowed) return true;
        }
        return false;
    }

    /** Whether any blocked word appears as a whole word (so "class" isn't caught by "ass"). */
    public static boolean hasBlockedWord(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w) + "(?![\\p{L}\\p{N}])").matcher(lower).find()) return true;
        }
        return false;
    }

    /** Shouting: at least {@code minLetters} letters, and {@code percent} or more of them upper case. */
    public static boolean isShouting(String text, int percent, int minLetters) {
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            if (Character.isUpperCase(cp)) upper++;
        }
        return letters >= minLetters && upper * 100 >= percent * letters;
    }

    public static int emojiCount(String text) {
        int n = 0;
        Matcher c = CUSTOM_EMOJI.matcher(text);
        while (c.find()) n++;
        Matcher u = UNICODE_EMOJI.matcher(CUSTOM_EMOJI.matcher(text).replaceAll(""));
        while (u.find()) n++;
        return n;
    }

    /** A message's text normalised for comparing repeats: lower case, collapsed whitespace. */
    public static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    // ---------- per-person history ----------

    /** What one person has recently sent, for the rules that look at more than one message. */
    public static final class History {
        private record Sent(long at, String text, int images, int stickers) {}

        private final Deque<Sent> sent = new ArrayDeque<>();
        private long lastAt;

        /** Remembers a message and drops anything older than {@code keepSeconds}. */
        public synchronized void record(long now, String text, int keepSeconds) {
            record(now, text, keepSeconds, 0, 0);
        }

        public synchronized void record(long now, String text, int keepSeconds, int images, int stickers) {
            lastAt = now;
            sent.addLast(new Sent(now, normalise(text), images, stickers));
            while (!sent.isEmpty() && now - sent.peekFirst().at() > keepSeconds * 1000L) sent.removeFirst();
            while (sent.size() > 60) sent.removeFirst();
        }

        public synchronized int within(long now, int seconds) {
            int n = 0;
            for (Sent s : sent) if (now - s.at() <= seconds * 1000L) n++;
            return n;
        }

        public synchronized int imagesWithin(long now, int seconds) {
            int n = 0;
            for (Sent s : sent) if (now - s.at() <= seconds * 1000L) n += s.images();
            return n;
        }

        public synchronized int stickersWithin(long now, int seconds) {
            int n = 0;
            for (Sent s : sent) if (now - s.at() <= seconds * 1000L) n += s.stickers();
            return n;
        }

        public synchronized int identicalWithin(long now, int seconds, String text) {
            String wanted = normalise(text);
            int n = 0;
            for (Sent s : sent) if (now - s.at() <= seconds * 1000L && s.text().equals(wanted)) n++;
            return n;
        }

        /** Whether nothing has been sent for longer than {@code seconds}, so the record can be dropped. */
        public synchronized boolean idleFor(long now, int seconds) {
            return now - lastAt > seconds * 1000L;
        }
    }

    /**
     * The first enabled rule a message breaks, or null. {@code mentions} is how many people, roles and @everyone it pinged. The message
     * is recorded in {@code history} before the fast-spam and repeat rules look at it, so the current message counts.
     */
    public static Rule firstViolation(Map<Rule, RuleConfig> config, String text, int mentions, History history, long now) {
        return firstViolation(config, new Facts(text, mentions), history, now);
    }

    /** As above, with everything automod knows about the message (images, stickers, the scam list). */
    public static Rule firstViolation(Map<Rule, RuleConfig> config, Facts facts, History history, long now) {
        String text = facts.text();
        int mentions = facts.mentions();
        history.record(now, text, 300, facts.images(), facts.stickers());
        for (Rule rule : Rule.values()) {
            RuleConfig c = config.getOrDefault(rule, defaults(rule));
            if (!c.enabled()) continue;
            boolean broken = switch (rule) {
                case INVITES -> hasInvite(text);
                case LINKS -> hasDisallowedLink(text, c.words());
                case BLOCKED_WORDS -> hasBlockedWord(text, c.words());
                case CAPS -> isShouting(text, c.limit(), c.seconds());
                case MENTIONS -> mentions > c.limit();
                case SPAM -> history.within(now, c.seconds()) >= c.limit();
                case REPEAT -> normalise(text).length() >= 4 && history.identicalWithin(now, c.seconds(), text) >= c.limit();
                case EMOJI -> emojiCount(text) > c.limit();
                case PHISHING -> hasPhishingLink(text, facts.phishingHost());
                case IMAGE_SPAM -> facts.images() > 0 && history.imagesWithin(now, c.seconds()) >= c.limit();
                case NEWLINES -> newlineCount(text) > c.limit();
                case STICKERS -> facts.stickers() > 0 && history.stickersWithin(now, c.seconds()) >= c.limit();
                case SPOILERS -> spoilerCount(text) > c.limit();
                case MASKED_LINKS -> hasMaskedLink(text, c.words());
                case ZALGO -> hasZalgo(text, c.limit());
            };
            if (broken) return rule;
        }
        return null;
    }
}
