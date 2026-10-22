package com.younglings.bot.internal;

import com.younglings.bot.checkup.ServerCheckup;
import com.younglings.bot.checkup.ServerCheckup.ChannelInfo;
import com.younglings.bot.checkup.ServerCheckup.Finding;
import com.younglings.bot.checkup.ServerCheckup.Level;
import com.younglings.bot.checkup.ServerCheckup.RoleInfo;
import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.tracking.TrackingGroup;
import com.younglings.bot.tracking.TrackingRepository;
import com.younglings.bot.tracking.TrackingService;
import com.younglings.bot.welcome.WelcomeRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The dashboard's Checkup page: runs {@link ServerCheckup} for one server and returns what it found. Read-only. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class CheckupAdminApi implements AdminRouteExtension {
    private final GuildSettingsService settings;
    private final WelcomeRepository welcome;
    private final TrackingService tracking;
    private final CommunitySettings community;

    public CheckupAdminApi(GuildSettingsService settings, WelcomeRepository welcome, TrackingService tracking, CommunitySettings community) {
        this.settings = settings;
        this.welcome = welcome;
        this.tracking = tracking;
        this.community = community;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("checkup")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        return run(guild);
    }

    DataObject run(Guild guild) {
        long guildId = guild.getIdLong();
        List<ServerCheckup.TrackingState> groups = new ArrayList<>();
        for (TrackingGroup group : TrackingGroup.values()) {
            List<Long> channels = new ArrayList<>();
            for (TrackingRepository.Destination d : tracking.getDestinations(guildId, group)) channels.add(d.channelId());
            groups.add(new ServerCheckup.TrackingState(group.displayName(), tracking.isEnabled(guildId, group), channels));
        }
        List<Finding> findings = ServerCheckup.run(new JdaView(guild),
                new ServerCheckup.Config(settings.getEffective(guildId), welcome.get(guildId), groups, community.pollChannel(guildId)));

        DataArray list = DataArray.empty();
        int errors = 0;
        int warnings = 0;
        int ok = 0;
        for (Finding f : findings) {
            switch (f.level()) {
                case ERROR -> errors++;
                case WARN -> warnings++;
                case OK -> ok++;
            }
            list.add(DataObject.empty().put("level", f.level().name()).put("area", f.area()).put("title", f.title())
                    .put("detail", f.detail()).put("page", f.page()));
        }
        return DataObject.empty().put("findings", list).put("errors", errors).put("warnings", warnings).put("ok", ok).put("intents", intents());
    }

    /** Which of Discord's privileged intents the bot is running with (the others were refused in the Developer Portal). */
    static DataObject intents() {
        return DataObject.empty().put("members", PrivilegedIntents.members()).put("presence", PrivilegedIntents.presence()).put("messageContent", PrivilegedIntents.messageContent());
    }

    /** Discord as the checkup sees it. */
    private record JdaView(Guild guild) implements ServerCheckup.GuildView {
        @Override
        public ChannelInfo channel(long id) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, id);
            if (channel == null) return null;
            Member self = guild.getSelfMember();
            return new ChannelInfo(channel.getName(), self.hasPermission(channel, Permission.VIEW_CHANNEL),
                    self.hasPermission(channel, Permission.MESSAGE_SEND), self.hasPermission(channel, Permission.MESSAGE_EMBED_LINKS));
        }

        @Override
        public RoleInfo role(long id) {
            Role role = guild.getRoleById(id);
            return role == null ? null : new RoleInfo(role.getName(), role.isManaged(), guild.getSelfMember().canInteract(role));
        }

        @Override
        public boolean botCanManageRoles() {
            return guild.getSelfMember().hasPermission(Permission.MANAGE_ROLES);
        }
    }
}
