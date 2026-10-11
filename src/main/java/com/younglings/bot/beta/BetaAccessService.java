package com.younglings.bot.beta;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who may use JonnyBot's website while it is closed to the public: besides the clan's own admins, anyone who owns, or holds
 * Administrator or Manage Server in, one of the trusted servers on the beta list. The bot has to be in the server to look, so a
 * listed server the bot is not in lets nobody through. Answers are remembered for a minute, because the website asks on every page.
 */
@BService
public class BetaAccessService {
    private static final long REMEMBER_MS = 60_000;

    private record Answer(boolean allowed, long expiresAt) {}

    private final BetaGuildRepository repository;
    private final Map<Long, Answer> remembered = new ConcurrentHashMap<>();
    /** Replaceable so tests can move time without waiting. */
    LongSupplier clock = System::currentTimeMillis;

    public BetaAccessService(BetaGuildRepository repository) {
        this.repository = repository;
    }

    public boolean managesAnyBetaGuild(JDA jda, long userId) {
        Answer known = remembered.get(userId);
        long now = clock.getAsLong();
        if (known != null && known.expiresAt() > now) return known.allowed();

        boolean allowed = false;
        for (BetaGuildRepository.BetaGuild beta : repository.all()) {
            Guild guild = jda.getGuildById(beta.guildId());
            if (guild == null) continue;
            Member member = memberIn(guild, userId);
            if (member != null && (member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR) || member.hasPermission(Permission.MANAGE_SERVER))) {
                allowed = true;
                break;
            }
        }
        remembered.put(userId, new Answer(allowed, now + REMEMBER_MS));
        return allowed;
    }

    /** Forget what was remembered: the list just changed. */
    public void listChanged() {
        remembered.clear();
    }

    private static Member memberIn(Guild guild, long userId) {
        Member cached = guild.getMemberById(userId);
        if (cached != null) return cached;
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (Exception e) {
            return null; // not in that server (or Discord could not say)
        }
    }
}
