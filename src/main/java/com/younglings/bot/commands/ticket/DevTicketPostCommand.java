package com.younglings.bot.commands.ticket;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only stand-in until the website dashboard can post panels: posts a ticket panel in the channel it's used
 * in. With no {@code panel} given it uses the server's first panel, and if there are none it first creates a
 * sample "Combat Achievement Help" panel (no roles or category yet — those are set in the dashboard) so there's
 * something to try. {@code @Test} keeps it out of production's command list, with {@link BotConfig#getLiveEnvironment()}
 * checked again at runtime.
 */
@Command
public class DevTicketPostCommand {
    private final BotConfig botConfig;
    private final AdminRoleFilter adminRoleFilter;
    private final TicketRepository repository;
    private final TicketService service;

    public DevTicketPostCommand(BotConfig botConfig, AdminRoleFilter adminRoleFilter, TicketRepository repository, TicketService service) {
        this.botConfig = botConfig;
        this.adminRoleFilter = adminRoleFilter;
        this.repository = repository;
        this.service = service;
    }

    @JDASlashCommand(name = "dev", subcommand = "ticketpost", description = "Posts a ticket panel in this channel (creates a sample one if none exist)")
    public void onDevTicketPost(GuildSlashEvent event,
                                @SlashOption(description = "Panel id — leave blank for the first panel") @Nullable Long panel) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }
        if (event.getGuild() == null || event.getMember() == null || !adminRoleFilter.isAuthorized(event.getGuild(), event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
            return;
        }
        if (!(event.getGuildChannel() instanceof GuildMessageChannel channel) || !channel.canTalk()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I can't post in this channel.");
            return;
        }

        long guildId = event.getGuild().getIdLong();
        Panel target;
        if (panel != null) {
            target = repository.getPanel(panel);
        } else {
            List<Panel> existing = repository.getPanels(guildId);
            target = existing.isEmpty() ? repository.getPanel(repository.saveDefinition(sample(guildId))) : existing.getFirst();
        }
        if (target == null || target.guildId() != guildId) {
            Containers.replyEphemeral(event, Containers.WARNING, "No panel with that id.");
            return;
        }

        event.deferReply(true).queue();
        service.postPanel(target, channel).whenComplete((ignored, error) -> event.getHook().editOriginalComponents(List.of(Containers.toast(
                error == null ? Containers.SUCCESS : Containers.DANGER,
                error == null ? "Posted panel **" + target.name() + "** (id " + target.id() + ")." : "Couldn't post it — check the bot can send messages here."))).useComponentsV2(true).queue());
    }

    /** A starting point shaped like the Combat Achievement help tickets from ticket-0003, with no roles attached yet. */
    public static PanelDefinition sample(long guildId) {
        Panel panel = new Panel(0, guildId, "Combat Achievement Help", "Combat Achievement Help",
                "Need a hand with a Combat Achievement? Tell us which one and a helper will join you.\n~<LS>~\nTeamforming first if you can — open a ticket when that hasn't worked.",
                "Request CA help", null, "ca-{number}", "A helper will join you here. Let them know when you're free.", true, 1, null, 2, 24, null, null, null);

        List<Option> tiers = new ArrayList<>();
        String[] names = {"Easy", "Medium", "Hard", "Elite", "Master", "Grandmaster"};
        for (int i = 0; i < names.length; i++) tiers.add(new Option(0, 0, i, names[i], null, null));

        List<Field> fields = List.of(
                new Field(0, 0, 0, "Which achievement(s)?", FieldKind.SHORT, true, "e.g. Amascut elites", 100, List.of()),
                new Field(0, 0, 1, "Tier", FieldKind.SELECT, true, "Pick the tier", null, tiers),
                new Field(0, 0, 2, "Solo or group?", FieldKind.SELECT, true, null, null,
                        List.of(new Option(0, 0, 0, "Solo", null, null), new Option(0, 0, 1, "Group", null, null))),
                new Field(0, 0, 3, "Notes and when you're free", FieldKind.PARAGRAPH, false, "Anything that helps", 500, List.of()),
                new Field(0, 0, 4, "I tried #teamforming first", FieldKind.CHECKBOX, false, null, null, List.of()));
        return new PanelDefinition(panel, fields, PanelRoles.none());
    }
}
