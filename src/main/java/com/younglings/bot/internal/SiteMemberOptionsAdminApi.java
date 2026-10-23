package com.younglings.bot.internal;

import com.younglings.bot.internal.CommunitySettings.SiteMemberOptions;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.function.Supplier;

/**
 * What a server's members may change about themselves from its clan website (T-369): their nickname and their name colour, both off until
 * an admin turns them on. {@code GET site-member-options} and {@code PUT site-member-options {nickname?, color?}}; the reply also says
 * whether the bot has the permission each needs. Reached only through {@link TicketAdminApi}, which has already checked the asker may use
 * the dashboard in this server and audit-logs every change. The dashboard/site admin page for it is a linked ticket (website-first).
 */
@BService
public class SiteMemberOptionsAdminApi implements AdminRouteExtension {
    private final CommunitySettings settings;

    public SiteMemberOptionsAdminApi(CommunitySettings settings) {
        this.settings = settings;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("site-member-options")) return null;
        if (method.equals("GET")) return json(guild, settings.siteMemberOptions(guild.getIdLong()));
        AdminRouteExtension.requireMethod(method, "PUT");
        DataObject b = body.get();
        SiteMemberOptions current = settings.siteMemberOptions(guild.getIdLong());
        SiteMemberOptions next = new SiteMemberOptions(b.hasKey("nickname") ? b.getBoolean("nickname", false) : current.nickname(),
                b.hasKey("color") ? b.getBoolean("color", false) : current.color());
        settings.setSiteMemberOptions(guild.getIdLong(), next);
        return json(guild, next);
    }

    private static DataObject json(Guild guild, SiteMemberOptions options) {
        Member self = guild.getSelfMember();
        return DataObject.empty().put("nickname", options.nickname()).put("color", options.color())
                .put("canSetNicknames", self.hasPermission(Permission.NICKNAME_MANAGE)).put("canManageRoles", self.hasPermission(Permission.MANAGE_ROLES));
    }
}
