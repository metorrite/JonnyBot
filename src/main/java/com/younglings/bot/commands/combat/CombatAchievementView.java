package com.younglings.bot.commands.combat;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementModels.Requirement;
import com.younglings.bot.discord.Containers;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * How an achievement looks when someone asks for it: its name linked to its own wiki page with the tier icon beside it, the tier and its CombatScore and
 * RuneScore, the boss it belongs to, the wiki's description, and what the wiki's own page says about doing it (strategy and tips), shortened to fit.
 * <p>
 * The tier icon is the wiki's own picture, because Discord has to fetch it from a public address; the same pictures are kept on the clan website for its pages.
 */
final class CombatAchievementView {
    private CombatAchievementView() {}

    /** Discord shows at most this much text across a message built from components; the tips take what's left after the fixed lines. */
    private static final int TIPS_MAX = 1700;
    static final String WIKI_IMAGES = "https://runescape.wiki/images/";
    private static final Color[] TIER_COLORS = {
            new Color(0x7FB069), new Color(0x4D9DE0), new Color(0xE1BC29), new Color(0xE15554), new Color(0x9B5DE5), new Color(0xF15BB5)};

    static String tierIconUrl(String tier) {
        return WIKI_IMAGES + "Combat_Mastery_-_" + tier + "_achievement_icon.png";
    }

    /** Wiki text for Discord: headings in bold, no more than {@code max} characters, and a mention of where the rest is. */
    static String tips(Achievement a, int max) {
        String text = a.wikiText() == null ? "" : a.wikiText();
        String summary = a.wikiSummary() == null ? "" : a.wikiSummary();
        // The summary is already said in the header area's description; the tips are what follows it.
        String rest = text.startsWith(summary) ? text.substring(summary.length()).strip() : text;
        rest = rest.replaceAll("(?m)^#{2,3} (.+)$", "**$1**");
        if (rest.length() <= max) return rest;

        String cut = rest.substring(0, max - 1);
        int paragraph = cut.lastIndexOf("\n\n");
        if (paragraph > max / 2) cut = cut.substring(0, paragraph);
        return cut.strip() + "…";
    }

    static Container card(Achievement a) {
        List<ContainerChildComponent> children = new ArrayList<>();

        String head = "### [" + a.name() + "](" + a.wikiUrl() + ")\n**" + a.tier() + "**  ·  " + a.combatScore() + " CombatScore  ·  " + a.runeScore() + " RuneScore  ·  "
                + (a.members() ? "Members" : "Free to play");
        children.add(Section.of(Thumbnail.fromUrl(tierIconUrl(a.tier())), TextDisplay.of(head)));

        StringBuilder body = new StringBuilder();
        if (a.subcategory() != null) {
            body.append("**Boss:** ").append(link(a.subcategory(), a.subcategoryUrl()));
            if (a.subsubcategory() != null) body.append(" › ").append(link(a.subsubcategory(), a.subsubcategoryUrl()));
            body.append("\n");
        }
        body.append(a.description());
        if (!a.requirements().isEmpty()) {
            body.append("\n\n**Achievements it lists:** ");
            body.append(String.join(", ", a.requirements().stream().map(Requirement::name).limit(12).toList()));
            if (a.requirements().size() > 12) body.append(", and ").append(a.requirements().size() - 12).append(" more");
        }
        children.add(TextDisplay.of(body.toString()));

        String tips = tips(a, TIPS_MAX);
        if (!tips.isBlank()) children.add(TextDisplay.of(tips));

        children.add(ActionRow.of(Button.link(a.wikiUrl(), "View on the RuneScape Wiki")));
        return Containers.card(TIER_COLORS[Math.max(0, Math.min(TIER_COLORS.length - 1, a.tierNumber() - 1))], children);
    }

    private static String link(String text, String url) {
        return url == null ? text : "[" + text + "](" + url + ")";
    }
}
