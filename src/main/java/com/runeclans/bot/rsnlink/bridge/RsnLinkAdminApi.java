package com.runeclans.bot.rsnlink.bridge;

import com.runeclans.bot.internal.AdminRouteExtension;
import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.rsnlink.LinkAdmin;
import com.runeclans.bot.rsnlink.LinkException;
import com.runeclans.bot.rsnlink.LinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.function.Supplier;

/**
 * The owner's RS Link abuse view (T-118) under {@code /internal/admin/rsnlink/...}, for the RuneClan Admin console (runeclans.com/admin). {@link
 * com.runeclans.bot.internal.TicketAdminApi} has already checked the secret, the asker's dashboard access and the write rate limit, and
 * audit-logs writes; this adds the owner check (the Discord application's owner or {@code OWNER_IDS}), so nobody else can read it, however
 * high their role in a server. The link service also writes its own audit row for every action.
 * <ul>
 *   <li>{@code GET rsnlink/overview}, {@code GET rsnlink/events}, {@code GET rsnlink/offenders}, {@code GET rsnlink/blocks}, {@code GET rsnlink/disputes},
 *       {@code GET rsnlink/audit}</li>
 *   <li>{@code POST rsnlink/blocks} {subjectKind, value, reason, hours}; {@code DELETE rsnlink/blocks/{id}}</li>
 *   <li>{@code POST rsnlink/disputes/{id}/resolve} {winner, note}</li>
 *   <li>{@code POST rsnlink/links/search} {rsn}; {@code POST rsnlink/links/{id}/remove} {reason}; {@code POST rsnlink/links/{id}/reveal}</li>
 * </ul>
 * RSNs and subjects travel in bodies, never in the URL.
 */
@BService
public class RsnLinkAdminApi implements AdminRouteExtension {
    private final RsnLinkModule module;
    private final BotOwners owners;

    public RsnLinkAdminApi(RsnLinkModule module, BotOwners owners) {
        this.module = module;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        return routeFor(guild.getJDA(), actor.getIdLong(), method, parts, body);
    }

    /** The same routes for the overall admin panel, whose admins need not be members of any server: the asker is a user id. The owner check is the same. */
    public DataObject routeFor(JDA jda, long actorId, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("rsnlink")) return null;
        if (!owners.isOwner(jda, actorId)) throw AdminRouteExtension.refuse(403, "Only the bot's owner can open the RS Link abuse view.");
        LinkService service = module.service();
        if (service == null) throw AdminRouteExtension.refuse(503, "The RS Link service is off (LINK_DATABASE_URL is not set).");
        LinkAdmin admin = new LinkAdmin(service);
        String who = Long.toString(actorId);
        try {
            if (parts.length == 2) {
                switch (parts[1]) {
                    case "overview" -> {
                        AdminRouteExtension.requireMethod(method, "GET");
                        return admin.overview();
                    }
                    case "events" -> {
                        AdminRouteExtension.requireMethod(method, "GET");
                        return admin.events(null, 100);
                    }
                    case "offenders" -> {
                        AdminRouteExtension.requireMethod(method, "GET");
                        return admin.offenders();
                    }
                    case "disputes" -> {
                        AdminRouteExtension.requireMethod(method, "GET");
                        return admin.disputes(null);
                    }
                    case "audit" -> {
                        AdminRouteExtension.requireMethod(method, "GET");
                        return admin.audit(100);
                    }
                    case "blocks" -> {
                        if (method.equals("GET")) return admin.blocks();
                        AdminRouteExtension.requireMethod(method, "POST");
                        DataObject b = body.get();
                        return admin.block(b.getString("subjectKind", null), b.getString("value", null), b.getString("reason", null), b.getInt("hours", 24), who);
                    }
                    default -> {
                        return null;
                    }
                }
            }
            if (parts.length == 3 && parts[1].equals("blocks")) {
                AdminRouteExtension.requireMethod(method, "DELETE");
                return admin.unblock(parts[2], who);
            }
            if (parts.length == 3 && parts[1].equals("links") && parts[2].equals("search")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return admin.findLinks(body.get().getString("rsn", null));
            }
            if (parts.length == 4 && parts[1].equals("disputes") && parts[3].equals("resolve")) {
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject b = body.get();
                return admin.resolveDispute(parts[2], b.getString("winner", null), b.getString("note", null), who);
            }
            if (parts.length == 4 && parts[1].equals("links") && parts[3].equals("remove")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return admin.removeLink(parts[2], body.get().getString("reason", null), who);
            }
            if (parts.length == 4 && parts[1].equals("links") && parts[3].equals("reveal")) {
                AdminRouteExtension.requireMethod(method, "POST");
                return admin.revealDiscord(parts[2], who);
            }
            return null;
        } catch (LinkException e) {
            throw AdminRouteExtension.refuse(e.status(), e.getMessage());
        }
    }
}
