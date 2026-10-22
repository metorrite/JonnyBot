package com.younglings.bot.internal;

import com.younglings.bot.afk.AfkNicknames;
import com.younglings.bot.afk.AfkRepository;
import com.younglings.bot.afk.AfkService;
import com.younglings.bot.fun.FunCommandSync;
import com.younglings.bot.hub.CommandTag;
import com.younglings.bot.hub.HubCommand;
import com.younglings.bot.hub.HubService;
import com.younglings.bot.hub.HubSettings;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.PermissionGroup;
import com.younglings.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
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
    private final FunCommandSync funSync;
    private final AfkService afk;

    public HubAdminApi(HubService hub, PermissionGroupService groups, DashboardAccess access, AfkService afk, FunCommandSync funSync) {
        this.afk = afk;
        this.hub = hub;
        this.groups = groups;
        this.access = access;
        this.funSync = funSync;
    }

    DataObject get(Guild guild) {
        DataArray commands = DataArray.empty();
        for (HubCommand command : HubCommand.values()) commands.add(commandJson(guild, command));

        // the permission choices a "who can use it" list can name: every group of the server (its roles are picked separately)
        DataArray choices = DataArray.empty();
        for (PermissionGroup group : groups.groups(guild.getIdLong())) {
            choices.add(DataObject.empty().put("ref", "group:" + group.key()).put("name", group.name()).put("builtin", group.builtin()));
        }
        // every tag a command can carry, in the order the filter chips show them
        DataArray tagChoices = DataArray.empty();
        for (CommandTag tag : CommandTag.values()) tagChoices.add(DataObject.empty().put("key", tag.key()).put("label", tag.label()));
        return DataObject.empty().put("commands", commands).put("groups", choices).put("tags", tagChoices).put("maxList", MAX_LIST);
    }

    DataObject save(Guild guild, Member actor, String key, DataObject body) {
        HubCommand command = HubCommand.ofKey(key).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.there_is_no_such_command")));
        HubSettings current = hub.get(guild.getIdLong(), command);
        List<String> problems = new ArrayList<>();

        boolean enabled = body.getBoolean("enabled", current.enabled());
        boolean customAccess = body.getBoolean("customAccess", current.customAccess());
        List<String> refs = body.hasKey("allowedRefs") ? refs(guild, body.getArray("allowedRefs"), problems) : current.allowedRefs();
        List<Long> channels = body.hasKey("channelIds") ? channelIds(guild, "Where it can be used", body.getArray("channelIds"), problems) : current.channelIds();

        boolean accessChanged = customAccess != current.customAccess() || !Set.copyOf(refs).equals(Set.copyOf(current.allowedRefs()));
        if (accessChanged && access.tierOf(guild, actor) != DashboardAccess.Tier.ADMIN) {
            throw new ApiError(403, Text.of(guild, "dashboard.only_an_admin_can_change_who"));
        }

        String extras = current.extras();
        if (command == HubCommand.SIGNUP && body.hasKey("signup")) extras = signupExtras(guild, body.getObject("signup"), problems);
        List<Long> clearAfk = List.of();
        if (command == HubCommand.AFK && body.hasKey("afk") && !body.isNull("afk")) {
            DataObject a = body.getObject("afk");
            List<Long> ignored = channelIds(guild, "Channels where away status is ignored", a.hasKey("ignoreChannelIds") && !a.isNull("ignoreChannelIds") ? a.getArray("ignoreChannelIds") : DataArray.empty(), problems);
            extras = HubService.writeAfk(new HubService.AfkPolicy(a.getBoolean("nicknameTag", false), ignored));
            clearAfk = userIds(a, "clearUserIds", problems);
        }

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved"), problems);

        hub.save(new HubSettings(guild.getIdLong(), command.key(), enabled, customAccess, refs, channels, extras));
        // a fun command is only registered with Discord in a server that has it on, so switching it changes that server's command list
        if (command.registeredPerServer() && enabled != current.enabled()) funSync.sync(guild);
        for (long userId : clearAfk) {
            if (!afk.clear(guild.getIdLong(), userId)) continue;
            Member away = guild.getMemberById(userId);
            if (away != null) AfkNicknames.markBack(guild, away);
            log.info("Dashboard: {} took {} off the away list", actor.getId(), userId);
        }
        log.info("Dashboard: {} saved the Hub settings for /{}", actor.getId(), command.slashName());
        return commandJson(guild, command);
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

    private static List<Long> userIds(DataObject body, String key, List<String> problems) {
        List<Long> out = new ArrayList<>();
        if (!body.hasKey(key) || body.isNull(key)) return out;
        DataArray input = body.getArray(key);
        for (int i = 0; i < input.length() && i < MAX_LIST; i++) {
            String raw = input.getString(i, "").strip();
            if (raw.matches("\\d{1,20}")) out.add(Long.parseLong(raw));
            else problems.add("Away list: something there isn't a member.");
        }
        return out;
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
                else if (!(channel instanceof TextChannel)) problems.add("Admin channel: signups can only use a text channel, not a forum or thread.");
                else if (!channel.canTalk()) problems.add("Admin channel: JonnyBot can't post in #" + channel.getName() + ".");
                else adminChannel = channel.getIdLong();
            }
        }
        boolean lock = b.getBoolean("lockAdminChannel", false);
        if (lock && adminChannel == null && problems.isEmpty()) problems.add("Choose the admin channel before fixing it.");
        List<Long> publics = b.isNull("publicChannelIds") ? List.of() : channelIds(guild, "Public panel channels", b.getArray("publicChannelIds"), problems);
        for (long id : publics) {
            if (guild.getTextChannelById(id) == null) problems.add("Public panel channels: signup panels can only go in text channels, not forums or threads.");
        }
        return HubService.writeSignup(new HubService.SignupPolicy(adminChannel, lock, publics));
    }

    // ---------- writing the answer ----------

    private DataObject commandJson(Guild guild, HubCommand command) {
        long guildId = guild.getIdLong();
        HubSettings s = hub.get(guildId, command);
        DataArray refs = DataArray.empty();
        s.allowedRefs().forEach(refs::add);
        DataArray channels = DataArray.empty();
        s.channelIds().forEach(id -> channels.add(Long.toString(id)));
        DataObject json = DataObject.empty()
                .put("key", command.key()).put("title", command.title()).put("slashName", command.slashName())
                .put("description", command.description()).put("defaultAccess", command.defaultAccess())
                .put("tags", DataArray.fromCollection(command.tags().stream().map(CommandTag::key).toList()))
                .put("enabled", s.enabled()).put("customAccess", s.customAccess()).put("allowedRefs", refs).put("channelIds", channels);
        if (command == HubCommand.SIGNUP) {
            HubService.SignupPolicy p = hub.signupPolicy(guildId);
            DataArray publics = DataArray.empty();
            p.publicChannelIds().forEach(id -> publics.add(Long.toString(id)));
            json.put("signup", DataObject.empty().put("adminChannelId", p.adminChannelId() == null ? null : Long.toString(p.adminChannelId()))
                    .put("lockAdminChannel", p.lockAdminChannel()).put("publicChannelIds", publics));
        }
        if (command == HubCommand.AFK) {
            HubService.AfkPolicy p = hub.afkPolicy(guildId);
            DataArray ignored = DataArray.empty();
            p.ignoreChannelIds().forEach(id -> ignored.add(Long.toString(id)));
            DataArray away = DataArray.empty();
            for (AfkRepository.Afk entry : afk.all(guildId)) {
                Member who = guild.getMemberById(entry.userId());
                away.add(DataObject.empty().put("userId", Long.toString(entry.userId())).put("name", who == null ? "Someone who left" : who.getEffectiveName()).put("reason", entry.reason()).put("since", entry.since().toString()));
            }
            json.put("afk", DataObject.empty().put("nicknameTag", p.nicknameTag()).put("ignoreChannelIds", ignored).put("away", away));
        }
        return json;
    }
}
