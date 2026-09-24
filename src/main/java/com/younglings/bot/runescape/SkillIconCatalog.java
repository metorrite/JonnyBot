package com.younglings.bot.runescape;

import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The 29 skill icons (25x25 PNGs from the RuneScape Wiki's Category:Skill_icons, downloaded once
 * and bundled under {@code src/main/resources/images/skills/}) — not fetched live, so displaying
 * them never depends on the wiki being reachable or costs an extra request per view. Loaded once
 * into memory at class-init; {@link #fileFor(int)} hands back a fresh {@link FileUpload} wrapping
 * the same bytes each time, since a FileUpload is meant to be attached to one message.
 */
public final class SkillIconCatalog {
    private static final Logger log = LoggerFactory.getLogger(SkillIconCatalog.class);
    private static final Map<Integer, byte[]> ICONS = new HashMap<>();

    static {
        for (int skillId = 0; skillId < RuneScapeSkillCatalog.skillCount(); skillId++) {
            String resource = "images/skills/" + RuneScapeSkillCatalog.nameFor(skillId).toLowerCase() + ".png";
            try (InputStream stream = SkillIconCatalog.class.getClassLoader().getResourceAsStream(resource)) {
                if (stream == null) {
                    log.warn("Skill icon resource '{}' not found on the classpath.", resource);
                    continue;
                }
                ICONS.put(skillId, stream.readAllBytes());
            } catch (IOException e) {
                log.warn("Failed to load skill icon resource '{}'", resource, e);
            }
        }
        log.info("Loaded {}/{} skill icons.", ICONS.size(), RuneScapeSkillCatalog.skillCount());
    }

    private SkillIconCatalog() {}

    /** A fresh upload of this skill's icon, or {@code null} if it wasn't found at startup. */
    public static FileUpload fileFor(int skillId) {
        byte[] bytes = ICONS.get(skillId);
        if (bytes == null) return null;
        return FileUpload.fromData(bytes, RuneScapeSkillCatalog.nameFor(skillId).toLowerCase() + ".png");
    }
}
