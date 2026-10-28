package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Who is asking about which server's clan website, for the site builder's admin routes. Where the bot is in the server, {@code guild} is
 * Discord's own; {@code member} is the person there (null when they aren't in it). For a bot-less site both are null.
 * <p>
 * {@code facts} null: a dashboard user in the server, whose site role comes from Discord exactly as before bot-less sites existed. Otherwise
 * the role comes from these facts: for a bot-less site what the website signed ({@link ActorFacts}, {@link SiteRoleGuard#factsWithoutBot}),
 * and for anyone let in through the site's own admin access (N5: an overall admin, someone named by user id, a held role, the staff-server
 * rule; {@link #site}) the bot's facts plus their signed staff servers ({@link ServerFacts}). {@code jda} is the bot's connection either way.
 */
record SiteCaller(long guildId, long userId, Guild guild, Member member, SiteRoles.Facts facts, JDA jda) {
    /** True when the bot isn't in the server: nothing Discord-side (roles, channels, members) can be read or checked. */
    boolean botless() {
        return guild == null;
    }

    static SiteCaller of(Guild guild, Member member) {
        return new SiteCaller(guild.getIdLong(), member.getIdLong(), guild, member, null, guild.getJDA());
    }

    /** Someone in a server the bot is in, judged on {@code facts} (they may not be a member: {@code member} null). */
    static SiteCaller site(Guild guild, long userId, Member member, SiteRoles.Facts facts) {
        return new SiteCaller(guild.getIdLong(), userId, guild, member, facts, guild.getJDA());
    }

    static SiteCaller botless(ActorFacts facts, SiteRoles.Facts siteFacts, JDA jda) {
        return new SiteCaller(facts.guildId(), facts.userId(), null, null, siteFacts, jda);
    }

    /** Whether this person may do {@code action} on the site: from {@code facts} when there are some, else Discord's view of the member. */
    boolean may(SiteRoleGuard roles, SiteRoles.Action action) {
        return facts != null ? roles.allows(guildId, facts, action) : roles.allows(guild, member, action);
    }

    /** This person's role on the site, the same way as {@link #may}. */
    SiteRoles.Role role(SiteRoleGuard roles) {
        return facts != null ? roles.roleOf(guildId, facts) : roles.roleOf(guild, member);
    }
}
