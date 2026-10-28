package com.runeclans.bot.hubsites;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiFunction;

/**
 * {@code /community join} and {@code /community leave} (T-732): join or leave the community of this server's website on the RuneClans hub,
 * through the hub's first-party {@code POST /v1/communities/{id}/members} and {@code DELETE /v1/communities/{id}/members/{discordId}}. The hub
 * applies the same rules as the website's Join button (open, by request, closed; a linked RuneScape character when the rules ask for one),
 * so a community that joins by request points the member at the website's join form. Works only while the hub owns the sites
 * ({@code SITES_BACKEND=hub}); replies are private to the caller, and the hub is called off the gateway thread.
 */
@Command
public class CommunityCommand {
    private static final Logger log = LoggerFactory.getLogger(CommunityCommand.class);

    private final HubSites sites;
    private final RuneClansHubClient hub;
    private final String sitesUrl;
    private final ScheduledExecutorService lane = BotScheduler.lane("community-command");

    public CommunityCommand(HubSites sites, RuneClansHubClient hub, BotConfig config) {
        this.sites = sites;
        this.hub = hub;
        this.sitesUrl = config.getClanSitesUrl();
    }

    @TopLevelSlashCommandData(description = "Join or leave this server's community on its RuneClans website")
    @JDASlashCommand(name = "community", subcommand = "join", description = "Join this server's community")
    public void onJoin(GuildSlashEvent event) {
        run(event, true);
    }

    @JDASlashCommand(name = "community", subcommand = "leave", description = "Leave this server's community")
    public void onLeave(GuildSlashEvent event) {
        run(event, false);
    }

    private void run(GuildSlashEvent event, boolean join) {
        long guildId = event.getGuild().getIdLong();
        String userId = event.getUser().getId();
        event.deferReply(true).queue(hook -> lane.execute(() -> {
            Reply reply = reply(sites, hub::call, sitesUrl, guildId, userId, join, (key, args) -> Text.of(event, key, args));
            hook.sendMessageComponents(List.of(Containers.toast(reply.accent(), reply.lines()))).useComponentsV2(true).setEphemeral(true).queue();
        }), err -> log.info("/community couldn't be acknowledged ({})", err.getClass().getSimpleName()));
    }

    /** What to tell the member: a colour and the lines. */
    record Reply(Color accent, String... lines) {}

    /** The hub's raw call ({@link RuneClansHubClient#call}): method, path, body. */
    interface Call {
        RuneClansHubClient.Answer call(String method, String path, String body);
    }

    /** Joins or leaves through the hub and words the answer. Pure apart from the two hub calls, so tests can drive it. */
    static Reply reply(HubSites sites, Call hub, String sitesUrl, long guildId, String userId, boolean join, BiFunction<String, Object[], String> text) {
        HubSites.Lookup lookup = sites.site(guildId);
        if (lookup.status() == HubSites.Status.UNKNOWN) return new Reply(Containers.WARNING, text.apply("community.try_again", new Object[0]));
        if (lookup.status() != HubSites.Status.FOUND || lookup.site().communityId() == null) {
            return new Reply(Containers.INFO, text.apply("community.no_community", new Object[0]));
        }
        HubSites.HubSite site = lookup.site();
        String base = "/v1/communities/" + site.communityId() + "/members";
        RuneClansHubClient.Answer a = join
                ? hub.call("POST", base, DataObject.empty().put("discordId", userId).toString())
                : hub.call("DELETE", base + "/" + userId, null);
        if (a == null || a.status() >= 500 || a.status() == 429) return new Reply(Containers.WARNING, text.apply("community.try_again", new Object[0]));
        String joinPage = joinPage(sitesUrl, site.slug());
        if (a.ok() && a.json() != null) {
            DataObject json = a.json();
            String name = json.getString("name", "");
            String state = json.optObject("me").map(me -> me.getString("state", "none")).orElse("none");
            if (join && state.equals("member")) return new Reply(Containers.SUCCESS, text.apply("community.joined", new Object[]{name}));
            if (join && state.equals("pending")) return new Reply(Containers.INFO, text.apply("community.request_waiting", new Object[]{name}));
            if (!join) return new Reply(Containers.SUCCESS, text.apply("community.left", new Object[]{name}));
        }
        String code = a.json() == null ? "" : a.json().getString("error", "");
        String message = a.json() == null ? "" : a.json().getString("message", "");
        if (a.status() == 404) return new Reply(Containers.INFO, text.apply("community.no_community", new Object[0]));
        if (message.isBlank()) return new Reply(Containers.WARNING, text.apply("community.try_again", new Object[0]));
        if (code.equals("use_site") && joinPage != null) return new Reply(Containers.INFO, message, text.apply("community.join_form", new Object[]{joinPage}));
        if (code.equals("link_needed") && sitesUrl != null) return new Reply(Containers.INFO, message, text.apply("community.link_page", new Object[]{sitesUrl + "/plugins/link"}));
        return new Reply(Containers.WARNING, message);
    }

    /** The site's {@code /join} page ({@code https://<slug>.<root>/join} from {@code CLAN_SITES_URL}), or null when either is unknown. */
    static String joinPage(String sitesUrl, String slug) {
        if (sitesUrl == null || slug == null || !slug.matches("[a-z0-9-]{1,63}")) return null;
        try {
            URI root = URI.create(sitesUrl);
            if (root.getHost() == null || root.getScheme() == null) return null;
            String port = root.getPort() < 0 ? "" : ":" + root.getPort();
            return root.getScheme() + "://" + slug + "." + root.getHost() + port + "/join";
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
