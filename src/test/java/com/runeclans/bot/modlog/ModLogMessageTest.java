package com.runeclans.bot.modlog;

import com.runeclans.bot.modlog.ModLogMessage.Kind;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModLogMessageTest {
    private static final Instant WHEN = Instant.parse("2026-10-11T12:00:00Z");

    private static String field(MessageEmbed embed, String name) {
        return embed.getFields().stream().filter(f -> name.equals(f.getName())).map(MessageEmbed.Field::getValue).findFirst().orElse(null);
    }

    @Test
    void aBanNamesTheMemberTheModeratorAndTheReason() {
        MessageEmbed embed = ModLogMessage.embed(Kind.BAN, 11, "troll", 22L, "Spam", null, WHEN);

        assertEquals("Member banned", embed.getTitle());
        assertEquals("<@11> (troll)", field(embed, "Member"));
        assertEquals("<@22>", field(embed, "By"));
        assertEquals("Spam", field(embed, "Reason"));
    }

    @Test
    void whenTheAuditLogCannotBeReadThoseLinesAreLeftOutRatherThanGuessed() {
        MessageEmbed embed = ModLogMessage.embed(Kind.UNBAN, 11, null, null, null, null, WHEN);

        assertEquals("<@11>", field(embed, "Member"));
        assertEquals(null, field(embed, "By"));
        assertEquals(null, field(embed, "Reason"));
    }

    @Test
    void aTimeoutShowsWhenItEnds() {
        MessageEmbed embed = ModLogMessage.embed(Kind.TIMEOUT, 11, "x", 22L, null, WHEN.plusSeconds(600), WHEN);

        assertTrue(field(embed, "Until").contains("<t:" + WHEN.plusSeconds(600).getEpochSecond() + ":f>"));
    }

    @Test
    void onlyATimeoutHasAnEndTime() {
        assertFalse(ModLogMessage.embed(Kind.TIMEOUT_END, 11, "x", null, null, WHEN, WHEN).getFields().stream().anyMatch(f -> "Until".equals(f.getName())));
    }

    @Test
    void aLongReasonIsCutShort() {
        String reason = field(ModLogMessage.embed(Kind.BAN, 1, "x", null, "a".repeat(2000), null, WHEN), "Reason");

        assertEquals(ModLogMessage.MAX_REASON, reason.length());
        assertTrue(reason.endsWith("…"));
    }

    @Test
    void defaultsAreOffButLogEverythingOnceSwitchedOn() {
        ModLogConfig c = ModLogConfig.defaults(5);

        assertFalse(c.enabled());
        assertTrue(c.logBans() && c.logTimeouts());
    }
}
