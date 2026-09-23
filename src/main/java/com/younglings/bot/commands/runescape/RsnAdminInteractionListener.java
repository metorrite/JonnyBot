package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import com.younglings.bot.runescape.RuneScapeStatsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Buttons for {@link RsnAdminCommand}'s panel: pagination, manual poll (single player or
 * everyone), and links into {@code RsnInteractionListener}'s existing per-RSN views (Skills,
 * History, Recent Activity are shared as-is — same {@code rsn_skills:}/{@code rsn_history:}/
 * {@code rsn_activity:} button IDs work from either place, no duplicated rendering logic).
 * <p>
 * "Poll Now" is deliberately the same action whether you think of it as "get fresh data" or
 * "re-sync a stale/old-format row" — both just mean fetch-and-save under whatever the current
 * schema is, so there's no separate re-sync mechanism to keep in step with this one.
 */
@BService
public class RsnAdminInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnAdminInteractionListener.class);
    private static final int PAGE_SIZE = 5;

    private final PlayerLinkService linkService;
    private final RuneScapeStatsService statsService;
    private final AdminRoleFilter adminRoleFilter;

    public RsnAdminInteractionListener(PlayerLinkService linkService, RuneScapeStatsService statsService,
                                        AdminRoleFilter adminRoleFilter) {
        this.linkService = linkService;
        this.statsService = statsService;
        this.adminRoleFilter = adminRoleFilter;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsnadmin_")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }
            handleButton(event, guild, id);
        } catch (Exception e) {
            log.error("Unhandled exception in rsnadmin button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void handleButton(ButtonInteractionEvent event, Guild guild, String id) {
        if (id.startsWith("rsnadmin_list_page:")) {
            int page = Integer.parseInt(id.split(":")[1]);
            event.editComponents(List.of(buildPanel(linkService, statsService, guild, page)))
                    .useComponentsV2(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_poll_all")) {
            event.deferReply(true).queue();
            List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());
            int succeeded = 0;
            for (PlayerLink link : links) {
                if (statsService.pollAndSnapshot(guild.getIdLong(), link.rsn()).isPresent()) succeeded++;
            }
            event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                    "Polled " + succeeded + "/" + links.size() + " linked player(s)."))).useComponentsV2(true).queue();
            return;
        }

        if (id.startsWith("rsnadmin_poll:")) {
            String rsn = id.split(":", 2)[1];
            event.deferReply(true).queue();
            var profile = statsService.pollAndSnapshot(guild.getIdLong(), rsn);
            event.getHook().editOriginalComponents(List.of(profile.isPresent()
                    ? Containers.toast(Containers.SUCCESS, "Polled **" + rsn + "** — Level " + profile.get().totalLevel()
                            + ", " + String.format("%,d", profile.get().totalXp()) + " xp.")
                    : Containers.toast(Containers.WARNING, "Couldn't poll **" + rsn + "** — profile may be private or the name may not exist.")
            )).useComponentsV2(true).queue();
        }
    }

    /** Shared by {@link RsnAdminCommand}'s initial reply and this listener's own pagination/re-renders. */
    static Container buildPanel(PlayerLinkService linkService, RuneScapeStatsService statsService, Guild guild, int pageIndex) {
        List<PlayerLink> links = linkService.getAllLinks(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("# RS3 Admin Panel"));
        children.add(TextDisplay.of("-# Automatic polling is disabled — everything here is manual."));
        children.add(ActionRow.of(Button.primary("rsnadmin_poll_all:_", "Poll All")));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        if (links.isEmpty()) {
            children.add(TextDisplay.of("*No linked players yet.*"));
            return Containers.card(Containers.PRIMARY, children);
        }

        var page = Pagination.paginate(links, pageIndex, PAGE_SIZE);
        children.add(TextDisplay.of("**Linked Players** (" + links.size() + ")"));

        for (PlayerLink link : page.items()) {
            var latest = statsService.getLatestSnapshot(guild.getIdLong(), link.rsn());
            String lastPolled = latest == null ? "never" : "<t:" + latest.snapshotAt().toEpochSecond() + ":R>";

            children.add(TextDisplay.of("**" + link.rsn() + "** — <@" + link.discordUserId() + ">\n" +
                    "-# Verified <t:" + link.verifiedAt().toEpochSecond() + ":R> via " + link.verificationMethod()
                    + " • Last polled: " + lastPolled));
            children.add(ActionRow.of(
                    Button.primary("rsnadmin_poll:" + link.rsn(), "Poll Now"),
                    Button.secondary("rsn_skills:" + link.rsn(), "Skills"),
                    Button.secondary("rsn_history:" + link.rsn(), "History"),
                    Button.secondary("rsn_activity:" + link.rsn(), "Activity")
            ));
        }

        if (!page.isSinglePage()) {
            children.add(Pagination.navRow(page, "rsnadmin_list_page:"));
        }

        return Containers.card(Containers.PRIMARY, children);
    }
}
