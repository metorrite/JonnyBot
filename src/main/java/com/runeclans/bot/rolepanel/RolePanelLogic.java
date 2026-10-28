package com.runeclans.bot.rolepanel;

import com.runeclans.bot.rolepanel.RolePanel.RoleButton;
import com.runeclans.bot.rolepanel.RolePanel.Rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.LongFunction;

/** What choosing something on a role panel does, decided without Discord so every style and rule is testable. {@code held} is the roles the member has now. */
public final class RolePanelLogic {
    private RolePanelLogic() {}

    /** Roles to add and to remove, and what to tell the person. Either list may be empty. */
    public record Change(List<Long> add, List<Long> remove, String message) {
        public boolean none() {
            return add.isEmpty() && remove.isEmpty();
        }
    }

    /** A change from a reaction: also whether the person's own reaction should be taken back, and the roles whose reactions of theirs go too (unique mode). */
    public record ReactionChange(Change change, boolean dropThisReaction, List<Long> dropOtherReactionsOfRoles) {}

    /** A button pressed. */
    public static Change button(RolePanel panel, RoleButton pressed, Set<Long> held, String roleName) {
        boolean has = held.contains(pressed.roleId());
        if (has) {
            if (panel.rule() == Rule.ADD_ONLY) return new Change(List.of(), List.of(), "You already have **" + roleName + "**. This panel only gives roles.");
            return new Change(List.of(), List.of(pressed.roleId()), "Removed **" + roleName + "**.");
        }
        if (panel.rule() == Rule.REMOVE_ONLY) return new Change(List.of(), List.of(), "You do not have **" + roleName + "**. This panel only takes roles away.");
        List<Long> remove = othersToSwapOut(panel, pressed.roleId(), held);
        return new Change(List.of(pressed.roleId()), remove, remove.isEmpty() ? "You now have **" + roleName + "**." : "You now have **" + roleName + "** instead.");
    }

    /** The panel's other roles a member holds, when the panel lets them hold only one. */
    private static List<Long> othersToSwapOut(RolePanel panel, long chosen, Set<Long> held) {
        List<Long> remove = new ArrayList<>();
        if (panel.mode() == RolePanel.Mode.UNIQUE) {
            for (RoleButton other : panel.buttons()) if (other.roleId() != chosen && held.contains(other.roleId())) remove.add(other.roleId());
        }
        return remove;
    }

    /**
     * A drop-down submitted with {@code selected} (role ids). Normal: the member ends up with exactly the selected roles of this panel, so
     * choosing nothing drops them all. Add-only: the selected roles are added, and in unique mode the others are swapped out. Remove-only: the
     * selected roles the member holds are removed.
     */
    public static Change menu(RolePanel panel, List<Long> selected, Set<Long> held, LongFunction<String> roleName) {
        Set<Long> panelRoles = new LinkedHashSet<>();
        panel.buttons().forEach(b -> panelRoles.add(b.roleId()));
        Set<Long> chosen = new LinkedHashSet<>(selected.stream().filter(panelRoles::contains).toList());
        if (panel.mode() == RolePanel.Mode.UNIQUE && chosen.size() > 1) chosen = new LinkedHashSet<>(List.of(chosen.iterator().next()));

        List<Long> add = new ArrayList<>();
        List<Long> remove = new ArrayList<>();
        switch (panel.rule()) {
            case REMOVE_ONLY -> {
                for (long r : chosen) if (held.contains(r)) remove.add(r);
            }
            case ADD_ONLY -> {
                for (long r : chosen) if (!held.contains(r)) add.add(r);
                if (panel.mode() == RolePanel.Mode.UNIQUE && !add.isEmpty()) {
                    for (long r : panelRoles) if (held.contains(r) && !chosen.contains(r)) remove.add(r);
                }
            }
            default -> {
                for (long r : chosen) if (!held.contains(r)) add.add(r);
                for (long r : panelRoles) if (held.contains(r) && !chosen.contains(r)) remove.add(r);
            }
        }
        if (add.isEmpty() && remove.isEmpty()) return new Change(add, remove, "Nothing changed.");
        List<String> parts = new ArrayList<>();
        if (!add.isEmpty()) parts.add("Added " + names(add, roleName) + ".");
        if (!remove.isEmpty()) parts.add("Removed " + names(remove, roleName) + ".");
        return new Change(add, remove, String.join(" ", parts));
    }

    private static String names(List<Long> ids, LongFunction<String> roleName) {
        return String.join(", ", ids.stream().map(id -> "**" + roleName.apply(id) + "**").toList());
    }

    /**
     * A reaction added to (or removed from) the panel's message, for the role of its emoji. Normal: adding gives the role, removing takes it.
     * Add-only: adding gives it, removing does nothing. Remove-only: adding takes the role away (and the reaction is taken back), removing does nothing.
     */
    public static ReactionChange reaction(RolePanel panel, long roleId, boolean added, Set<Long> held) {
        Change none = new Change(List.of(), List.of(), "");
        if (!added) {
            if (panel.rule() == Rule.NORMAL && held.contains(roleId)) return new ReactionChange(new Change(List.of(), List.of(roleId), ""), false, List.of());
            return new ReactionChange(none, false, List.of());
        }
        if (panel.rule() == Rule.REMOVE_ONLY) {
            return new ReactionChange(held.contains(roleId) ? new Change(List.of(), List.of(roleId), "") : none, true, List.of());
        }
        List<Long> swap = othersToSwapOut(panel, roleId, held);
        List<Long> add = held.contains(roleId) ? List.of() : List.of(roleId);
        List<Long> otherReactions = panel.mode() == RolePanel.Mode.UNIQUE
                ? panel.buttons().stream().map(RoleButton::roleId).filter(r -> r != roleId).toList() : List.of();
        return new ReactionChange(new Change(add, swap, ""), false, otherReactions);
    }
}
