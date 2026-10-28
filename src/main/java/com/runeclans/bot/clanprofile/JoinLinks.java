package com.runeclans.bot.clanprofile;

import com.runeclans.bot.configure.GuildSettingsService;
import com.runeclans.bot.configure.InviteLink;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

/**
 * The one Discord invite a server shows everywhere on the hub (T-181): on its clan page's recruiting post and as the "join the Discord first"
 * link of its events. A server can type an invite in three places, so they are tried in one fixed order and the first real Discord invite wins:
 * <ol>
 *   <li>Server setup's invite (the server's own invite: the source of truth);</li>
 *   <li>the recruiting post's invite (for a server that only filled that in);</li>
 *   <li>the clan profile's "Where to apply" link, when it is a Discord invite.</li>
 * </ol>
 * A "Where to apply" link that is a web page is never used as the join link: it stays the clan page's own Apply link.
 */
@BService
public class JoinLinks {
    private final GuildSettingsService guildSettings;
    private final ClanRecruitingRepository recruiting;

    public JoinLinks(GuildSettingsService guildSettings, ClanRecruitingRepository recruiting) {
        this.guildSettings = guildSettings;
        this.recruiting = recruiting;
    }

    /** The server's join link, or null when none of the three is a Discord invite. {@code applyUrl} is its clan profile's, when it has one. */
    public String of(long guildId, String applyUrl) {
        return choose(guildSettings.getEffective(guildId).inviteUrl(), recruiting.of(guildId).inviteUrl(), applyUrl);
    }

    /** The first of the three that is a Discord invite, in its short form. Pure, so the order can be tested. */
    public static String choose(String serverInvite, String recruitingInvite, String applyUrl) {
        for (String candidate : new String[]{serverInvite, recruitingInvite, applyUrl}) {
            if (candidate == null || candidate.isBlank()) continue;
            var invite = InviteLink.normalize(candidate);
            if (invite.isPresent()) return invite.get();
        }
        return null;
    }
}
