package com.younglings.bot.commands.teamforming;

import java.util.List;

/**
 * One boss/category block in the teamforming panel: a header (emoji + title + a one-line prompt)
 * followed by a multi-select dropdown of {@link TeamformingOption} tags.
 *
 * @param key               short, stable identifier used in the select menu's component ID —
 *                          changing this after the panel has been posted breaks existing dropdowns
 *                          until the panel is re-posted
 * @param emoji             emoji shown before the section title
 * @param title             boss/category display name
 * @param prompt            one-line instruction shown under the title
 * @param selectPlaceholder placeholder text shown on the (unopened) dropdown
 * @param options           the tags selectable in this section's dropdown
 */
public record TeamformingSection(String key, String emoji, String title, String prompt,
                                  String selectPlaceholder, List<TeamformingOption> options) {
}
