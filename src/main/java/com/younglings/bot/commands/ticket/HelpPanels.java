package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.HelpKind;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The standard PvM Help and CA Help ticket panels: their wording and questions in one place, and creating them in a server. An admin does that
 * from the website's PvM Help page and then sets each panel's category, staff and channel there like any other panel; the dev setup builds the
 * same panels under test names.
 */
@BService
public class HelpPanels {
    public static final String PVM_NAME = "PvM Help";
    public static final String CA_NAME = "CA Help";

    /** What {@link #createMissing} did: the panels it made and the ones that were already there. */
    public record Result(List<Panel> created, List<Panel> existing) {}

    private final TicketRepository repository;

    public HelpPanels(TicketRepository repository) {
        this.repository = repository;
    }

    // ================= creating =================

    /**
     * Creates the PvM Help and CA Help panels if the server doesn't have panels of those names yet; one that is already there is left exactly as
     * the admin has it. The helper roles saved in the PvM Help settings are set as each new panel's helper roles. Category, staff roles and the
     * channel to post in are left for the admin to set.
     */
    public Result createMissing(long guildId) {
        HelpSettings settings = repository.getHelpSettings(guildId);
        Set<Long> helperRoles = new java.util.LinkedHashSet<>();
        if (settings.helperRoleId() != null) helperRoles.add(settings.helperRoleId());
        if (settings.helperPlusRoleId() != null) helperRoles.add(settings.helperPlusRoleId());
        PanelRoles roles = new PanelRoles(helperRoles, Set.of(), Set.of());

        List<Panel> created = new ArrayList<>();
        List<Panel> existing = new ArrayList<>();
        for (PanelDefinition definition : List.of(pvm(guildId, PVM_NAME), ca(guildId, CA_NAME))) {
            Panel there = find(guildId, definition.panel().name());
            if (there != null) {
                existing.add(there);
                continue;
            }
            created.add(repository.getPanel(repository.saveDefinition(new PanelDefinition(definition.panel(), definition.fields(), roles))));
        }
        return new Result(created, existing);
    }

    private Panel find(long guildId, String name) {
        return repository.getPanels(guildId).stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    /**
     * Creates the panel, or replaces the earlier one of the same name so it matches this code (where it was posted is left alone). For a test setup
     * that should always show the latest wording; {@link #createMissing} is the careful one.
     */
    long replace(long guildId, PanelDefinition definition, PanelRoles roles) {
        Panel wanted = definition.panel();
        Panel there = find(guildId, wanted.name());
        Panel withId = there == null ? wanted : new Panel(there.id(), guildId, wanted.name(), wanted.title(), wanted.description(), wanted.buttonLabel(),
                wanted.categoryId(), wanted.channelNameTemplate(), wanted.welcomeText(), wanted.enabled(), wanted.perUserLimit(), wanted.defaultPingRoleId(),
                wanted.helperCap(), wanted.escalationHours(), wanted.defaultEscalateRoleId(), there.postedChannelId(), there.postedMessageId(),
                wanted.openingMessage(), wanted.closeByRequester(), wanted.closeByHelpers(), wanted.helpKind());
        return repository.saveDefinition(new PanelDefinition(withId, definition.fields(), roles));
    }

    // ================= the panels =================

    private static List<Option> tiers() {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < HelpRules.TIERS.size(); i++) options.add(new Option(0, 0, i, HelpRules.TIERS.get(i), null, null));
        return options;
    }

    /** General PvM help: a boss is needed, a tier and a specific achievement are optional. Advice only; helpers don't go in game. */
    public static PanelDefinition pvm(long guildId, String name) {
        Panel panel = new Panel(0, guildId, name, "PvM Help",
                "Stuck on a boss, or want to get better at one? Open a ticket and a helper will give you advice, guides, tips and resources, or review a recording of your attempts.\n"
                        + "~<LS>~\n"
                        + "This is advice, not a carry: helpers don't go in game and run the content for you. Looking for a group? Try #teamforming first.",
                "Ask for PvM help", null, "pvm-{number}",
                "A helper will be with you soon. They'll give advice, guides and tips here, or review a recording of your attempt. They won't join you in game.",
                true, 1, null, 2, null, null, null, null, Panel.DEFAULT_OPENING, true, true, HelpKind.PVM);
        return new PanelDefinition(panel, fields(false), PanelRoles.none());
    }

    /** CA help: a boss and a tier are needed and one achievement is optional. Helpers do go in and help with the achievement asked for. */
    public static PanelDefinition ca(long guildId, String name) {
        Panel panel = new Panel(0, guildId, name, "Combat Achievement Help",
                "Working on a Combat Achievement? Pick the boss and the tier. You can also name one specific achievement, and if you do we will only help with that one.",
                "Ask for CA help", null, "ca-{number}",
                "A helper will be with you soon. If you named one achievement they will only help with that one.",
                true, 1, null, 2, null, null, null, null, Panel.DEFAULT_OPENING, true, true, HelpKind.CA);
        return new PanelDefinition(panel, fields(true), PanelRoles.none());
    }

    /**
     * The questions of a help panel. The boss, tier and achievement are chosen from lists in the guided flow ({@link HelpTicketFlow}), so their
     * kinds here only say how the answer is filed; the checkbox and the last box are the small form at the end. {@code tierRequired} is CA Help.
     */
    private static List<Field> fields(boolean tierRequired) {
        return List.of(
                new Field(0, 0, 0, "Which boss?", FieldKind.SHORT, true, null, 100, List.of(), FieldPurpose.BOSS),
                new Field(0, 0, 1, tierRequired ? "Tier" : "Tier (optional)", FieldKind.SELECT, tierRequired, null, null, tiers(), FieldPurpose.TIER),
                new Field(0, 0, 2, "Specific achievement? (Optional)", FieldKind.SHORT, false, null, 100, List.of(), FieldPurpose.ACHIEVEMENT),
                new Field(0, 0, 3, "Have you already made attempts yourself?", FieldKind.CHECKBOX, false, null, null, List.of(), FieldPurpose.ATTEMPTS),
                // A text box's hint holds 100 characters at most, so this is the long wording shortened.
                new Field(0, 0, 4, "Where you stand / what you need", FieldKind.PARAGRAPH, false,
                        "Where you currently stand with this boss/CA, and what you need help with", 500, List.of(), FieldPurpose.NONE));
    }
}
