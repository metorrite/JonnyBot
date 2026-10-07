package com.younglings.bot.commands.ticket;

import com.younglings.bot.combat.CombatAchievementModels.Achievement;
import com.younglings.bot.combat.CombatAchievementRepository;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How a PvM Help or CA Help ticket is asked for. One long form can't hold a boss list (Discord caps a dropdown at 25 choices, and there are 39
 * bosses and dungeons) or an achievement list that depends on the boss and tier chosen, so it is a short guided flow, all in a private message
 * that only the member sees:
 * <ol>
 *   <li>the boss, from the achievement catalogue (a dungeon is one entry, not each boss inside it),</li>
 *   <li>the tier (optional on PvM Help, required on CA Help if the panel's tier question is),</li>
 *   <li>a specific achievement for that boss and tier (always optional), and</li>
 *   <li>a small form with the panel's other questions, such as whether they have already made attempts.</li>
 * </ol>
 * The panel's own questions still hold the answers: the boss, tier and achievement are filed under the questions marked for them, so the ticket,
 * the transcript and the rules read exactly as they would from one form. Nothing is held in memory; each step's component ids carry what was chosen.
 */
@BService
public class HelpTicketFlow extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HelpTicketFlow.class);

    static final String BOSS_PREFIX = "tickethelp_boss:";
    static final String TIER_PREFIX = "tickethelp_tier:";
    static final String ACHIEVEMENT_PREFIX = "tickethelp_ach:";
    static final String SKIP_PREFIX = "tickethelp_skip:";
    static final String FORM_PREFIX = "tickethelp_form:";
    /** A dropdown holds at most this many choices. */
    static final int MAX_CHOICES = 25;
    private static final int DESCRIPTION_MAX = 100;

    private final TicketRepository repository;
    private final TicketService service;
    private final CombatAchievementRepository catalog;

    public HelpTicketFlow(TicketRepository repository, TicketService service, CombatAchievementRepository catalog) {
        this.repository = repository;
        this.service = service;
        this.catalog = catalog;
    }

    // ================= rules (no Discord in them) =================

    /** A help panel with a question marked as the boss is filled in through this flow; anything else keeps the single form. */
    public static boolean applies(Panel panel, List<Field> fields) {
        return panel.isHelpPanel() && fields.stream().anyMatch(f -> f.purpose() == FieldPurpose.BOSS);
    }

    /** Splits a list into the fewest groups of at most {@code max}, as even as possible, keeping order. */
    static <T> List<List<T>> balancedChunks(List<T> items, int max) {
        if (items.isEmpty()) return List.of();
        int groups = (items.size() + max - 1) / max;
        int per = (items.size() + groups - 1) / groups;
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < items.size(); i += per) chunks.add(items.subList(i, Math.min(items.size(), i + per)));
        return chunks;
    }

    /** What the panel's own questions are answered with: the flow's choices under the questions marked for them, and the small form's answers under the rest. */
    static Map<Long, String> rawValues(List<Field> fields, String boss, String tier, String achievement, Map<Long, String> formValues) {
        Map<Long, String> raw = new HashMap<>(formValues);
        for (Field field : fields) {
            switch (field.purpose()) {
                case BOSS -> raw.put(field.id(), boss == null ? "" : boss);
                case ACHIEVEMENT -> raw.put(field.id(), achievement == null ? "" : achievement);
                case TIER -> {
                    String optionId = tier == null ? null : field.options().stream().filter(o -> o.label().equalsIgnoreCase(tier)).map(o -> String.valueOf(o.id())).findFirst().orElse(null);
                    if (optionId != null) raw.put(field.id(), optionId);
                }
                default -> { }
            }
        }
        return raw;
    }

    /** The panel's questions the small form still asks: everything except the boss, tier and achievement, which the flow has already collected. */
    static List<Field> formFields(List<Field> fields) {
        return fields.stream().filter(f -> f.purpose() != FieldPurpose.BOSS && f.purpose() != FieldPurpose.TIER && f.purpose() != FieldPurpose.ACHIEVEMENT).toList();
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    // ================= the steps =================

    static Container bossStep(Panel panel, List<String> bosses) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + panel.title() + "\n**Step 1: which boss?**\nChoose the boss or activity you want help with."
                // Only PvM Help is advice without going in game; on CA Help the helpers do go in and help with the achievement.
                + (panel.helpKind() == com.younglings.bot.ticket.TicketModels.HelpKind.PVM
                        ? "\n-# Helpers give advice, guides and tips, or review a recording. They don't join you in game." : "")));

        int number = 0;
        for (List<String> chunk : balancedChunks(bosses, MAX_CHOICES)) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(BOSS_PREFIX + panel.id() + ":" + number++)
                    .setPlaceholder(shorten(chunk.getFirst() + " to " + chunk.getLast(), 100))
                    .setRequiredRange(1, 1);
            for (String boss : chunk) menu.addOption(shorten(boss, 100), String.valueOf(bosses.indexOf(boss)));
            children.add(ActionRow.of(menu.build()));
        }
        return Containers.card(Containers.PRIMARY, children);
    }

    static Container tierStep(Panel panel, String boss, int bossIndex, boolean tierRequired) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + panel.title() + "\n**Boss:** " + boss + "\n**Step 2: which tier?**\n"
                + (tierRequired ? "Choose the tier you're working on."
                        : "Choose a tier, or \"No tier\" if your question isn't about one. Pick a tier if you want to name a specific achievement.")));

        StringSelectMenu.Builder menu = StringSelectMenu.create(TIER_PREFIX + panel.id() + ":" + bossIndex)
                .setPlaceholder(tierRequired ? "Choose a tier" : "Choose a tier (optional)")
                .setRequiredRange(1, 1);
        if (!tierRequired) menu.addOption("No tier", "0");
        for (int i = 0; i < HelpRules.TIERS.size(); i++) menu.addOption(HelpRules.TIERS.get(i), String.valueOf(i + 1));
        children.add(ActionRow.of(menu.build()));
        return Containers.card(Containers.PRIMARY, children);
    }

    /** The achievements of one boss in the tier that was chosen, so the lists stay short and everything in them is the right difficulty. */
    static Container achievementStep(Panel panel, String boss, String tier, int bossIndex, int tierNumber, List<Achievement> achievements) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + panel.title() + "\n**Boss:** " + boss + "  ·  **Tier:** " + tier
                + "\n**Step 3: a specific achievement?** (optional)\nIf you choose one, helpers will only help with that one."));

        int number = 0;
        for (List<Achievement> chunk : balancedChunks(achievements, MAX_CHOICES)) {
            StringSelectMenu.Builder menu = StringSelectMenu.create(ACHIEVEMENT_PREFIX + panel.id() + ":" + bossIndex + ":" + tierNumber + ":" + number++)
                    .setPlaceholder(shorten(chunk.getFirst().name() + " to " + chunk.getLast().name(), 100))
                    .setRequiredRange(1, 1);
            for (Achievement a : chunk) {
                menu.addOption(shorten(a.name(), 100), String.valueOf(a.id()), shorten(a.description(), DESCRIPTION_MAX));
            }
            children.add(ActionRow.of(menu.build()));
        }
        children.add(ActionRow.of(Button.secondary(SKIP_PREFIX + panel.id() + ":" + bossIndex + ":" + tierNumber, "No specific achievement")));
        return Containers.card(Containers.PRIMARY, children);
    }

    // ================= starting =================

    /** Opens the first step (the caller has already checked the member may open a ticket on this panel). */
    public void start(IReplyCallback event, Panel panel) {
        List<String> bosses = catalog.bosses();
        if (bosses.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING, "The boss list isn't loaded yet. An admin needs to check the combat achievement catalogue.");
            return;
        }
        event.replyComponents(bossStep(panel, bosses)).useComponentsV2(true).setEphemeral(true).queue();
    }

    // ================= the steps' answers =================

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(BOSS_PREFIX) && !id.startsWith(TIER_PREFIX) && !id.startsWith(ACHIEVEMENT_PREFIX)) return;
        try {
            Guild guild = event.getGuild();
            if (guild == null || event.getMember() == null) return;

            String[] parts = id.split(":");
            Panel panel = repository.getPanel(Long.parseLong(parts[1]));
            if (panel == null || panel.guildId() != guild.getIdLong()) {
                Containers.replyEphemeral(event, Containers.WARNING, "That ticket type no longer exists.");
                return;
            }
            List<Field> fields = repository.getFields(panel.id());
            String value = event.getValues().getFirst();

            if (id.startsWith(BOSS_PREFIX)) {
                List<String> bosses = catalog.bosses();
                int bossIndex = Integer.parseInt(value);
                if (bossIndex < 0 || bossIndex >= bosses.size()) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That boss isn't on the list any more. Press the button again.");
                    return;
                }
                boolean tierRequired = fields.stream().anyMatch(f -> f.purpose() == FieldPurpose.TIER && f.required());
                event.editComponents(tierStep(panel, bosses.get(bossIndex), bossIndex, tierRequired)).useComponentsV2(true).queue();
            } else if (id.startsWith(TIER_PREFIX)) {
                int bossIndex = Integer.parseInt(parts[2]);
                int tierNumber = Integer.parseInt(value);
                List<String> bosses = catalog.bosses();
                if (bossIndex < 0 || bossIndex >= bosses.size() || tierNumber < 0 || tierNumber > HelpRules.TIERS.size()) {
                    Containers.replyEphemeral(event, Containers.WARNING, "That choice isn't available any more. Press the button again.");
                    return;
                }
                String boss = bosses.get(bossIndex);
                String tier = tierNumber == 0 ? null : HelpRules.TIERS.get(tierNumber - 1);
                // No tier chosen means no list to pick from: an achievement belongs to a tier, so naming one starts with choosing the tier.
                List<Achievement> achievements = tierNumber == 0 ? List.of() : catalog.search(null, tierNumber, boss, 200);
                if (achievements.isEmpty()) askRest(event, panel, fields, bossIndex, tierNumber, 0);
                else event.editComponents(achievementStep(panel, boss, tier, bossIndex, tierNumber, achievements)).useComponentsV2(true).queue();
            } else {
                askRest(event, panel, fields, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Long.parseLong(value));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in the help ticket flow '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(SKIP_PREFIX)) return;
        try {
            Guild guild = event.getGuild();
            if (guild == null || event.getMember() == null) return;
            String[] parts = id.split(":");
            Panel panel = repository.getPanel(Long.parseLong(parts[1]));
            if (panel == null || panel.guildId() != guild.getIdLong()) {
                Containers.replyEphemeral(event, Containers.WARNING, "That ticket type no longer exists.");
                return;
            }
            askRest(event, panel, repository.getFields(panel.id()), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), 0);
        } catch (Exception e) {
            log.error("Unhandled exception in the help ticket flow '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** The last step: the panel's remaining questions as a small form, or straight to the ticket if it has none. */
    private <E extends IReplyCallback & IModalCallback> void askRest(E event, Panel panel, List<Field> fields, int bossIndex, int tierNumber, long achievementId) {
        List<Field> rest = formFields(fields);
        if (rest.isEmpty()) {
            // Nothing left to ask. A select or button can't submit a form, so open the ticket from here.
            submit(event, panel, fields, bossIndex, tierNumber, achievementId, Map.of());
            return;
        }
        event.replyModal(service.buildForm(FORM_PREFIX + panel.id() + ":" + bossIndex + ":" + tierNumber + ":" + achievementId, panel, rest)).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(FORM_PREFIX)) return;
        try {
            Guild guild = event.getGuild();
            if (guild == null || event.getMember() == null) return;
            String[] parts = id.split(":");
            Panel panel = repository.getPanel(Long.parseLong(parts[1]));
            if (panel == null || panel.guildId() != guild.getIdLong()) {
                Containers.replyEphemeral(event, Containers.WARNING, "That ticket type no longer exists.");
                return;
            }
            List<Field> fields = repository.getFields(panel.id());
            submit(event, panel, fields, Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Long.parseLong(parts[4]), TicketListener.readValues(event, formFields(fields)));
        } catch (Exception e) {
            log.error("Unhandled exception in the help ticket form '{}'", id, e);
            Containers.replyError(event);
        }
    }

    // ================= opening the ticket =================

    private void submit(IReplyCallback event, Panel panel, List<Field> fields, int bossIndex, int tierNumber, long achievementId, Map<Long, String> formValues) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) return;

        List<String> bosses = catalog.bosses();
        if (bossIndex < 0 || bossIndex >= bosses.size()) {
            Containers.replyEphemeral(event, Containers.WARNING, "That boss isn't on the list any more. Press the button again.");
            return;
        }
        String tier = tierNumber <= 0 || tierNumber > HelpRules.TIERS.size() ? null : HelpRules.TIERS.get(tierNumber - 1);
        String achievement = achievementId == 0 ? null : catalog.findById(achievementId).map(Achievement::name).orElse(null);

        Optional<String> blocked = service.cannotOpen(guild, member.getIdLong(), panel, fields); // re-checked: a second one could have been opened meanwhile
        if (blocked.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, blocked.get());
            return;
        }
        Map<Long, String> raw = rawValues(fields, bosses.get(bossIndex), tier, achievement, formValues);
        Optional<String> needsAttempts = service.attemptsProblem(guild, member, panel, fields, raw);
        if (needsAttempts.isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, needsAttempts.get());
            return;
        }

        event.deferReply(true).queue();
        service.openTicket(guild, member, panel, fields, raw).whenComplete((ticket, error) -> {
            if (error != null || ticket == null || ticket.channelId() == null) {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                        "I couldn't open the ticket. An admin needs to check that I can create channels in its category."))).useComponentsV2(true).queue();
            } else {
                event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                        "✅ Your ticket is open: <#" + ticket.channelId() + ">"))).useComponentsV2(true).queue();
            }
        });
    }
}
