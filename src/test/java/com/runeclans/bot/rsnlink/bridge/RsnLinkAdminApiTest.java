package com.runeclans.bot.rsnlink.bridge;

import com.runeclans.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Only the bot's owner gets anything from the RS Link abuse view, however high their role in the server. */
class RsnLinkAdminApiTest {
    private RsnLinkAdminApi api(boolean owner) {
        BotOwners owners = mock(BotOwners.class);
        when(owners.isOwner(any(), anyLong())).thenReturn(owner);
        RsnLinkModule module = mock(RsnLinkModule.class);
        return new RsnLinkAdminApi(module, owners);
    }

    private static Guild guild() {
        Guild guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        return guild;
    }

    @Test
    void otherPathsAreNotItsBusiness() {
        assertNull(api(false).route(guild(), mock(Member.class), "GET", new String[]{"beta-guilds"}, DataObject::empty));
    }

    @Test
    void aServerAdminWhoIsNotTheOwnerIsRefused() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> api(false).route(guild(), mock(Member.class), "GET", new String[]{"rsnlink", "overview"}, DataObject::empty));
        assertTrue(e.getMessage().contains("owner"));
    }

    @Test
    void theOwnerIsToldWhenTheServiceIsOff() {
        RuntimeException e = assertThrows(RuntimeException.class, () -> api(true).route(guild(), mock(Member.class), "GET", new String[]{"rsnlink", "overview"}, DataObject::empty));
        assertTrue(e.getMessage().contains("off"));
    }

    @Test
    void theOverallPanelAsksByUserIdAndTheOwnerRuleHolds() {
        JDA jda = mock(JDA.class);
        RuntimeException refused = assertThrows(RuntimeException.class, () -> api(false).routeFor(jda, 7L, "GET", new String[]{"rsnlink", "overview"}, DataObject::empty));
        assertTrue(refused.getMessage().contains("owner"));
        RuntimeException off = assertThrows(RuntimeException.class, () -> api(true).routeFor(jda, 7L, "GET", new String[]{"rsnlink", "overview"}, DataObject::empty));
        assertTrue(off.getMessage().contains("off"));
        assertNull(api(true).routeFor(jda, 7L, "GET", new String[]{"other"}, DataObject::empty));
    }
}
