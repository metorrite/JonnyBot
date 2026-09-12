package com.younglings.bot.commands.teamforming;

import java.awt.Color;

/**
 * One selectable tag within a {@link TeamformingSection}'s dropdown.
 *
 * @param label       text shown as the dropdown option
 * @param description grey subtitle text shown under the label in the dropdown
 * @param roleName    exact Discord role name this option grants; also used as the option's
 *                    underlying value, so a submitted selection maps straight back to a role name
 *                    with no separate lookup table
 * @param color       role color to use for this specific option, or {@code null} to inherit the
 *                    owning {@link TeamformingSection}'s color (used for "Group Encounters", where
 *                    each option is a different, unrelated boss rather than a shared one)
 */
public record TeamformingOption(String label, String description, String roleName, Color color) {
}
