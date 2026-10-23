package com.younglings.bot.checkup;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.welcome.WelcomeConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * A "server checkup": looks at everything a server has set up that depends on JonnyBot having the right Discord permissions (the
 * channels it posts in and the roles it hands out) and says plainly what is wrong, what it means, and which dashboard page fixes it.
 * The things people most often get wrong, such as the bot's role sitting below a role it has to give, show up here instead of as
 * silent failures weeks later.
 * <p>
 * Pure logic: Discord is reached through {@link GuildView} so this can be tested without a server, and everything it needs to know
 * about the server's settings is handed in as {@link Config}.
 */
public final class ServerCheckup {
    private ServerCheckup() {}

    public enum Level { OK, WARN, ERROR }

    /** One line of the report. {@code page} is the dashboard page (under /dashboard/&lt;server&gt;) that fixes it, or null. */
    public record Finding(Level level, String area, String title, String detail, String page) {}

    /** What JonnyBot can do in a channel; {@code null} from {@link GuildView#channel} means the channel is gone. */
    public record ChannelInfo(String name, boolean canView, boolean canSend, boolean canEmbed) {}

    /** A role as JonnyBot sees it; {@code null} from {@link GuildView#role} means the role is gone. */
    public record RoleInfo(String name, boolean managed, boolean botCanGive) {}

    public interface GuildView {
        ChannelInfo channel(long id);

        RoleInfo role(long id);

        boolean botCanManageRoles();
    }

    public record TrackingState(String name, boolean enabled, List<Long> channelIds) {}

    public record Config(GuildSettings settings, WelcomeConfig welcome, List<TrackingState> tracking, Long pollChannelId) {}

    public static List<Finding> run(GuildView view, Config config) {
        List<Finding> out = new ArrayList<>();
        GuildSettings s = config.settings();

        // ----- link requests and the roles verification hands out -----
        if (s.clanName() != null && s.clanEnabled() && s.verificationReviewChannelId() == null) {
            out.add(new Finding(Level.WARN, "Link requests", "Link requests have nowhere to go",
                    "A clan is set up, but no review channel is chosen, so staff never see members asking to link a RuneScape name.", "settings"));
        }
        channel(out, view, "Link requests", "Review channel", s.verificationReviewChannelId(), "settings", true);
        channel(out, view, "Link requests", "Rename alerts", s.renameAlertChannelId(), "settings", true);

        boolean anyRole = s.verifiedClanRoleId() != null || s.verifiedNonClanRoleId() != null || s.unverifiedRoleId() != null || s.onboardingRoleId() != null;
        if (anyRole && !view.botCanManageRoles()) {
            out.add(new Finding(Level.ERROR, "Roles", "The bot can't manage roles",
                    "Verification gives and takes roles, but the bot doesn't have the Manage Roles permission. In Discord, open Server Settings, then Roles, and give the bot's role Manage Roles.", null));
        }
        role(out, view, "Verified clan member", s.verifiedClanRoleId());
        role(out, view, "Verified, not in the clan", s.verifiedNonClanRoleId());
        role(out, view, "Not verified", s.unverifiedRoleId());
        role(out, view, "Link request pending", s.onboardingRoleId());

        // ----- welcome -----
        WelcomeConfig w = config.welcome();
        if (w != null && w.enabled()) {
            if (w.channelId() == null) {
                out.add(new Finding(Level.ERROR, "Welcome", "The welcome has no channel", "It is switched on, but no channel is chosen, so nobody is greeted.", "welcome"));
            } else {
                channel(out, view, "Welcome", "Welcome channel", w.channelId(), "welcome", w.messageType() != WelcomeConfig.MessageType.MESSAGE);
            }
        }

        // ----- announcements -----
        int working = 0;
        for (TrackingState group : config.tracking()) {
            if (!group.enabled()) continue;
            if (group.channelIds().isEmpty()) {
                out.add(new Finding(Level.WARN, "Announcements", group.name() + " has no channel", "It is switched on but has nowhere to post.", "tracking"));
                continue;
            }
            boolean fine = true;
            for (long id : group.channelIds()) {
                ChannelInfo c = view.channel(id);
                if (c == null) {
                    out.add(new Finding(Level.ERROR, "Announcements", group.name() + ": a channel was deleted", "One of its channels no longer exists. Remove it and choose another.", "tracking"));
                    fine = false;
                } else if (!c.canView() || !c.canSend()) {
                    out.add(new Finding(Level.ERROR, "Announcements", group.name() + ": can't post in #" + c.name(),
                            "The bot " + (c.canView() ? "can see this channel but isn't allowed to send messages in it." : "can't see this channel."), "tracking"));
                    fine = false;
                } else if (!c.canEmbed()) {
                    out.add(new Finding(Level.WARN, "Announcements", group.name() + ": can't embed links in #" + c.name(), "Its posts are cards, which won't show without the Embed Links permission.", "tracking"));
                    fine = false;
                }
            }
            if (fine) working++;
        }
        if (working > 0) {
            out.add(new Finding(Level.OK, "Announcements", working + " announcement feed" + (working == 1 ? " is" : "s are") + " set up and able to post", null, null));
        }

        // ----- polls -----
        channel(out, view, "Polls", "Member poll channel", config.pollChannelId(), "community", true);
        return out;
    }

    private static void channel(List<Finding> out, GuildView view, String area, String label, Long id, String page, boolean embeds) {
        if (id == null) return;
        ChannelInfo c = view.channel(id);
        if (c == null) {
            out.add(new Finding(Level.ERROR, area, label + " was deleted", "That channel no longer exists. Choose another.", page));
        } else if (!c.canView()) {
            out.add(new Finding(Level.ERROR, area, "The bot can't see #" + c.name(), label + ": give the bot the View Channel permission there.", page));
        } else if (!c.canSend()) {
            out.add(new Finding(Level.ERROR, area, "The bot can't send messages in #" + c.name(), label + ": give the bot the Send Messages permission there.", page));
        } else if (embeds && !c.canEmbed()) {
            out.add(new Finding(Level.WARN, area, "The bot can't embed links in #" + c.name(), label + ": its cards won't show without the Embed Links permission.", page));
        } else {
            out.add(new Finding(Level.OK, area, label + ": #" + c.name(), "The bot can post there.", null));
        }
    }

    private static void role(List<Finding> out, GuildView view, String label, Long id) {
        if (id == null) return;
        RoleInfo r = view.role(id);
        if (r == null) {
            out.add(new Finding(Level.ERROR, "Roles", label + " role was deleted", "That role no longer exists. Choose another.", "settings"));
        } else if (r.managed()) {
            out.add(new Finding(Level.ERROR, "Roles", "@" + r.name() + " can't be handed out", label + ": it belongs to an integration, which Discord doesn't let any bot give.", "settings"));
        } else if (!r.botCanGive()) {
            out.add(new Finding(Level.ERROR, "Roles", "@" + r.name() + " is above the bot's role",
                    label + ": in Server Settings, then Roles, drag the bot's role above @" + r.name() + " so it can give it out.", "settings"));
        } else {
            out.add(new Finding(Level.OK, "Roles", label + ": @" + r.name(), "The bot can give it out.", null));
        }
    }
}
