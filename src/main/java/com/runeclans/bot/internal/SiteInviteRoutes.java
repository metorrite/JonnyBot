package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ClanRecruitingRepository;
import com.runeclans.bot.clanprofile.JoinLinks;
import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.configure.InviteLink;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.function.Supplier;

/**
 * The site admin's Discord invite (T-633), on the Connections page: {@code GET clan-site/connections/invite} and {@code PUT
 * clan-site/connections/invite} {@code {inviteUrl}} (empty or null clears it). It is the server setup's invite, the first of the three
 * {@link JoinLinks} tries, so what is typed here is what the site's "Join our Discord" buttons show. Stored by server id, so a bot-less site
 * works too. Admin, like the site's other settings ({@link ClanSiteAdminApi} checks the site role first).
 */
@BService
public class SiteInviteRoutes {
    private final GuildSettingsService settings;
    private final ClanRecruitingRepository recruiting;
    private final ClanProfileService profiles;

    public SiteInviteRoutes(GuildSettingsService settings, ClanRecruitingRepository recruiting, ClanProfileService profiles) {
        this.settings = settings;
        this.recruiting = recruiting;
        this.profiles = profiles;
    }

    DataObject route(long guildId, String method, Supplier<DataObject> body) {
        if (method.equals("GET")) return get(guildId);
        AdminRouteExtension.requireMethod(method, "PUT");
        DataObject b = body.get();
        String raw = b.isNull("inviteUrl") ? "" : b.getString("inviteUrl", "").strip();
        String invite = null;
        if (!raw.isEmpty()) {
            invite = InviteLink.normalize(raw).orElseThrow(() -> new ApiError(400, "The invite can't be saved.",
                    List.of("Use a Discord invite link, like https://discord.gg/abc123.")));
        }
        settings.updateInviteUrl(guildId, invite);
        return get(guildId);
    }

    /**
     * {@code inviteUrl}: the one typed for this server (null when none); {@code joinLink}: the invite the site actually shows, which can come
     * from the recruiting post or the profile's apply link when none is typed; {@code recruiting}: whether the clan profile says it is
     * recruiting (the clan page shows the invite only then).
     */
    DataObject get(long guildId) {
        ClanProfile profile = profiles.profileOf(guildId);
        String typed = settings.getEffective(guildId).inviteUrl();
        String join = JoinLinks.choose(typed, recruiting.of(guildId).inviteUrl(), profile.applyUrl());
        return DataObject.empty().put("inviteUrl", typed).put("joinLink", join).put("recruiting", profile.recruiting() != ClanProfile.Recruiting.CLOSED);
    }
}
