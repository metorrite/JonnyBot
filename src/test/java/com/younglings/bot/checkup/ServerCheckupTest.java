package com.younglings.bot.checkup;

import com.younglings.bot.checkup.ServerCheckup.ChannelInfo;
import com.younglings.bot.checkup.ServerCheckup.Config;
import com.younglings.bot.checkup.ServerCheckup.Finding;
import com.younglings.bot.checkup.ServerCheckup.Level;
import com.younglings.bot.checkup.ServerCheckup.RoleInfo;
import com.younglings.bot.checkup.ServerCheckup.TrackingState;
import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.welcome.WelcomeConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerCheckupTest {
    private final Map<Long, ChannelInfo> channels = new HashMap<>();
    private final Map<Long, RoleInfo> roles = new HashMap<>();
    private boolean manageRoles;
    private final ServerCheckup.GuildView view = new ServerCheckup.GuildView() {
        @Override
        public ChannelInfo channel(long id) {
            return channels.get(id);
        }

        @Override
        public RoleInfo role(long id) {
            return roles.get(id);
        }

        @Override
        public boolean botCanManageRoles() {
            return manageRoles;
        }
    };

    @BeforeEach
    void setUp() {
        manageRoles = true;
    }

    private static GuildSettings settings(String clan, Long review, Long verifiedClanRole) {
        return new GuildSettings(1, clan, null, null, review, verifiedClanRole, null, null, null, clan != null, clan, null, null);
    }

    private static Config config(GuildSettings s) {
        return new Config(s, WelcomeConfig.defaults(1), List.of(), null);
    }

    private static List<Finding> only(List<Finding> all, Level level) {
        return all.stream().filter(f -> f.level() == level).toList();
    }

    @Test
    void anEmptyServerHasNothingToReport() {
        assertTrue(ServerCheckup.run(view, config(settings(null, null, null))).isEmpty());
    }

    @Test
    void aClanWithNoReviewChannelIsWarned() {
        List<Finding> findings = ServerCheckup.run(view, config(settings("Younglings", null, null)));

        assertEquals(1, only(findings, Level.WARN).size());
        assertEquals("settings", only(findings, Level.WARN).get(0).page());
    }

    @Test
    void aChannelTheBotCanPostInIsFine() {
        channels.put(10L, new ChannelInfo("link-requests", true, true, true));

        List<Finding> findings = ServerCheckup.run(view, config(settings("Younglings", 10L, null)));

        assertEquals(0, only(findings, Level.ERROR).size());
        assertEquals(0, only(findings, Level.WARN).size());
        assertEquals(1, only(findings, Level.OK).size());
    }

    @Test
    void aDeletedChannelIsAnError() {
        List<Finding> findings = ServerCheckup.run(view, config(settings("Younglings", 10L, null)));

        assertTrue(only(findings, Level.ERROR).get(0).title().contains("deleted"));
    }

    @Test
    void aChannelTheBotCannotSendInIsAnError() {
        channels.put(10L, new ChannelInfo("link-requests", true, false, true));

        List<Finding> findings = ServerCheckup.run(view, config(settings("Younglings", 10L, null)));

        assertTrue(only(findings, Level.ERROR).get(0).title().contains("can't send messages in #link-requests"));
    }

    @Test
    void missingEmbedLinksIsOnlyAWarning() {
        channels.put(10L, new ChannelInfo("link-requests", true, true, false));

        List<Finding> findings = ServerCheckup.run(view, config(settings("Younglings", 10L, null)));

        assertEquals(0, only(findings, Level.ERROR).size());
        assertEquals(1, only(findings, Level.WARN).size());
    }

    @Test
    void aRoleAboveTheBotIsAnErrorThatSaysWhatToDrag() {
        roles.put(20L, new RoleInfo("Member", false, false));

        List<Finding> findings = ServerCheckup.run(view, config(settings(null, null, 20L)));

        Finding error = only(findings, Level.ERROR).get(0);
        assertTrue(error.title().contains("@Member is above JonnyBot's role"));
        assertTrue(error.detail().contains("drag JonnyBot's role above @Member"));
    }

    @Test
    void anIntegrationRoleCannotBeHandedOut() {
        roles.put(20L, new RoleInfo("Booster", true, true));

        assertTrue(only(ServerCheckup.run(view, config(settings(null, null, 20L))), Level.ERROR).get(0).title().contains("can't be handed out"));
    }

    @Test
    void handingOutRolesWithoutManageRolesIsAnError() {
        manageRoles = false;
        roles.put(20L, new RoleInfo("Member", false, true));

        List<Finding> findings = ServerCheckup.run(view, config(settings(null, null, 20L)));

        assertTrue(only(findings, Level.ERROR).stream().anyMatch(f -> f.title().contains("can't manage roles")));
    }

    @Test
    void aSwitchedOffWelcomeIsNotChecked() {
        assertTrue(ServerCheckup.run(view, config(settings(null, null, null))).isEmpty());
    }

    @Test
    void anEnabledWelcomeWithoutAChannelIsAnError() {
        WelcomeConfig on = new WelcomeConfig(1, true, WelcomeConfig.MessageType.MESSAGE, null, false, "Hi", null, "", "", "", "", "", "", "", "", "", List.of(), false, "x", "primary", "small");

        List<Finding> findings = ServerCheckup.run(view, new Config(settings(null, null, null), on, List.of(), null));

        assertEquals("welcome", only(findings, Level.ERROR).get(0).page());
    }

    @Test
    void trackingFeedsAreCountedAndOnlyProblemsAreListed() {
        channels.put(30L, new ChannelInfo("drops", true, true, true));
        channels.put(31L, new ChannelInfo("quests", true, false, true));
        List<TrackingState> tracking = List.of(
                new TrackingState("Notable Drops", true, List.of(30L)),
                new TrackingState("Quests", true, List.of(31L)),
                new TrackingState("Pets", true, List.of()),
                new TrackingState("Archaeology", false, List.of()));

        List<Finding> findings = ServerCheckup.run(view, new Config(settings(null, null, null), WelcomeConfig.defaults(1), tracking, null));

        assertEquals(1, only(findings, Level.ERROR).size()); // quests: can't send
        assertEquals(1, only(findings, Level.WARN).size()); // pets: no channel
        assertTrue(only(findings, Level.OK).get(0).title().startsWith("1 announcement feed is"));
    }

    @Test
    void thePollChannelIsChecked() {
        List<Finding> findings = ServerCheckup.run(view, new Config(settings(null, null, null), WelcomeConfig.defaults(1), List.of(), 40L));

        assertEquals("community", only(findings, Level.ERROR).get(0).page());
    }
}
