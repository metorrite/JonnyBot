package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.SelfRole;
import com.younglings.bot.runescape.ClanPointsRepository;
import com.younglings.bot.runescape.ClanPointsRepository.RankConfigRow;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The admin dashboard's non-ticket settings: which roles members may give themselves, and the clan's points
 * and rank thresholds. Reached only through {@link TicketAdminApi}, which has already verified the acting user
 * is an Admin or Developer, so nothing here re-checks who is asking.
 */
@BService
public class ClanAdminApi {
    private static final Logger log = LoggerFactory.getLogger(ClanAdminApi.class);
    private static final int MAX_SELF_ROLES = 25;
    private static final long MAX_POINTS = 1_000_000_000L;

    private final MemberProfileRepository members;
    private final ClanPointsRepository points;
    private final SiteNewsService news;

    public ClanAdminApi(MemberProfileRepository members, ClanPointsRepository points, SiteNewsService news) {
        this.members = members;
        this.points = points;
        this.news = news;
    }

    // ---------- the website's news channels ----------

    DataObject newsChannels(Guild guild) {
        DataArray array = DataArray.empty();
        for (var configured : news.channels(guild.getIdLong())) {
            var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, configured.channelId());
            array.add(DataObject.empty().put("channelId", Long.toString(configured.channelId())).put("name", channel == null ? null : channel.getName())
                    .put("label", configured.label()).put("readable", channel != null && guild.getSelfMember().hasPermission(channel,
                            net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_HISTORY)));
        }
        return DataObject.empty().put("channels", array);
    }

    DataObject saveNewsChannels(Guild guild, Member actor, DataObject body) {
        DataArray input = body.isNull("channels") ? DataArray.empty() : body.getArray("channels");
        if (input.length() > 6) throw new ApiError(400, "At most 6 news channels.");

        List<SiteNewsService.NewsChannel> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            long channelId;
            try {
                channelId = Long.parseLong(item.getString("channelId", ""));
            } catch (NumberFormatException e) {
                throw new ApiError(400, "A chosen channel isn't valid.");
            }
            if (!seen.add(channelId)) continue;
            var channel = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channelId);
            if (channel == null) throw new ApiError(400, "A chosen channel isn't a text channel in this server.");
            if (!guild.getSelfMember().hasPermission(channel, net.dv8tion.jda.api.Permission.VIEW_CHANNEL, net.dv8tion.jda.api.Permission.MESSAGE_HISTORY)) {
                throw new ApiError(400, "JonnyBot can't read #" + channel.getName() + " — give it View Channel and Read Message History there first.");
            }
            String label = item.getString("label", "").strip();
            if (label.length() > 40) throw new ApiError(400, "Labels are up to 40 characters.");
            chosen.add(new SiteNewsService.NewsChannel(channelId, label.isEmpty() ? null : label, i));
        }

        news.replaceChannels(guild.getIdLong(), chosen);
        log.info("Dashboard: {} set {} public news channel(s)", actor.getId(), chosen.size());
        return newsChannels(guild);
    }

    // ---------- self-assignable roles ----------

    DataObject selfRoles(Guild guild) {
        DataArray array = DataArray.empty();
        for (SelfRole configured : members.selfRoles(guild.getIdLong())) {
            Role role = guild.getRoleById(configured.roleId());
            array.add(DataObject.empty().put("roleId", Long.toString(configured.roleId())).put("name", role == null ? null : role.getName())
                    .put("label", configured.label()).put("description", configured.description())
                    .put("safe", role != null && MemberApi.safeToAssign(guild, role)));
        }
        return DataObject.empty().put("roles", array);
    }

    DataObject saveSelfRoles(Guild guild, Member actor, DataObject body) {
        DataArray input = body.isNull("roles") ? DataArray.empty() : body.getArray("roles");
        if (input.length() > MAX_SELF_ROLES) throw new ApiError(400, "At most " + MAX_SELF_ROLES + " self-assignable roles.");

        List<SelfRole> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < input.length(); i++) {
            DataObject item = input.getObject(i);
            long roleId;
            try {
                roleId = Long.parseLong(item.getString("roleId", ""));
            } catch (NumberFormatException e) {
                throw new ApiError(400, "A chosen role isn't valid.");
            }
            if (!seen.add(roleId)) continue;

            Role role = guild.getRoleById(roleId);
            if (role == null) throw new ApiError(400, "A chosen role no longer exists in this server.");
            if (!MemberApi.safeToAssign(guild, role)) {
                var dangerous = MemberApi.dangerousIn(role.getPermissions());
                throw new ApiError(400, "\"" + role.getName() + "\" can't be self-assignable: " + (dangerous.isEmpty()
                        ? "it is managed, the @everyone role, or above JonnyBot's own role."
                        : "it carries powerful permissions (" + dangerous.iterator().next().getName() + (dangerous.size() > 1 ? " and more" : "") + ")."));
            }
            String label = item.getString("label", "").strip();
            String description = item.getString("description", "").strip();
            if (label.length() > 40 || description.length() > 120) throw new ApiError(400, "Labels are up to 40 characters and descriptions up to 120.");
            chosen.add(new SelfRole(roleId, label.isEmpty() ? null : label, description.isEmpty() ? null : description, i));
        }

        members.replaceSelfRoles(guild.getIdLong(), chosen);
        log.info("Dashboard: {} set {} self-assignable role(s)", actor.getId(), chosen.size());
        return selfRoles(guild);
    }

    // ---------- clan points and rank thresholds ----------

    DataObject clanPoints(Guild guild) {
        var settings = points.getSettings(guild.getIdLong());
        DataArray ranks = DataArray.empty();
        for (RankConfigRow rank : points.getRanksOrdered(guild.getIdLong())) {
            ranks.add(DataObject.empty().put("id", Long.toString(rank.id())).put("name", rank.rankName()).put("order", rank.rankOrder()).put("threshold", rank.pointThreshold()));
        }
        return DataObject.empty().put("dailyMembershipPoints", settings.dailyMembershipPoints()).put("citadelVisitPoints", settings.citadelVisitPoints())
                .put("citadelCapPoints", settings.citadelCapPoints()).put("ranks", ranks);
    }

    DataObject saveClanPoints(Guild guild, Member actor, DataObject body) {
        long daily = pointsValue(body, "dailyMembershipPoints");
        long visit = pointsValue(body, "citadelVisitPoints");
        long cap = pointsValue(body, "citadelCapPoints");

        List<RankConfigRow> existing = points.getRanksOrdered(guild.getIdLong());
        List<long[]> updates = new ArrayList<>();
        if (!body.isNull("ranks")) {
            DataArray ranks = body.getArray("ranks");
            for (int i = 0; i < ranks.length(); i++) {
                DataObject item = ranks.getObject(i);
                long id;
                try {
                    id = Long.parseLong(item.getString("id", ""));
                } catch (NumberFormatException e) {
                    throw new ApiError(400, "A rank isn't valid.");
                }
                if (existing.stream().noneMatch(r -> r.id() == id)) throw new ApiError(400, "A rank doesn't belong to this server.");
                updates.add(new long[]{id, pointsValue(item, "threshold")});
            }
        }

        points.setSettings(guild.getIdLong(), daily, visit, cap);
        for (long[] update : updates) points.setRankThreshold(guild.getIdLong(), update[0], update[1]);
        log.info("Dashboard: {} updated clan points settings and {} rank threshold(s)", actor.getId(), updates.size());
        return clanPoints(guild);
    }

    private static long pointsValue(DataObject json, String key) {
        long value;
        try {
            value = json.isNull(key) ? 0 : Long.parseLong(String.valueOf(json.toMap().get(key)).replaceAll("\\.0+$", ""));
        } catch (NumberFormatException e) {
            throw new ApiError(400, "\"" + key + "\" must be a whole number.");
        }
        if (value < 0 || value > MAX_POINTS) throw new ApiError(400, "\"" + key + "\" must be between 0 and " + MAX_POINTS + ".");
        return value;
    }
}
