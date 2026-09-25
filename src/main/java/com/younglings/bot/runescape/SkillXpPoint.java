package com.younglings.bot.runescape;

import java.time.OffsetDateTime;

/** One historical (poll time, XP) reading for a single skill — the XP-over-time chart's data source. */
public record SkillXpPoint(OffsetDateTime timestamp, long xp) {
}
