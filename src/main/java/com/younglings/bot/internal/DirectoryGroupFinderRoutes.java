package com.younglings.bot.internal;

import com.younglings.bot.clansite.ClanSite;
import com.younglings.bot.clansite.ClanSiteService;
import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.QuickGroupRepository.Group;
import com.younglings.bot.events.QuickGroupRepository.Member;
import com.younglings.bot.events.QuickGroupRepository.Settings;
import com.younglings.bot.events.QuickGroupService;
import com.younglings.bot.events.QuickGroups;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The group finder on a clan website (T-589): {@code sites/{slug}/groups} answers the open groups (GET, {@code ?userId=}) and posts one (POST);
 * {@code sites/{slug}/groups/join|leave|close} (POST, {@code groupId}, and {@code role} for a join) change one. Every answer is the whole list again,
 * as that person sees it, so the widget redraws from one answer.
 * <p>
 * Like the site's "me" routes ({@link DirectoryClanSiteMemberRoutes}): only for a published site whose server has the bot (a bot-less site answers
 * 409 {@code needsBot}), and only for someone in that Discord server; the {@code userId} must come from the visitor's verified login session on the
 * website, never from the browser. Closing is for the group's host and the site's staff ({@link SiteRoleGuard#viewer}), decided here. Names are the
 * members' names in the server; no Discord id is ever answered.
 */
@BService
public class DirectoryGroupFinderRoutes implements DirectoryRouteModule {
    private final ClanSiteService sites;
    private final QuickGroupService groups;
    private final SiteRoleGuard guard;
    private final RateLimiter writes = new RateLimiter(6, 2_000);
    private final RateLimiter reads = new RateLimiter(30, 500);

    public DirectoryGroupFinderRoutes(ClanSiteService sites, QuickGroupService groups, SiteRoleGuard guard) {
        this.sites = sites;
        this.groups = groups;
        this.guard = guard;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("sites") || parts.length < 3 || parts.length > 4 || !parts[2].equals("groups")) return false;
        String action = parts.length == 4 ? parts[3] : "";
        if (!action.isEmpty() && !action.equals("join") && !action.equals("leave") && !action.equals("close")) return false;

        Optional<ClanSite> site = request.viewable(parts[1]).flatMap(p -> sites.visible(p.guildId()));
        Guild guild = site.isEmpty() ? null : request.jda().getGuildById(site.get().guildId());
        if (guild == null) {
            if (site.isPresent() && sites.botless(site.get().guildId())) {
                request.send(409, DataObject.empty().put("error", BotlessAdminApi.NEEDS_BOT).put("needsBot", true).put("botless", true));
            } else {
                request.sendError(404, "Not found");
            }
            return true;
        }

        boolean read = action.isEmpty() && request.method().equals("GET");
        if (!read && !request.requirePost()) return true;
        DataObject body = read ? DataObject.empty() : request.body("Send the group as JSON.");
        if (body == null) return true;
        long userId;
        try {
            userId = Long.parseLong(read ? String.valueOf(request.query("userId")).strip() : body.getString("userId", "").strip());
        } catch (NumberFormatException e) {
            request.sendError(400, "userId must be numeric");
            return true;
        }
        if (!(read ? reads : writes).tryAcquire(guild.getId() + ":" + userId)) {
            request.sendError(429, "You're doing that too fast. Give it a moment.");
            return true;
        }
        try {
            if (ServerProfile.resolveMember(guild, userId) == null) {
                request.sendError(403, MemberApi.NOT_IN_SERVER);
                return true;
            }
            long guildId = guild.getIdLong();
            DataObject extra = DataObject.empty();
            switch (action) {
                case "" -> {
                    if (!read) extra.put("created", Long.toString(groups.create(guildId, userId, body, Instant.now())));
                }
                case "join" -> extra.put("joined", groups.join(guildId, userId, groupId(body), body.getString("role", "")).name());
                case "leave" -> groups.leave(guildId, userId, groupId(body));
                case "close" -> groups.close(guildId, userId, groupId(body), guard.viewer(guild, userId).staff());
                default -> {
                }
            }
            request.send(200, view(guild, userId).put("result", extra));
        } catch (EventProblem e) {
            request.sendError(e.status(), e.getMessage());
        } catch (DiscordLookups.Slow e) {
            InternalApiServer.sendSlow(request.exchange());
        }
        return true;
    }

    private static long groupId(DataObject body) {
        try {
            return Long.parseLong(body.getString("groupId", "").strip());
        } catch (NumberFormatException e) {
            throw new EventProblem(400, "Say which group.");
        }
    }

    /** The finder as {@code userId} sees it: whether it is on, whether groups are also posted in Discord, and the open groups soonest first. */
    DataObject view(Guild guild, long userId) {
        Settings settings = groups.settings(guild.getIdLong());
        boolean staff = guard.viewer(guild, userId).staff();
        DataArray list = DataArray.empty();
        if (settings.enabled()) {
            for (Group g : groups.open(guild.getIdLong())) list.add(groupJson(g, groups.members(g.id()), userId, staff, guild));
        }
        return DataObject.empty().put("enabled", settings.enabled()).put("postsToDiscord", settings.channelId() != null).put("groups", list)
                .put("limits", DataObject.empty().put("activity", QuickGroups.MAX_ACTIVITY).put("note", QuickGroups.MAX_NOTE).put("role", QuickGroups.MAX_ROLE)
                        .put("roles", QuickGroups.MAX_ROLES).put("perRole", QuickGroups.MAX_PER_ROLE).put("places", QuickGroups.MAX_PLACES)
                        .put("daysAhead", QuickGroups.MAX_AHEAD.toDays()).put("openPerHost", QuickGroups.MAX_OPEN_PER_HOST));
    }

    static DataObject groupJson(Group g, List<Member> members, long userId, boolean staff, Guild guild) {
        String mine = members.stream().filter(m -> m.userId() == userId).map(Member::role).findFirst().orElse(null);
        DataArray slots = DataArray.empty();
        for (QuickGroups.Slot slot : QuickGroups.slotsOf(g.config())) {
            DataArray who = DataArray.empty();
            for (Member m : members) {
                if (m.role() != null && m.role().equalsIgnoreCase(slot.role()))
                    who.add(DataObject.empty().put("name", nameOf(guild, m.userId())).put("me", m.userId() == userId));
            }
            slots.add(DataObject.empty().put("role", slot.role()).put("places", slot.places()).put("members", who));
        }
        return DataObject.empty().put("id", Long.toString(g.id())).put("activity", g.activity()).put("note", g.note() == null ? "" : g.note())
                .put("startsAt", g.startsAt() == null ? null : g.startsAt().toString())
                .put("host", DataObject.empty().put("name", nameOf(guild, g.hostId())).put("me", g.hostId() == userId))
                .put("slots", slots).put("myRole", mine).put("canClose", g.hostId() == userId || staff);
    }

    /** A member's name in the server from the bot's cache (a request never waits on Discord), or "A member". */
    private static String nameOf(Guild guild, long userId) {
        net.dv8tion.jda.api.entities.Member m = guild == null ? null : guild.getMemberById(userId);
        return m == null ? "A member" : m.getEffectiveName();
    }
}
