package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

/**
 * Who is asking about which server's clan website, for the site builder's admin routes. Where the bot is in the server, {@code guild} and
 * {@code member} are Discord's own and the site role comes from them, exactly as before bot-less sites existed. For a bot-less site both are
 * null and {@code facts} are what the website signed ({@link ActorFacts}, {@link SiteRoleGuard#factsWithoutBot}). {@code jda} is the bot's
 * connection either way (the bot's owners are looked up through it).
 */
record SiteCaller(long guildId, long userId, Guild guild, Member member, SiteRoles.Facts facts, JDA jda) {
    /** True when the bot isn't in the server: nothing Discord-side (roles, channels, members) can be read or checked. */
    boolean botless() {
        return guild == null;
    }

    static SiteCaller of(Guild guild, Member member) {
        return new SiteCaller(guild.getIdLong(), member.getIdLong(), guild, member, null, guild.getJDA());
    }

    static SiteCaller botless(ActorFacts facts, JDA jda) {
        return new SiteCaller(facts.guildId(), facts.userId(), null, null, SiteRoleGuard.factsWithoutBot(facts.userId(), facts.owner(), facts.admin()), jda);
    }

    /** Whether this person may do {@code action} on the site: Discord's view where the bot is in the server, else the signed facts. */
    boolean may(SiteRoleGuard roles, SiteRoles.Action action) {
        return botless() ? roles.allows(guildId, facts, action) : roles.allows(guild, member, action);
    }

    /** This person's role on the site, the same way as {@link #may}. */
    SiteRoles.Role role(SiteRoleGuard roles) {
        return botless() ? roles.roleOf(guildId, facts) : roles.roleOf(guild, member);
    }
}
