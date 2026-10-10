package com.younglings.bot.welcome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The variables a welcome message can use, filled in for one member. */
class WelcomeTemplateTest {
    /** A member called Ryan joining The Younglings, with one role, one channel and one other member that can be found by name. */
    private static final WelcomeTemplate.Lookup LOOKUP = new WelcomeTemplate.Lookup() {
        public String userMention() { return "<@42>"; }
        public String username() { return "PvmRyan"; }
        public String avatarUrl() { return "https://cdn.example/avatar.png"; }
        public String serverName() { return "The Younglings"; }
        public String channelName() { return "welcome"; }
        public int memberCount() { return 128; }
        public String findUser(String name) { return name.equalsIgnoreCase("Metorrite") ? "<@7>" : null; }
        public String findRole(String name) { return name.equalsIgnoreCase("Admin") ? "<@&900>" : null; }
        public String findChannel(String name) { return name.equalsIgnoreCase("verify") ? "<#800>" : null; }
    };

    @Test
    void theNewMemberVariablesAreFilledIn() {
        assertEquals("Welcome to The Younglings, <@42> 👋", WelcomeTemplate.render("Welcome to {server}, {user} 👋", LOOKUP));
        assertEquals("Hi PvmRyan, you are member 128 in #welcome", WelcomeTemplate.render("Hi {username}, you are member {count} in #{channel}", LOOKUP));
        assertEquals("https://cdn.example/avatar.png", WelcomeTemplate.render("{avatar}", LOOKUP));
    }

    @Test
    void namesAreLookedUpForMembersRolesAndChannels() {
        assertEquals("Ask <@7>, an <@&900>, or head to <#800>.", WelcomeTemplate.render("Ask {@Metorrite}, an {&Admin}, or head to {#verify}.", LOOKUP));
    }

    @Test
    void aNameThatCantBeFoundIsShownPlainNotLost() {
        assertEquals("Ask @Nobody, a @Ghost, or #nowhere.", WelcomeTemplate.render("Ask {@Nobody}, a {&Ghost}, or {#nowhere}.", LOOKUP));
    }

    @Test
    void everyoneAndHereAreShownButNeverMentionedByThemselves() {
        assertEquals("@everyone @here", WelcomeTemplate.render("{everyone} {here}", LOOKUP));
    }

    @Test
    void anythingThatIsNotAVariableIsLeftAsTyped() {
        assertEquals("{unknown} {} { user } {user", WelcomeTemplate.render("{unknown} {} { user } {user", LOOKUP));
        assertEquals("", WelcomeTemplate.render(null, LOOKUP));
        assertEquals("", WelcomeTemplate.render("", LOOKUP));
    }

    @Test
    void regexCharactersInANameDontBreakTheFilling() {
        var dollars = new WelcomeTemplate.Lookup() {
            public String userMention() { return "<@1>"; }
            public String username() { return "Mr $1 \\ Bob"; }
            public String avatarUrl() { return ""; }
            public String serverName() { return "A$B\\C"; }
            public String channelName() { return ""; }
            public int memberCount() { return 1; }
            public String findUser(String name) { return null; }
            public String findRole(String name) { return null; }
            public String findChannel(String name) { return null; }
        };
        assertEquals("Mr $1 \\ Bob joined A$B\\C", WelcomeTemplate.render("{username} joined {server}", dollars));
    }

    @Test
    void inATitleOrFooterTheMemberIsTheirNameBecauseAMentionWouldShowAsAnId() {
        assertEquals("Welcome PvmRyan", WelcomeTemplate.renderPlain("Welcome {user}", LOOKUP));
        assertEquals("See #verify and @Admin", WelcomeTemplate.renderPlain("See {#verify} and {&Admin}", LOOKUP));
        assertEquals("The Younglings", WelcomeTemplate.renderPlain("{server}", LOOKUP));
    }

    @Test
    void theEditorsListOfVariablesCoversEveryOneThatIsHandled() {
        var names = WelcomeTemplate.VARIABLES.stream().map(v -> v[0]).toList();
        for (String token : new String[]{"{user}", "{username}", "{avatar}", "{server}", "{channel}", "{count}", "{@name}", "{&role}", "{#channel}", "{everyone}", "{here}"}) {
            org.junit.jupiter.api.Assertions.assertTrue(names.contains(token), token);
        }
    }
}
