package com.runeclans.bot.discord;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text JonnyBot posts never pings @everyone, @here or a role unless the feature names it (T-136). */
class MentionsTest {
    @Test
    void byDefaultTextCantPingEveryoneHereOrRoles() {
        Mentions.installDefault();
        MessageCreateData data = new MessageCreateBuilder().setContent("hi @everyone @here <@&123> <@456>").build();
        Set<Message.MentionType> allowed = data.getAllowedMentions();
        assertFalse(allowed.contains(Message.MentionType.EVERYONE));
        assertFalse(allowed.contains(Message.MentionType.HERE));
        assertFalse(allowed.contains(Message.MentionType.ROLE));
        assertTrue(allowed.contains(Message.MentionType.USER), "someone named in a message is still told");
    }

    @Test
    void aFeatureThatPingsARolePingsOnlyThatOne() {
        Mentions.installDefault();
        MessageCreateBuilder alert = new MessageCreateBuilder().setContent("<@&10> Stream is live: a title with <@&999> and @everyone");
        MessageCreateData data = Mentions.onlyRoles(alert, 10L).build();
        assertTrue(data.getAllowedMentions().isEmpty(), "nothing pings by type: no @everyone, no roles, no users");
        assertEquals(Set.of("10"), data.getMentionedRoles(), "only the alert's own role");
    }

    @Test
    void noRolesMeansNoPingsAtAll() {
        MessageCreateData data = Mentions.onlyRoles(new MessageCreateBuilder().setContent("@everyone <@&1>")).build();
        assertTrue(data.getAllowedMentions().isEmpty());
        assertTrue(data.getMentionedRoles().isEmpty());
    }
}
