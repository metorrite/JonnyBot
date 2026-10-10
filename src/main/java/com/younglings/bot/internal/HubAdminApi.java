package com.younglings.bot.internal;

import com.younglings.bot.hub.HubCommand;
import com.younglings.bot.hub.HubService;
import com.younglings.bot.hub.HubSettings;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The dashboard's Hub: the slash commands that stand on their own, each with the same generic settings (on or off, who may use it,
 * where members may use it) and, for some, settings of their own. Reached only through {@link TicketAdminApi}, which has already
 * checked the acting user may use the dashboard here and which audit-logs every change.
 * <p>
 * Changing <em>who</em> may use a command is changing permissions, so it needs the Admin tier, like editing the permission groups;
 * everything else on a card is open to the Developer tier too. A save is checked as a whole before anything changes.
 */
@BService
public class HubAdminApi {
    private static final Logger log = LoggerFactory.getLogger(HubAdminApi.class);
    private static final int MAX_LIST = 25;

    private final HubService hub;
    private final PermissionGroupService groups;
    private final DashboardAccess access;

    public HubAdminApi(HubService hub, PermissionGroupService groups, DashboardAccess access) {
        this.hub = hub;
        this.groups = groups;
        this.access = access;
    }

    DataObject get(Guild guild) {
        DataArray commands = DataArray.empty();
        for (HubCommand command : HubCommand.values()) commands.add(commandJson(guild.getIdLong(), command));

        // the permission choices a "who can use it" list can name: every group of the server (its roles are picked separately)
        DataArray choices = DataArray.empty();
        for (PermissionGroup group : groups.groups(guild.getIdLong())) {
            choices.add(DataObject.empty().put("ref", "group:" + group.key()).put("name", group.name()).put("builtin", group.builtin()));
        }
        return DataObject.empty().put("commands", commands).put("groups", choices).put("maxList", MAX_LIST);
    }

    DataObject save(Guild guild, Member actor, String key, DataObject body) {
        HubCommand command = HubCommand.ofKey(key).orElseThrow(() -> new ApiError(404, "There is no such command."));
        HubSettings current = hub.get(guild.getIdLong(), command);
        List<String> problems = new ArrayList<>();

        boolean enabled = body.getBoolean("enabled", current.enabled());
        boolean customAccess = body.getBoolean("customAccess", current.customAccess());
        List<String> refs = body.hasKey("allowedRefs") ? refs(guild, body.getArray("allowedRefs"), problems) : current.allowedRefs();
        List<Long> channels = body.hasKey("channelIds") ? channelIds(guild, "Where it can be used", body.getArray("channelIds"), problems) : current.channelIds();

        boolean accessChanged = customAccess != current.customAccess() || !Set.copyOf(refs).equals(Set.copyOf(current.allowedRefs()));
        if (accessChanged && access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) {
            throw new ApiError(403, "Only an Admin can change who may use a command.");
        }

        String extras = current.extras();
        if (command == HubCommand.SIGNUP && body.hasKey("signup")) extras = signupExtras(guild, body.getObject("signup"), problems);

        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved yet.", problems);

        hub.save(new HubSettings(guild.getIdLong(), command.key(), enabled, customAccess, refs, channels, extras));
        log.info("Dashboard: {} saved the Hub settings for /{}", actor.getId(), command.slashName());
        return commandJson(guild.getIdLong(), command);
    }

    // ---------- reading the request ----------

    private List<String> refs(Guild guild, DataArray input, List<String> problems) {
        Set<String> out = new LinkedHashSet<>();
        Set<String> groupKeys = new LinkedHashSet<>();
        groups.groups(guild.getIdLong()).forEach(g -> groupKeys.add(g.key()));
        for (int i = 0; i < input.length(); i++) {
            String ref = input.getString(i, "").strip();
            if (ref.startsWith("group:")) {
                if (groupKeys.contains(ref.substring(6))) out.add(ref);
                else problems.add("A permission group in \"Who can use it\" no longer exists.");
            } else if (ref.startsWith("role:") && ref.substring(5).matches("\\d{1,20}")) {
                if (guild.getRoleById(Long.parseLong(ref.substring(5))) != null) out.add(ref);
                else problems.add("A role in \"Who can use it\" doesn't exist in this server any more.");
            } else {
                problems.add("Something in \"Who can use it\" isn't a group or a role.");
            }
        }
        if (out.size() > MAX_LIST) problems.add("\"Who can use it\" can list at most " + MAX_LIST + " groups and roles.");
        return List.copyOf(out);
    }

    private List<Long> channelIds(Guild guild, String label, DataArray input, List<String> problems) {
        Set<Long> out = new LinkedHashSet<>();
        for (int i = 0; i < input.length(); i++) {
            String raw = input.getString(i, "").strip();
            if (!raw.matches("\\d{1,20}")) {
                problems.add(label + ": something there isn't a channel.");
                continue;
            }
            long id = Long.parseLong(raw);
            if (guild.getChannelById(GuildMessageChannel.class, id) == null) problems.add(label + ": a channel doesn't exist in this server any more.");
            else out.add(id);
        }
        if (out.size() > MAX_LIST) problems.add(label + " can list at most " + MAX_LIST + " channels.");
        return List.copyOf(out);
    }

    private String signupExtras(Guild guild, DataObject b, List<String> problems) {
        Long adminChannel = null;
        if (!b.isNull("adminChannelId") && !b.getString("adminChannelId", "").isBlank()) {
            String raw = b.getString("adminChannelId", "").strip();
            if (!raw.matches("\\d{1,20}")) {
                problems.add("Admin channel: that isn't a channel.");
            } else {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw));
                if (channel == null) problems.add("Admin channel: that channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("Admin channel: JonnyBot can't post in #" + channel.getName() + ".");
                else adminChannel = channel.getIdLong();
            }
        }
        boolean lock = b.getBoolean("lockAdminChannel", false);
        if (lock && adminChannel == null && problems.isEmpty()) problems.add("Choose the admin channel before fixing it.");
        List<Long> publics = b.isNull("publicChannelIds") ? List.of() : channelIds(guild, "Public panel channels", b.getArray("publicChannelIds"), problems);
        return HubService.writeSignup(new HubService.SignupPolicy(adminChannel, lock, publics));
    }

    // ---------- writing the answer ----------

    private DataObject commandJson(long guildId, HubCommand command) {
        HubSettings s = hub.get(guildId, command);
        DataArray refs = DataArray.empty();
        s.allowedRefs().forEach(refs::add);
        DataArray channels = DataArray.empty();
        s.channelIds().forEach(id -> channels.add(Long.toString(id)));
        DataObject json = DataObject.empty()
                .put("key", command.key()).put("title", command.title()).put("slashName", command.slashName())
                .put("description", command.description()).put("defaultAccess", command.defaultAccess())
                .put("enabled", s.enabled()).put("customAccess", s.customAccess()).put("allowedRefs", refs).put("channelIds", channels);
        if (command == HubCommand.SIGNUP) {
            HubService.SignupPolicy p = hub.signupPolicy(guildId);
            DataArray publics = DataArray.empty();
            p.publicChannelIds().forEach(id -> publics.add(Long.toString(id)));
            json.put("signup", DataObject.empty().put("adminChannelId", p.adminChannelId() == null ? null : Long.toString(p.adminChannelId()))
                    .put("lockAdminChannel", p.lockAdminChannel()).put("publicChannelIds", publics));
        }
        return json;
    }
}
