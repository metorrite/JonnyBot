package com.younglings.bot.tracking;

import com.younglings.bot.runescape.PlayerActivity;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.SkillEmojiCatalog;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.audit.ActionType;
import net.dv8tion.jda.api.audit.AuditLogEntry;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one {@link PlayerActivity} (RuneMetrics' adventure log feed) or one Discord
 * {@link AuditLogEntry} into a {@link ClassifiedEntry} — a group plus an already-rendered plain-text
 * line — or {@code empty} if it's not one of the entry types the approved taxonomy covers yet.
 * <p>
 * The RuneMetrics rules are ordered most-specific-first and each one returns as soon as it matches;
 * two of them ({@link #CHAMPION_WIN}, the "Capped at my Clan Citadel" exact match) are lifted
 * directly from real activity text already confirmed live elsewhere in this codebase
 * ({@link com.younglings.bot.runescape.MonthlyRecapService}) — the rest are compiled from
 * <a href="https://runescape.wiki/w/RuneMetrics/Adventurer%27s_Log">the wiki's own documented
 * templates</a> and haven't all been individually confirmed against real text yet. Expect to add a
 * pattern here occasionally when something shows up that doesn't match any rule — same "we'll add it
 * when we see it" approach already agreed for the drop list.
 */
@BService
public class TrackingEventClassifier {
    private static final Pattern CHAMPION_WIN = Pattern.compile(
            "Won a challenge against the (.+?) Champion", Pattern.CASE_INSENSITIVE);
    private static final Pattern XP_MILESTONE = Pattern.compile("^[\\d,]+XP in (.+)$");
    private static final Pattern SKILL_LEVEL_UP = Pattern.compile("^Levelled up (.+?)\\.?$");
    private static final Pattern QUEST_COMPLETE = Pattern.compile("^Quest complete: ?(.+)$");
    private static final Pattern SKILLING_PET = Pattern.compile("^I found (.+?), the (.+?) pet\\.?$", Pattern.CASE_INSENSITIVE);

    private static final List<String> NAMED_BOSSES = List.of(
            "TzTok-Jad", "TzKal-Zuk", "Telos", "Vorago", "Nex", "Kalphite King", "Kalphite Queen",
            "Queen Black Dragon", "Corporeal Beast", "General Graardor", "Kree'arra", "K'ril Tsutsaroth",
            "Commander Zilyana", "Vindicta", "Helwyr", "Gregorovic", "Har-Aken", "The Magister",
            "Araxxi", "Nymora", "Avaryss", "Arch-Glacor", "Telos, the Warden");

    private static final List<String> MINIGAME_KEYWORDS = List.of(
            "Castle Wars", "Dominion Tower", "duellist's cap", "wildstalker helmet", "Daemonheim",
            "dungeoneering tokens", "charm sprites", "Livid Farm", "court summons", "Golden Cannon",
            "Royale Cannon", "Master Student", "Fight Kiln", "chimp ices", "Dagannoth Kings' Rex",
            "Duel Arena");

    // Notified on the 1st kill (first blood is notable) and then every 10th after that — suppresses
    // the per-kill spam RuneMetrics itself doesn't filter out (see #recordAndCheckBossKillMilestone).
    private static final int BOSS_KILL_MILESTONE_INTERVAL = 10;

    private final SkillEmojiCatalog skillEmojiCatalog;
    private final TrackingIconCatalog trackingIconCatalog;
    private final BossKillTallyRepository bossKillTallyRepository;

    public TrackingEventClassifier(SkillEmojiCatalog skillEmojiCatalog, TrackingIconCatalog trackingIconCatalog,
                                    BossKillTallyRepository bossKillTallyRepository) {
        this.skillEmojiCatalog = skillEmojiCatalog;
        this.trackingIconCatalog = trackingIconCatalog;
        this.bossKillTallyRepository = bossKillTallyRepository;
    }

    public Optional<ClassifiedEntry> classify(long guildId, String rsn, PlayerActivity activity) {
        String text = activity.text();
        String bold = "**" + rsn + "**";

        if (text.startsWith("Capped at my Clan Citadel")) {
            return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " capped at the Clan Citadel.");
        }
        if (text.startsWith("Visited my Clan Citadel")) {
            return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " visited the Clan Citadel.");
        }
        if (text.startsWith("Maintained Clan Fealty")) {
            return entry(TrackingGroup.CITADEL_ACTIVITY, null, bold + " " + lowerFirst(text));
        }

        Matcher quest = QUEST_COMPLETE.matcher(text);
        if (quest.matches()) {
            return entry(TrackingGroup.QUESTS, trackingIconCatalog.mentionForCategory("quest"),
                    bold + " completed the quest **" + quest.group(1).trim() + "**.");
        }
        if (text.contains("Quest points obtained") || text.contains("Quest Points obtained")) {
            return entry(TrackingGroup.QUESTS, trackingIconCatalog.mentionForCategory("quest"), bold + " " + lowerFirst(text));
        }

        Optional<DropItemCatalog.DropItem> drop = DropItemCatalog.findIn(text);
        if (drop.isPresent()) {
            DropItemCatalog.DropItem item = drop.get();
            return entry(TrackingGroup.NOTABLE_DROPS, trackingIconCatalog.mentionForDrop(item.key()),
                    bold + " found **" + item.name() + "**.");
        }

        Matcher levelUp = SKILL_LEVEL_UP.matcher(text);
        if (levelUp.matches()) {
            String skillName = levelUp.group(1).trim();
            return entry(TrackingGroup.SKILL_MILESTONES, skillEmojiMention(skillName),
                    bold + " levelled up **" + skillName + "**.");
        }
        Matcher xpMilestone = XP_MILESTONE.matcher(text);
        if (xpMilestone.matches()) {
            String skillName = xpMilestone.group(1).trim();
            return entry(TrackingGroup.SKILL_MILESTONES, skillEmojiMention(skillName), bold + " reached " + text + ".");
        }
        if (text.contains("total levels gained") || text.startsWith("Levelled all skills over")) {
            return entry(TrackingGroup.SKILL_MILESTONES, null, bold + " " + lowerFirst(text));
        }

        Matcher championWin = CHAMPION_WIN.matcher(text);
        if (championWin.find()) {
            return entry(TrackingGroup.BOSS_KILLS, null,
                    bold + " won a challenge against the **" + championWin.group(1).trim() + " Champion**.");
        }
        if (text.startsWith("I killed") || text.startsWith("I defeated")) {
            for (String boss : NAMED_BOSSES) {
                if (text.contains(boss)) {
                    return recordAndCheckBossKillMilestone(guildId, rsn, boss)
                            .flatMap(count -> entry(TrackingGroup.BOSS_KILLS, null,
                                    bold + " has defeated **" + boss + "** " + count + (count == 1 ? " time." : " times.")));
                }
            }
        }

        Matcher pet = SKILLING_PET.matcher(text);
        if (pet.matches()) {
            return entry(TrackingGroup.PETS, trackingIconCatalog.mentionForCategory("pet"),
                    bold + " found **" + pet.group(1).trim() + "**, the " + pet.group(2).trim() + " pet.");
        }
        if (text.contains("I adopted TzRek-Jad") || text.toLowerCase(Locale.ROOT).contains("effigy pet")) {
            return entry(TrackingGroup.PETS, trackingIconCatalog.mentionForCategory("pet"), bold + " " + lowerFirst(text));
        }

        if (text.toLowerCase(Locale.ROOT).contains("treasure trail completed")) {
            return entry(TrackingGroup.CLUE_SCROLLS, trackingIconCatalog.mentionForCategory("clue"), bold + " " + lowerFirst(text));
        }

        if (text.contains("archaeological mystery") || text.contains("tetracompass")
                || (text.startsWith("Earnt my") && text.contains("qualification"))) {
            return entry(TrackingGroup.ARCHAEOLOGY, trackingIconCatalog.mentionForCategory("archaeology"), bold + " " + lowerFirst(text));
        }

        for (String keyword : MINIGAME_KEYWORDS) {
            if (text.contains(keyword)) {
                return entry(TrackingGroup.MINIGAME_MISC, null, bold + " " + lowerFirst(text));
            }
        }

        return Optional.empty();
    }

    /**
     * RuneMetrics reports every single named-boss kill, not just round-number milestones — without
     * this, a player grinding one boss would flood the Boss Kills group with one line per kill.
     * Always counts the kill; only returns the new total (to post) on the 1st kill and every
     * {@link #BOSS_KILL_MILESTONE_INTERVAL}th one after.
     */
    private Optional<Integer> recordAndCheckBossKillMilestone(long guildId, String rsn, String boss) {
        int count = bossKillTallyRepository.incrementAndGet(guildId, rsn, boss);
        return (count == 1 || count % BOSS_KILL_MILESTONE_INTERVAL == 0) ? Optional.of(count) : Optional.empty();
    }

    /** {@code actorMention} is a raw {@code <@id>} mention, already resolved by the caller. */
    public Optional<ClassifiedEntry> classify(AuditLogEntry logEntry, String actorMention) {
        TrackingGroup group = groupFor(logEntry.getType());
        if (group == null) return Optional.empty();

        return entry(group, null, actorMention + " — " + prettyLabel(logEntry.getType()));
    }

    private static TrackingGroup groupFor(ActionType type) {
        return switch (type) {
            case GUILD_UPDATE, GUILD_PROFILE_UPDATE -> TrackingGroup.SERVER_SETTINGS;
            case CHANNEL_CREATE, CHANNEL_UPDATE, CHANNEL_DELETE, CHANNEL_OVERRIDE_CREATE,
                 CHANNEL_OVERRIDE_UPDATE, CHANNEL_OVERRIDE_DELETE, THREAD_CREATE, THREAD_UPDATE,
                 THREAD_DELETE, VOICE_CHANNEL_STATUS_UPDATE, VOICE_CHANNEL_STATUS_DELETE,
                 STAGE_INSTANCE_CREATE, STAGE_INSTANCE_UPDATE, STAGE_INSTANCE_DELETE -> TrackingGroup.CHANNELS_THREADS;
            case ROLE_CREATE, ROLE_UPDATE, ROLE_DELETE, APPLICATION_COMMAND_PRIVILEGES_UPDATE -> TrackingGroup.ROLES_PERMISSIONS;
            case KICK, PRUNE, BAN, UNBAN, MEMBER_UPDATE, MEMBER_ROLE_UPDATE, MEMBER_VOICE_MOVE,
                 MEMBER_VOICE_KICK, BOT_ADD, AUTO_MODERATION_RULE_CREATE, AUTO_MODERATION_RULE_UPDATE,
                 AUTO_MODERATION_RULE_DELETE, AUTO_MODERATION_RULE_BLOCK_MESSAGE,
                 AUTO_MODERATION_FLAG_TO_CHANNEL, AUTO_MODERATION_MEMBER_TIMEOUT,
                 AUTO_MODERATION_QUARANTINE_USER -> TrackingGroup.MEMBERS_MODERATION;
            case MESSAGE_DELETE, MESSAGE_BULK_DELETE, MESSAGE_PIN, MESSAGE_UNPIN -> TrackingGroup.MESSAGES;
            case INVITE_CREATE, INVITE_UPDATE, INVITE_DELETE, WEBHOOK_CREATE, WEBHOOK_UPDATE,
                 WEBHOOK_REMOVE, EMOJI_CREATE, EMOJI_UPDATE, EMOJI_DELETE, STICKER_CREATE,
                 STICKER_UPDATE, STICKER_DELETE, SOUNDBOARD_SOUND_CREATE, SOUNDBOARD_SOUND_UPDATE,
                 SOUNDBOARD_SOUND_DELETE, SCHEDULED_EVENT_CREATE, SCHEDULED_EVENT_UPDATE,
                 SCHEDULED_EVENT_DELETE, ONBOARDING_PROMPT_CREATE, ONBOARDING_PROMPT_UPDATE,
                 ONBOARDING_PROMPT_DELETE, ONBOARDING_CREATE, ONBOARDING_UPDATE, INTEGRATION_CREATE,
                 INTEGRATION_UPDATE, INTEGRATION_DELETE, CREATOR_MONETIZATION_REQUEST_CREATED,
                 CREATOR_MONETIZATION_TERMS_ACCEPTED, HOME_SETTINGS_CREATE, HOME_SETTINGS_UPDATE -> TrackingGroup.SERVER_EXTRAS;
            default -> null; // MESSAGE_CREATE/UPDATE and anything JDA adds later that we haven't triaged
        };
    }

    /** {@code "ROLE_CREATE"} -> {@code "Role Create"} — good enough for a log line; not worth 50 hand-written labels for a first pass. */
    private static String prettyLabel(ActionType type) {
        String[] words = type.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private String skillEmojiMention(String skillName) {
        for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
            if (RuneScapeSkillCatalog.nameFor(skillId).equalsIgnoreCase(skillName)) {
                return skillEmojiCatalog.mentionFor(skillId);
            }
        }
        return null;
    }

    private static String lowerFirst(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static Optional<ClassifiedEntry> entry(TrackingGroup group, String iconMention, String message) {
        String line = iconMention != null ? iconMention + " " + message : message;
        return Optional.of(new ClassifiedEntry(group, line));
    }
}
