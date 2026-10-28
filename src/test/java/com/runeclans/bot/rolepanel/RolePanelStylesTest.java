package com.runeclans.bot.rolepanel;

import com.runeclans.bot.rolepanel.RolePanel.Mode;
import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import com.runeclans.bot.rolepanel.RolePanel.Rule;
import com.runeclans.bot.rolepanel.RolePanel.Style;
import com.runeclans.bot.rolepanel.RolePanelLogic.Change;
import com.runeclans.bot.rolepanel.RolePanelLogic.ReactionChange;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dropdown and reaction styles of a role panel and its add-only / remove-only rules: what each choice does, and the messages built. */
class RolePanelStylesTest {
    private static RolePanel panel(Style style, Mode mode, Rule rule, long... roles) {
        List<RoleButton> buttons = java.util.Arrays.stream(roles).mapToObj(r -> new RoleButton(r, "", r % 2 == 0 ? "🔴" : "🔵", "primary")).toList();
        return new RolePanel(1L, 5L, "Teams", "", "", null, mode, null, null, buttons, style, rule, false);
    }

    private static RoleButton btn(long role) {
        return new RoleButton(role, "", null, "primary");
    }

    // ---- buttons under each rule

    @Test
    void addOnlyButtonsNeverTakeARoleAway() {
        RolePanel p = panel(Style.BUTTONS, Mode.TOGGLE, Rule.ADD_ONLY, 1, 2);
        assertEquals(List.of(1L), RolePanelLogic.button(p, btn(1), Set.of(), "Red").add());
        Change again = RolePanelLogic.button(p, btn(1), Set.of(1L), "Red");
        assertTrue(again.none(), "pressing a role already held does nothing");
        assertTrue(again.message().contains("only gives"));
    }

    @Test
    void removeOnlyButtonsNeverGiveARole() {
        RolePanel p = panel(Style.BUTTONS, Mode.TOGGLE, Rule.REMOVE_ONLY, 1, 2);
        assertEquals(List.of(1L), RolePanelLogic.button(p, btn(1), Set.of(1L), "Red").remove());
        Change lacks = RolePanelLogic.button(p, btn(1), Set.of(), "Red");
        assertTrue(lacks.none());
        assertTrue(lacks.message().contains("only takes"));
    }

    @Test
    void uniqueStillSwapsWhenAddOnly() {
        RolePanel p = panel(Style.BUTTONS, Mode.UNIQUE, Rule.ADD_ONLY, 1, 2, 3);
        Change change = RolePanelLogic.button(p, btn(2), Set.of(1L, 99L), "Blue");
        assertEquals(List.of(2L), change.add());
        assertEquals(List.of(1L), change.remove());
    }

    // ---- drop-down

    @Test
    void normalMenuLeavesTheMemberWithExactlyTheSelectedRolesOfThePanel() {
        RolePanel p = panel(Style.DROPDOWN, Mode.TOGGLE, Rule.NORMAL, 1, 2, 3);
        Change change = RolePanelLogic.menu(p, List.of(2L, 3L), Set.of(1L, 3L, 99L), id -> "r" + id);
        assertEquals(List.of(2L), change.add());
        assertEquals(List.of(1L), change.remove(), "role 99 is not on the panel so it is never touched");
        assertTrue(change.message().contains("Added **r2**") && change.message().contains("Removed **r1**"));
    }

    @Test
    void choosingNothingFromANormalMenuDropsThePanelRoles() {
        RolePanel p = panel(Style.DROPDOWN, Mode.TOGGLE, Rule.NORMAL, 1, 2);
        Change change = RolePanelLogic.menu(p, List.of(), Set.of(1L, 2L, 99L), id -> "r");
        assertEquals(List.of(1L, 2L), change.remove());
        assertTrue(change.add().isEmpty());
    }

    @Test
    void roleIdsNotOnThePanelAreIgnoredSoAForgedChoiceGivesNothing() {
        RolePanel p = panel(Style.DROPDOWN, Mode.TOGGLE, Rule.NORMAL, 1, 2);
        Change change = RolePanelLogic.menu(p, List.of(777L), Set.of(), id -> "r");
        assertTrue(change.none());
        assertEquals("Nothing changed.", change.message());
    }

    @Test
    void uniqueMenuTakesOnlyTheFirstChoice() {
        RolePanel p = panel(Style.DROPDOWN, Mode.UNIQUE, Rule.NORMAL, 1, 2, 3);
        Change change = RolePanelLogic.menu(p, List.of(2L, 3L), Set.of(1L), id -> "r");
        assertEquals(List.of(2L), change.add());
        assertEquals(List.of(1L), change.remove());
    }

    @Test
    void addOnlyMenuOnlyAddsAndRemoveOnlyMenuOnlyRemoves() {
        RolePanel add = panel(Style.DROPDOWN, Mode.TOGGLE, Rule.ADD_ONLY, 1, 2);
        Change a = RolePanelLogic.menu(add, List.of(2L), Set.of(1L), id -> "r");
        assertEquals(List.of(2L), a.add());
        assertTrue(a.remove().isEmpty(), "role 1 is not selected but add-only never drops");

        RolePanel remove = panel(Style.DROPDOWN, Mode.TOGGLE, Rule.REMOVE_ONLY, 1, 2);
        Change r = RolePanelLogic.menu(remove, List.of(1L, 2L), Set.of(1L), id -> "r");
        assertTrue(r.add().isEmpty());
        assertEquals(List.of(1L), r.remove(), "only a role actually held can be removed");
    }

    // ---- reactions

    @Test
    void normalReactionsGiveOnAddAndTakeOnRemove() {
        RolePanel p = panel(Style.REACTIONS, Mode.TOGGLE, Rule.NORMAL, 1, 2);
        ReactionChange added = RolePanelLogic.reaction(p, 1, true, Set.of());
        assertEquals(List.of(1L), added.change().add());
        assertFalse(added.dropThisReaction());
        assertEquals(List.of(1L), RolePanelLogic.reaction(p, 1, false, Set.of(1L)).change().remove());
        assertTrue(RolePanelLogic.reaction(p, 1, false, Set.of()).change().none(), "removing a reaction for a role not held does nothing");
    }

    @Test
    void addOnlyReactionsIgnoreRemoval() {
        RolePanel p = panel(Style.REACTIONS, Mode.TOGGLE, Rule.ADD_ONLY, 1, 2);
        assertEquals(List.of(1L), RolePanelLogic.reaction(p, 1, true, Set.of()).change().add());
        assertTrue(RolePanelLogic.reaction(p, 1, false, Set.of(1L)).change().none());
    }

    @Test
    void removeOnlyReactionsTakeTheRoleAndTheReactionBack() {
        RolePanel p = panel(Style.REACTIONS, Mode.TOGGLE, Rule.REMOVE_ONLY, 1, 2);
        ReactionChange held = RolePanelLogic.reaction(p, 1, true, Set.of(1L));
        assertEquals(List.of(1L), held.change().remove());
        assertTrue(held.change().add().isEmpty());
        assertTrue(held.dropThisReaction());
        ReactionChange lacks = RolePanelLogic.reaction(p, 1, true, Set.of());
        assertTrue(lacks.change().none());
        assertTrue(lacks.dropThisReaction());
        assertTrue(RolePanelLogic.reaction(p, 1, false, Set.of(1L)).change().none(), "removing the reaction does nothing");
    }

    @Test
    void uniqueReactionsSwapRolesAndAskForTheOtherReactionsToGo() {
        RolePanel p = panel(Style.REACTIONS, Mode.UNIQUE, Rule.NORMAL, 1, 2, 3);
        ReactionChange change = RolePanelLogic.reaction(p, 2, true, Set.of(1L, 99L));
        assertEquals(List.of(2L), change.change().add());
        assertEquals(List.of(1L), change.change().remove());
        assertEquals(List.of(1L, 3L), change.dropOtherReactionsOfRoles());
    }

    @Test
    void aReactionForARoleAlreadyHeldDoesNotAddItAgain() {
        RolePanel p = panel(Style.REACTIONS, Mode.TOGGLE, Rule.NORMAL, 1, 2);
        assertTrue(RolePanelLogic.reaction(p, 1, true, Set.of(1L)).change().none());
    }

    // ---- what gets posted

    @Test
    void aDropdownPanelPostsOneMenuWithAnOptionPerRoleAndNoButtons() {
        MessageCreateData data = RolePanelMessage.build(panel(Style.DROPDOWN, Mode.TOGGLE, Rule.NORMAL, 1, 2, 3), id -> "Role " + id);
        assertEquals(1, data.getComponents().size());
        StringSelectMenu menu = data.getComponents().get(0).asActionRow().getComponents().get(0).asStringSelectMenu();
        assertEquals("rr:1:menu", menu.getCustomId());
        assertEquals(3, menu.getOptions().size());
        assertEquals("1", menu.getOptions().get(0).getValue());
        assertEquals(3, menu.getMaxValues());
        assertEquals(0, menu.getMinValues());
    }

    @Test
    void aUniqueDropdownAllowsOneChoice() {
        MessageCreateData data = RolePanelMessage.build(panel(Style.DROPDOWN, Mode.UNIQUE, Rule.NORMAL, 1, 2, 3), id -> "Role " + id);
        assertEquals(1, data.getComponents().get(0).asActionRow().getComponents().get(0).asStringSelectMenu().getMaxValues());
    }

    @Test
    void aDropdownKeepsToDiscordsTwentyFiveOptions() {
        long[] many = IntStream.rangeClosed(1, 30).mapToLong(i -> i).toArray();
        MessageCreateData data = RolePanelMessage.build(panel(Style.DROPDOWN, Mode.TOGGLE, Rule.NORMAL, many), id -> "Role " + id);
        assertEquals(25, data.getComponents().get(0).asActionRow().getComponents().get(0).asStringSelectMenu().getOptions().size());
    }

    @Test
    void aReactionsPanelHasNoComponentsAndListsWhatEachEmojiGives() {
        MessageCreateData data = RolePanelMessage.build(panel(Style.REACTIONS, Mode.TOGGLE, Rule.NORMAL, 1, 2), id -> "Role " + id);
        assertTrue(data.getComponents().isEmpty());
        String description = data.getEmbeds().get(0).getDescription();
        assertTrue(description.contains("🔵  Role 1") && description.contains("🔴  Role 2"), description);
    }

    @Test
    void aButtonsPanelStillPostsButtons() {
        MessageCreateData data = RolePanelMessage.build(panel(Style.BUTTONS, Mode.TOGGLE, Rule.NORMAL, 1, 2, 3, 4, 5, 6), id -> "Role " + id);
        assertEquals(2, data.getComponents().size());
    }

    // ---- reactions matching, links

    @Test
    void anEmojiMatchesItsReactionCode() {
        assertTrue(RolePanelReactionListener.sameEmoji("🔴", net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🔴").getAsReactionCode()));
        assertFalse(RolePanelReactionListener.sameEmoji("🔴", net.dv8tion.jda.api.entities.emoji.Emoji.fromUnicode("🔵").getAsReactionCode()));
        assertFalse(RolePanelReactionListener.sameEmoji(null, "🔴"));
    }

    @Test
    void aMessageLinkIsReadAndAnythingElseIsNot() {
        var link = RolePanelService.parseLink(" https://discord.com/channels/111/222/333 ");
        assertEquals(111L, link.guildId());
        assertEquals(222L, link.channelId());
        assertEquals(333L, link.messageId());
        assertEquals(333L, RolePanelService.parseLink("https://ptb.discord.com/channels/1/2/333").messageId());
        assertEquals(333L, RolePanelService.parseLink("https://discordapp.com/channels/1/2/333").messageId());
        assertNull(RolePanelService.parseLink("https://discord.com/channels/@me/222/333"));
        assertNull(RolePanelService.parseLink("https://evil.example/channels/1/2/3"));
        assertNull(RolePanelService.parseLink("hello"));
        assertNull(RolePanelService.parseLink(null));
    }

    @Test
    void styleLimitsAreDiscordsLimits() {
        assertEquals(20, Style.BUTTONS.maxItems());
        assertEquals(25, Style.DROPDOWN.maxItems());
        assertEquals(20, Style.REACTIONS.maxItems());
    }
}
