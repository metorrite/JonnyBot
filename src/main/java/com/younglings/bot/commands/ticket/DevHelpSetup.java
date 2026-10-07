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
    private final HelpPanels helpPanels;

    public DevHelpSetup(BotConfig botConfig, TicketRepository repository, TicketService ticketService, HelpOnboarding onboarding, HelpPanels helpPanels) {
        this.botConfig = botConfig;
        this.repository = repository;
        this.ticketService = ticketService;
        this.onboarding = onboarding;
        this.helpPanels = helpPanels;
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
            long pvm = helpPanels.replace(guild.getIdLong(), pvmPanel(guild.getIdLong()), roles);
            long ca = helpPanels.replace(guild.getIdLong(), caPanel(guild.getIdLong()), roles);
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

    static PanelDefinition pvmPanel(long guildId) {
        return HelpPanels.pvm(guildId, PVM_PANEL);
    }

    static PanelDefinition caPanel(long guildId) {
        return HelpPanels.ca(guildId, CA_PANEL);
    }
}
