package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.ClanPointsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Configure Points & Ranks" tool on {@code /rsadmin}'s panel — lets an admin set the three point
 * values (daily membership, Citadel visit, Citadel cap) and each clan rank's point threshold, both of
 * which {@code ClanPointsService} reads every day to award points and decide who needs a promotion.
 * <p>
 * A separate listener from {@link RsAdminInteractionListener}, same reasoning as
 * {@link PruneInteractionListener}. Ranks are edited in pages of up to 5 (a modal's own component
 * cap) rather than one button/modal per rank — with the standard 11-rank ladder that's 3 modals to
 * fully configure instead of 11.
 */
@BService
public class ClanPointsInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ClanPointsInteractionListener.class);
    private static final int RANKS_PER_PAGE = 5;

    private final AdminRoleFilter adminRoleFilter;
    private final ClanPointsRepository repository;

    public ClanPointsInteractionListener(AdminRoleFilter adminRoleFilter, ClanPointsRepository repository) {
        this.adminRoleFilter = adminRoleFilter;
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("clanpoints_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "clanpoints_open" -> event.replyComponents(List.of(buildMainPanel(guild.getIdLong())))
                        .useComponentsV2(true).setEphemeral(true).queue();
                case "clanpoints_main" -> event.editComponents(List.of(buildMainPanel(guild.getIdLong()))).useComponentsV2(true).queue();
                case "clanpoints_edit_values" -> event.replyModal(buildValuesModal(guild.getIdLong())).queue();
                case "clanpoints_edit_ranks" -> event.replyModal(buildRanksModal(guild.getIdLong(), Integer.parseInt(parts[1]))).queue();
            }
        } catch (Exception e) {
            log.error("Unhandled exception in clan points button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("clanpoints_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "clanpoints_values_modal" -> handleValuesModal(event, guild);
                case "clanpoints_ranks_modal" -> handleRanksModal(event, guild, Integer.parseInt(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in clan points modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private Modal buildValuesModal(long guildId) {
        ClanPointsRepository.PointsSettings settings = repository.getSettings(guildId);

        TextInput daily = TextInput.create("daily_points", TextInputStyle.SHORT)
                .setValue(String.valueOf(settings.dailyMembershipPoints())).setRequired(true).build();
        TextInput visit = TextInput.create("visit_points", TextInputStyle.SHORT)
                .setValue(String.valueOf(settings.citadelVisitPoints())).setRequired(true).build();
        TextInput cap = TextInput.create("cap_points", TextInputStyle.SHORT)
                .setValue(String.valueOf(settings.citadelCapPoints())).setRequired(true).build();

        return Modal.create("clanpoints_values_modal:_", "Edit Point Values")
                .addComponents(
                        Label.of("Points per day in the clan", daily),
                        Label.of("Points per Citadel visit", visit),
                        Label.of("Points per Citadel cap", cap))
                .build();
    }

    private void handleValuesModal(ModalInteractionEvent event, Guild guild) {
        Long daily = parseNonNegative(event.getValue("daily_points").getAsString());
        Long visit = parseNonNegative(event.getValue("visit_points").getAsString());
        Long cap = parseNonNegative(event.getValue("cap_points").getAsString());

        if (daily == null || visit == null || cap == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "All three values must be whole numbers 0 or greater.");
            return;
        }

        repository.setSettings(guild.getIdLong(), daily, visit, cap);
        event.editComponents(List.of(buildMainPanel(guild.getIdLong()))).useComponentsV2(true).queue();
    }

    private Modal buildRanksModal(long guildId, int page) {
        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);
        List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ranks, page);

        List<Label> labels = new ArrayList<>();
        for (var rank : pageRanks) {
            TextInput input = TextInput.create("rank_" + rank.id(), TextInputStyle.SHORT)
                    .setValue(String.valueOf(rank.pointThreshold())).setRequired(true).build();
            labels.add(Label.of(rank.rankName() + " — point threshold", input));
        }

        return Modal.create("clanpoints_ranks_modal:" + page, "Edit Rank Thresholds").addComponents(labels).build();
    }

    private void handleRanksModal(ModalInteractionEvent event, Guild guild, int page) {
        long guildId = guild.getIdLong();
        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);
        List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ranks, page);

        Map<Long, Long> parsedThresholds = new LinkedHashMap<>();
        for (var rank : pageRanks) {
            Long threshold = parseNonNegative(event.getValue("rank_" + rank.id()).getAsString());
            if (threshold == null) {
                Containers.replyEphemeral(event, Containers.WARNING,
                        "\"" + rank.rankName() + "\"'s threshold must be a whole number 0 or greater — nothing on this page was saved.");
                return;
            }
            parsedThresholds.put(rank.id(), threshold);
        }

        // Applied only after every field on the page validates — an all-or-nothing save, since a
        // half-applied page would be confusing (some ranks updated, the rest silently not).
        parsedThresholds.forEach((rankId, threshold) -> repository.setRankThreshold(guildId, rankId, threshold));

        event.editComponents(List.of(buildMainPanel(guildId))).useComponentsV2(true).queue();
    }

    private static List<ClanPointsRepository.RankConfigRow> pageOf(List<ClanPointsRepository.RankConfigRow> ranks, int page) {
        int from = Math.min(page * RANKS_PER_PAGE, ranks.size());
        int to = Math.min(from + RANKS_PER_PAGE, ranks.size());
        return ranks.subList(from, to);
    }

    private static Long parseNonNegative(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            return value >= 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    Container buildMainPanel(long guildId) {
        ClanPointsRepository.PointsSettings settings = repository.getSettings(guildId);
        List<ClanPointsRepository.RankConfigRow> ranks = repository.getRanksOrdered(guildId);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Points & Promotions"));
        children.add(TextDisplay.of("-# Points are awarded daily for clan membership and weekly for Citadel visits/caps. A member is flagged for promotion once their total points meet a higher rank's threshold than their current in-game rank — see the daily \"Clan Report\" in the Tracking panel."));

        children.add(TextDisplay.of("**Points per day in the clan:** " + settings.dailyMembershipPoints() +
                "\n**Points per Citadel visit:** " + settings.citadelVisitPoints() +
                "\n**Points per Citadel cap:** " + settings.citadelCapPoints()));
        children.add(ActionRow.of(Button.secondary("clanpoints_edit_values:_", "Edit Point Values")));

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("**Rank Thresholds** (lowest to highest):"));

        StringBuilder list = new StringBuilder();
        for (var rank : ranks) list.append("**").append(rank.rankName()).append("** — ").append(rank.pointThreshold()).append(" pts\n");
        children.add(TextDisplay.of(list.toString().trim()));

        List<Button> pageButtons = new ArrayList<>();
        int pageCount = (ranks.size() + RANKS_PER_PAGE - 1) / RANKS_PER_PAGE;
        for (int page = 0; page < pageCount; page++) {
            List<ClanPointsRepository.RankConfigRow> pageRanks = pageOf(ranks, page);
            String label = pageRanks.size() == 1
                    ? "Edit " + pageRanks.getFirst().rankName()
                    : "Edit " + pageRanks.getFirst().rankName() + "–" + pageRanks.getLast().rankName();
            pageButtons.add(Button.secondary("clanpoints_edit_ranks:" + page, label));
        }
        if (!pageButtons.isEmpty()) children.add(ActionRow.of(pageButtons));

        return Containers.card(Containers.PRIMARY, children);
    }
}
