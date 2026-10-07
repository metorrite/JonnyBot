package com.younglings.bot.commands.ticket;

import com.younglings.bot.config.BotConfig;
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
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sets up the PvM Help system for trying out in a test server: finds (or creates) the PVM Helper and PVM Helper+ roles, saves them in the help
 * settings, creates the "PvM Help" and "CA Help" test panels, and posts both panels and the helper signup message in one channel. Running it
 * again brings the panels back in line with this code and updates the posted messages in place.
 * <p>
 * Dev only: it never runs on the live bot ({@link BotConfig#getLiveEnvironment()}). Run it with {@code /dev helpsetup}, or start the bot with
 * the {@code DEV_HELP_SEED_CHANNEL} environment variable set to a channel name and it runs once as the bot comes up.
 */
@BService
public class DevHelpSetup {
    private static final Logger log = LoggerFactory.getLogger(DevHelpSetup.class);

    static final String PVM_PANEL = "PvM Help (test)";
    static final String CA_PANEL = "CA Help (test)";
    static final String HELPER_ROLE = "PVM Helper";
    static final String HELPER_PLUS_ROLE = "PVM Helper+";
    static final String SEED_ENV = "DEV_HELP_SEED_CHANNEL";

    private final BotConfig botConfig;
    private final TicketRepository repository;
    private final TicketService ticketService;
    private final HelpOnboarding onboarding;

    public DevHelpSetup(BotConfig botConfig, TicketRepository repository, TicketService ticketService, HelpOnboarding onboarding) {
        this.botConfig = botConfig;
        this.repository = repository;
        this.ticketService = ticketService;
        this.onboarding = onboarding;
    }

    /** With {@code DEV_HELP_SEED_CHANNEL} set (and never on the live bot), sets everything up in the channel of that name as the bot starts. */
    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        String wanted = System.getenv(SEED_ENV);
        if (wanted == null || wanted.isBlank() || botConfig.getLiveEnvironment()) return;
        JDA jda = event.getJda();
        // The guild's channels aren't loaded the instant JDA reports ready, so give them a moment.
        CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() -> {
            String name = wanted.strip().toLowerCase(java.util.Locale.ROOT);
            for (Guild guild : jda.getGuilds()) {
                // An exact name first, then any channel whose name contains it (a channel called "🧪┃dev-testing", say).
                var channels = guild.getTextChannels();
                var channel = channels.stream().filter(c -> c.getName().equalsIgnoreCase(name)).findFirst()
                        .or(() -> channels.stream().filter(c -> c.getName().toLowerCase(java.util.Locale.ROOT).contains(name)).findFirst()).orElse(null);
                if (channel == null) {
                    log.warn("{} is set to \"{}\" but no text channel matches it in {}. Channels there: {}", SEED_ENV, wanted, guild.getName(),
                            channels.stream().map(c -> c.getName()).toList());
                    continue;
                }
                run(guild, channel).whenComplete((summary, error) -> {
                    if (error != null) log.error("Setting up PvM Help testing in #{} failed", channel.getName(), error);
                    else log.info("PvM Help testing set up in #{}: {}", channel.getName(), summary);
                });
                return;
            }
        });
    }

    /** Does the whole setup in {@code channel}; the result says what was done. */
    public CompletableFuture<String> run(Guild guild, GuildMessageChannel channel) {
        if (botConfig.getLiveEnvironment()) return CompletableFuture.failedFuture(new IllegalStateException("dev only"));

        return CompletableFuture.supplyAsync(() -> prepare(guild)).thenCompose(prepared -> {
            Panel pvm = repository.getPanel(prepared.pvmId());
            Panel ca = repository.getPanel(prepared.caId());
            return ticketService.postPanel(pvm, channel)
                    .thenCompose(done -> ticketService.postPanel(ca, channel))
                    .thenCompose(done -> onboarding.post(guild, channel))
                    .thenApply(done -> "roles " + prepared.helper().getName() + " and " + prepared.plus().getName() + ", panels \"" + pvm.name() + "\" (id " + pvm.id()
                            + ") and \"" + ca.name() + "\" (id " + ca.id() + "), and the helper signup message");
        });
    }

    private record Prepared(Role helper, Role plus, long pvmId, long caId) {}

    /** Blocking work (creating roles) kept off the event threads: roles, settings, and the two panel definitions. */
    private Prepared prepare(Guild guild) {
        try {
            Role helper = findOrCreateRole(guild, HELPER_ROLE);
            Role plus = findOrCreateRole(guild, HELPER_PLUS_ROLE);

            HelpSettings s = repository.getHelpSettings(guild.getIdLong());
            repository.saveHelpSettings(new HelpSettings(s.guildId(), helper.getIdLong(), plus.getIdLong(), s.guidelines(), s.memberPingOnOpen(),
                    s.memberEscalationHours(), s.guestPingsEnabled(), s.guestPingOnOpen(), s.guestEscalationHours(), s.guestHighTierNeedsAttempts(),
                    s.highTierLabels(), s.postedChannelId(), s.postedMessageId()));

            PanelRoles roles = new PanelRoles(Set.of(helper.getIdLong(), plus.getIdLong()), Set.of(), Set.of());
            long pvm = save(guild.getIdLong(), pvmPanel(guild.getIdLong()), roles);
            long ca = save(guild.getIdLong(), caPanel(guild.getIdLong()), roles);
            return new Prepared(helper, plus, pvm, ca);
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't prepare the PvM Help test setup: " + e.getMessage(), e);
        }
    }

    private Role findOrCreateRole(Guild guild, String name) throws Exception {
        List<Role> existing = guild.getRolesByName(name, true);
        if (!existing.isEmpty()) return existing.getFirst();
        return guild.createRole().setName(name).setMentionable(false).submit().get(15, TimeUnit.SECONDS);
    }

    /** Creates the panel, or replaces the earlier one of the same name so it matches this code (where it was posted is untouched). */
    private long save(long guildId, PanelDefinition definition, PanelRoles roles) {
        Panel wanted = definition.panel();
        Panel existing = repository.getPanels(guildId).stream().filter(p -> p.name().equalsIgnoreCase(wanted.name())).findFirst().orElse(null);
        Panel withId = existing == null ? wanted : new Panel(existing.id(), guildId, wanted.name(), wanted.title(), wanted.description(), wanted.buttonLabel(),
                wanted.categoryId(), wanted.channelNameTemplate(), wanted.welcomeText(), wanted.enabled(), wanted.perUserLimit(), wanted.defaultPingRoleId(),
                wanted.helperCap(), wanted.escalationHours(), wanted.defaultEscalateRoleId(), existing.postedChannelId(), existing.postedMessageId(),
                wanted.openingMessage(), wanted.closeByRequester(), wanted.closeByHelpers(), wanted.helpKind());
        return repository.saveDefinition(new PanelDefinition(withId, definition.fields(), roles));
    }

    private static List<Option> tiers() {
        List<Option> options = new ArrayList<>();
        for (int i = 0; i < HelpRules.TIERS.size(); i++) options.add(new Option(0, 0, i, HelpRules.TIERS.get(i), null, null));
        return options;
    }

    /** General PvM help: a boss is needed, a tier and a specific achievement are optional. */
    static PanelDefinition pvmPanel(long guildId) {
        Panel panel = new Panel(0, guildId, PVM_PANEL, "PvM Help",
                "Stuck on a boss, or want to get better at one? Open a ticket and a helper will give you advice, guides, tips and resources, or review a recording of your attempts.\n"
                        + "~<LS>~\n"
                        + "This is advice, not a carry: helpers don't go in game and run the content for you. Looking for a group? Try #teamforming first.",
                "Ask for PvM help", null, "pvm-{number}",
                "A helper will be with you soon. They'll give advice, guides and tips here, or review a recording of your attempt. They won't join you in game.",
                true, 1, null, 2, null, null, null, null, Panel.DEFAULT_OPENING, true, true, HelpKind.PVM);
        List<Field> fields = List.of(
                new Field(0, 0, 0, "Which boss?", FieldKind.SHORT, true, "e.g. Vorago", 100, List.of(), FieldPurpose.NONE),
                new Field(0, 0, 1, "Tier (optional)", FieldKind.SELECT, false, "Leave blank if it isn't about a tier", null, tiers(), FieldPurpose.TIER),
                new Field(0, 0, 2, "A specific achievement (optional)", FieldKind.SHORT, false, "e.g. Maul and Brawl", 100, List.of(), FieldPurpose.NONE),
                new Field(0, 0, 3, "What do you need help with?", FieldKind.PARAGRAPH, false, "Anything that helps a helper understand", 500, List.of(), FieldPurpose.NONE));
        return new PanelDefinition(panel, fields, PanelRoles.none());
    }

    /** CA help: a boss and a tier are needed; one achievement is optional, and a guest asking for Master or above says what they have tried. */
    static PanelDefinition caPanel(long guildId) {
        Panel panel = new Panel(0, guildId, CA_PANEL, "Combat Achievement Help",
                "Working on a Combat Achievement? Pick the boss and the tier. You can also name one specific achievement, and if you do we will only help with that one.\n"
                        + "~<LS>~\n"
                        + "Helpers give advice, guides and tips, or review a recording of your attempt. They don't go in game with you.",
                "Ask for CA help", null, "ca-{number}",
                "A helper will be with you soon. If you named one achievement they will only help with that one.",
                true, 1, null, 2, null, null, null, null, Panel.DEFAULT_OPENING, true, true, HelpKind.CA);
        List<Field> fields = List.of(
                new Field(0, 0, 0, "Which boss?", FieldKind.SHORT, true, "e.g. Amascut", 100, List.of(), FieldPurpose.NONE),
                new Field(0, 0, 1, "Tier", FieldKind.SELECT, true, "Pick the tier", null, tiers(), FieldPurpose.TIER),
                new Field(0, 0, 2, "One achievement (optional)", FieldKind.SHORT, false, "If you choose one, we only help with that one", 100, List.of(), FieldPurpose.NONE),
                new Field(0, 0, 3, "Earlier attempts", FieldKind.PARAGRAPH, false, "Needed for Master and Grandmaster if you aren't in the clan", 500, List.of(), FieldPurpose.ATTEMPTS),
                new Field(0, 0, 4, "Anything else?", FieldKind.PARAGRAPH, false, "When you're free, what you've tried, and so on", 500, List.of(), FieldPurpose.NONE));
        return new PanelDefinition(panel, fields, PanelRoles.none());
    }
}
