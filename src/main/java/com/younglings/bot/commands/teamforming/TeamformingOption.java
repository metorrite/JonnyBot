package com.younglings.bot.commands.teamforming;

/**
 * One selectable tag within a {@link TeamformingSection}'s dropdown.
 *
 * @param label       text shown as the dropdown option
 * @param description grey subtitle text shown under the label in the dropdown
 * @param roleName    exact Discord role name this option grants/revokes; also used as the
 *                    option's underlying value, so a submitted selection maps straight back to a
 *                    role name with no separate lookup table
 */
public record TeamformingOption(String label, String description, String roleName) {
}
