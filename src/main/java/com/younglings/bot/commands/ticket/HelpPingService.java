package com.younglings.bot.commands.ticket;

import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Works out which helpers a new help ticket pings: the ones who chose its tier in their ping settings and still hold a helper role. The
 * choices are kept in the database, but who still holds a role has to be asked of Discord, because the bot only keeps online members in
 * memory.
 */
@BService
public class HelpPingService {
    private static final Logger log = LoggerFactory.getLogger(HelpPingService.class);

    /** What a member is to the help system. */
    public enum HelperLevel { NONE, HELPER, HELPER_PLUS }

    private final TicketRepository repository;

    public HelpPingService(TicketRepository repository) {
        this.repository = repository;
    }

    public static HelperLevel levelOf(HelpSettings settings, Member member) {
        boolean plus = settings.helperPlusRoleId() != null && member.getRoles().stream().anyMatch(r -> r.getIdLong() == settings.helperPlusRoleId());
        if (plus) return HelperLevel.HELPER_PLUS;
        boolean helper = settings.helperRoleId() != null && member.getRoles().stream().anyMatch(r -> r.getIdLong() == settings.helperRoleId());
        return helper ? HelperLevel.HELPER : HelperLevel.NONE;
    }

    /** The helpers to ping for a ticket of this tier ({@code null} or blank means the general group); empty if nobody qualifies or Discord can't be asked. */
    public CompletableFuture<List<Long>> usersFor(Guild guild, HelpSettings settings, String tierLabel) {
        String group = HelpRules.pingGroup(tierLabel);
        List<Long> candidates = repository.getPingUsers(guild.getIdLong(), group);
        if (candidates.isEmpty()) return CompletableFuture.completedFuture(List.of());

        CompletableFuture<List<Long>> result = new CompletableFuture<>();
        guild.retrieveMembersByIds(candidates).onSuccess(members -> {
            List<Long> pinged = new ArrayList<>();
            for (Member member : members) {
                HelperLevel level = levelOf(settings, member);
                if (level == HelperLevel.NONE) continue; // they stopped being a helper since they chose their tiers
                if (HelpRules.allowedTiers(settings, level == HelperLevel.HELPER_PLUS).contains(group)) pinged.add(member.getIdLong());
            }
            result.complete(pinged);
        }).onError(error -> {
            log.warn("Couldn't look up the helpers to ping for the {} group", group, error);
            result.complete(List.of());
        });
        return result;
    }
}
