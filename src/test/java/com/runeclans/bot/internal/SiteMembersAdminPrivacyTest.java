package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.member.HubMemberPrivacy;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.member.MemberProfileRepository.PrivacyDefaults;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** T-750: Site Admin > Member privacy sends the saved defaults (the new ones, each in its own field) to the hub; a read sends nothing. */
class SiteMembersAdminPrivacyTest {
    private static final long GUILD = Long.parseLong("3".repeat(18)), ADMIN = Long.parseLong("4".repeat(18));

    @Test
    void savedDefaultsGoToTheHubAsSaved() {
        MemberProfileRepository profiles = mock(MemberProfileRepository.class);
        AtomicReference<PrivacyDefaults> stored = new AtomicReference<>(new PrivacyDefaults(true, false, false));
        when(profiles.privacyDefaults(GUILD)).thenAnswer(i -> stored.get());
        doAnswer(i -> {
            stored.set(i.getArgument(1));
            return null;
        }).when(profiles).savePrivacyDefaults(eq(GUILD), any(), anyLong());
        List<String[]> sent = new ArrayList<>();
        HubMemberPrivacy hub = HubMemberPrivacy.sendingTo((path, body) -> {
            sent.add(new String[]{path, body});
            return new RuneClansHubClient.Answer(200, DataObject.empty());
        }, Runnable::run);
        SiteMembersAdmin admin = new SiteMembersAdmin(profiles, mock(SiteRoleGuard.class), new SiteCache(), mock(SiteCockpitAdmin.class), mock(SiteSeasons.class), hub);
        SiteCaller caller = new SiteCaller(GUILD, ADMIN, null, null, null, null);

        admin.privacy(caller, "GET", DataObject::empty);
        assertTrue(sent.isEmpty(), "a read sends nothing");

        // leaderboards off, Discord link on; adventure log left out keeps false: (false, false, true), unlike what was stored
        admin.privacy(caller, "PUT", () -> DataObject.empty().put("hideFromLeaderboards", false).put("hideDiscordLink", true));
        assertEquals(1, sent.size());
        assertEquals("/v1/guilds/" + GUILD + "/member-privacy/defaults", sent.get(0)[0]);
        DataObject body = DataObject.fromJson(sent.get(0)[1]);
        assertEquals(false, body.getBoolean("hideFromLeaderboards"), "the new value, not the stored one");
        assertEquals(false, body.getBoolean("hideAdventureLog"));
        assertEquals(true, body.getBoolean("hideDiscordLink"));

        admin.privacy(caller, "PUT", () -> DataObject.empty().put("hideAdventureLog", true));
        DataObject second = DataObject.fromJson(sent.get(1)[1]);
        assertEquals(false, second.getBoolean("hideFromLeaderboards"));
        assertEquals(true, second.getBoolean("hideAdventureLog"));
        assertEquals(true, second.getBoolean("hideDiscordLink"), "a default left out is kept and sent as kept");
    }
}
