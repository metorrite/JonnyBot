package com.younglings.bot.clanprofile;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * A clan's recruiting post on the Clans Hub: what it is looking for, the application form (a web form of this server) the hub's Apply
 * button opens, and the Discord invite an applicant is shown (web forms only take answers from people already in the server, so joining
 * comes first). Sits beside the clan profile, which keeps the recruiting status, requirements, timezone and focus.
 */
public record ClanRecruiting(long guildId, String post, Long applyFormId, String inviteUrl, Instant updatedAt) {
    public static final int MAX_POST = 1500;
    /** A Discord invite and nothing else: {@code https://discord.gg/<code>} or {@code https://discord.com/invite/<code>}. */
    public static final Pattern INVITE = Pattern.compile("https://(?:discord\\.gg|(?:www\\.)?discord\\.com/invite)/[A-Za-z0-9-]{2,32}/?");

    public static ClanRecruiting empty(long guildId) {
        return new ClanRecruiting(guildId, "", null, null, null);
    }

    public static boolean validInvite(String url) {
        return url != null && INVITE.matcher(url).matches();
    }
}
