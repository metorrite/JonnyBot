package com.runeclans.bot.member;

import com.runeclans.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.runeclans.bot.runeclans.HubWriteQueue;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T-750: the bot's member privacy saves, sent on to the hub with the switch on; nothing with it off; a failing send never breaks the save. */
class HubMemberPrivacyTest {
    private static final long GUILD = Long.parseLong("1".repeat(18)), USER = Long.parseLong("2".repeat(18));

    @Test
    void savesGoToTheHubOnlyWithTheSwitchOn() {
        List<String[]> sent = new ArrayList<>();
        HubMemberPrivacy on = new HubMemberPrivacy((path, body) -> {
            sent.add(new String[]{path, body});
            return new HubWriteQueue.Result(HubWriteQueue.Outcome.QUEUED, null);
        });
        assertTrue(on.on());
        on.memberSaved(GUILD, USER, true, false, true);
        on.defaultsSaved(GUILD, new PrivacyDefaults(false, true, false));
        assertEquals(2, sent.size());
        assertEquals("/v1/guilds/" + GUILD + "/member-privacy/members/" + USER, sent.get(0)[0]);
        DataObject member = DataObject.fromJson(sent.get(0)[1]);
        assertTrue(member.getBoolean("hideFromLeaderboards"));
        assertFalse(member.getBoolean("hideAdventureLog"));
        assertTrue(member.getBoolean("hideDiscordLink"));
        assertFalse(member.hasKey("chosen"), "the hub marks a member who names a toggle as chosen");
        assertEquals("/v1/guilds/" + GUILD + "/member-privacy/defaults", sent.get(1)[0]);
        assertTrue(DataObject.fromJson(sent.get(1)[1]).getBoolean("hideAdventureLog"));

        HubMemberPrivacy off = HubMemberPrivacy.off();
        assertFalse(off.on());
        off.memberSaved(GUILD, USER, true, true, true); // nothing to send to, nothing thrown

        HubMemberPrivacy failing = new HubMemberPrivacy((path, body) -> {
            throw new IllegalStateException("hub down");
        });
        failing.defaultsSaved(GUILD, PrivacyDefaults.NONE); // logged, not thrown: the bot's own save already happened
    }

    @Test
    void theSwitchReadsOnOrTrue() {
        assertTrue(HubMemberPrivacy.on("on"));
        assertTrue(HubMemberPrivacy.on(" TRUE "));
        assertFalse(HubMemberPrivacy.on(null));
        assertFalse(HubMemberPrivacy.on("off"));
    }
}
