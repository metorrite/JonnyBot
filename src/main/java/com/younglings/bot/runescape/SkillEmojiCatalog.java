package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.emoji.ApplicationEmoji;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The 29 skill icons plus the Overall/Total-level icon (bundled locally under
 * {@code src/main/resources/images/skills/}, not fetched live) — uploaded once as Discord
 * application emojis so they can be referenced inline by mention (e.g. {@code <:rs3_attack:id>}).
 * This is deliberately not done via a {@code Section}'s thumbnail accessory: Discord always
 * renders that on the right of the section's text, with no way to put it on the left, which is
 * what an inline emoji mention placed at the start of a line achieves instead.
 * <p>
 * Synced once per boot on {@link #onReady}, matched by name against the application's existing
 * emojis first — a restart doesn't re-upload duplicates, it just picks the existing ones back up.
 */
@BService
public class SkillEmojiCatalog extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SkillEmojiCatalog.class);
    private static final Map<Integer, byte[]> ICON_BYTES = new HashMap<>();
    private static final byte[] OVERALL_BYTES;

    static {
        for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
            String resource = "images/skills/" + RuneScapeSkillCatalog.nameFor(skillId).toLowerCase() + ".png";
            byte[] bytes = readResource(resource);
            if (bytes != null) ICON_BYTES.put(skillId, bytes);
        }
        OVERALL_BYTES = readResource("images/skills/overall.png");
        log.info("Loaded {}/{} skill icon images (overall icon: {}).",
                ICON_BYTES.size(), RuneScapeSkillCatalog.skillCount(), OVERALL_BYTES != null ? "found" : "missing");
    }

    private static byte[] readResource(String resource) {
        try (InputStream stream = SkillEmojiCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                log.warn("Icon resource '{}' not found on the classpath.", resource);
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException e) {
            log.warn("Failed to load icon resource '{}'", resource, e);
            return null;
        }
    }

    private final Map<Integer, String> mentionsBySkillId = new ConcurrentHashMap<>();
    private volatile String overallMention;

    @Override
    public void onReady(ReadyEvent event) {
        JDA jda = event.getJDA();
        jda.retrieveApplicationEmojis().queue(existing -> {
            Map<String, ApplicationEmoji> byName = existing.stream()
                    .collect(Collectors.toMap(ApplicationEmoji::getName, e -> e, (a, b) -> a));

            for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
                int id = skillId;
                syncEmoji(jda, byName, emojiName(RuneScapeSkillCatalog.nameFor(skillId)), ICON_BYTES.get(skillId),
                        mention -> mentionsBySkillId.put(id, mention));
            }
            syncEmoji(jda, byName, emojiName("overall"), OVERALL_BYTES, mention -> overallMention = mention);
        }, error -> log.warn("Failed to retrieve application emojis for RS3 skill icons", error));
    }

    private void syncEmoji(JDA jda, Map<String, ApplicationEmoji> existing, String name, byte[] bytes, Consumer<String> onMention) {
        ApplicationEmoji found = existing.get(name);
        if (found != null) {
            onMention.accept(found.getAsMention());
            return;
        }
        if (bytes == null) return;

        jda.createApplicationEmoji(name, Icon.from(bytes)).queue(
                created -> onMention.accept(created.getAsMention()),
                error -> log.warn("Failed to create application emoji '{}'", name, error));
    }

    private static String emojiName(String skillName) {
        return "rs3_" + skillName.toLowerCase();
    }

    /** This skill's inline emoji mention, or {@code null} if it hasn't synced yet (briefly, right after boot). */
    public String mentionFor(int skillId) {
        return mentionsBySkillId.get(skillId);
    }

    /** The Overall/Total-level inline emoji mention, or {@code null} if it hasn't synced yet. */
    public String overallMention() {
        return overallMention;
    }
}
