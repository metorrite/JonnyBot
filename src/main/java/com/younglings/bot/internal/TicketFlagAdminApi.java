package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import com.younglings.bot.ticketflag.TicketFlagRepository;
import com.younglings.bot.ticketflag.TicketFlagRepository.Flag;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Staff flags on a ticket's player (a private "this help experience went badly" note), for the dashboard. Off until a server switches it on.
 * <ul>
 *     <li>{@code GET ticket-flags}: the setting and the newest flags in the server</li>
 *     <li>{@code PUT ticket-flags/settings}: {@code enabled}</li>
 *     <li>{@code GET ticket-flags/ticket/{ticketId}}: the setting and every flag on that ticket's player</li>
 *     <li>{@code POST ticket-flags/ticket/{ticketId}}: add a flag ({@code note}); refused while the feature is off</li>
 *     <li>{@code POST ticket-flags/{id}/delete}: remove a flag</li>
 * </ul>
 * Reached through {@link TicketAdminApi}, which has already checked the asker may use the dashboard here and audit-logs every write.
 */
@BService
public class TicketFlagAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(TicketFlagAdminApi.class);
    static final int MAX_NOTE = 500;
    static final int MAX_FLAGS_PER_GUILD = 2000;
    private static final int LIST_LIMIT = 200;

    private final TicketFlagRepository flags;
    private final TicketRepository tickets;

    public TicketFlagAdminApi(TicketFlagRepository flags, TicketRepository tickets) {
        this.flags = flags;
        this.tickets = tickets;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("ticket-flags")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        if (parts.length == 3 && parts[1].equals("ticket")) {
            if (method.equals("GET")) return forTicket(guild, id(parts[2]));
            AdminRouteExtension.requireMethod(method, "POST");
            return add(guild, actor, id(parts[2]), body.get());
        }
        if (parts.length == 3 && parts[2].equals("delete")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return delete(guild, actor, id(parts[1]));
        }
        return null;
    }

    DataObject overview(Guild guild) {
        DataArray list = DataArray.empty();
        for (Flag flag : flags.list(guild.getIdLong(), null, LIST_LIMIT)) list.add(toJson(guild, flag));
        return DataObject.empty().put("enabled", flags.enabled(guild.getIdLong())).put("flags", list).put("maxNote", MAX_NOTE);
    }

    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        flags.setEnabled(guild.getIdLong(), enabled);
        log.info("Dashboard: {} switched ticket flags {}", actor.getId(), enabled ? "on" : "off");
        return overview(guild);
    }

    DataObject forTicket(Guild guild, long ticketId) {
        Ticket ticket = ticket(guild, ticketId);
        DataArray list = DataArray.empty();
        for (Flag flag : flags.list(guild.getIdLong(), ticket.requesterId(), LIST_LIMIT)) list.add(toJson(guild, flag));
        return DataObject.empty().put("enabled", flags.enabled(guild.getIdLong())).put("flags", list).put("maxNote", MAX_NOTE);
    }

    DataObject add(Guild guild, Member actor, long ticketId, DataObject body) {
        if (!flags.enabled(guild.getIdLong())) throw new ApiError(400, Text.of(guild, "dashboard.ticket_flags_are_switched_off_for"));
        Ticket ticket = ticket(guild, ticketId);
        String note = body.getString("note", "").strip();
        if (note.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.write_a_short_note_on_what"));
        if (note.length() > MAX_NOTE) throw new ApiError(400, Text.of(guild, "dashboard.keep_the_note_under_characters", MAX_NOTE));
        if (flags.count(guild.getIdLong()) >= MAX_FLAGS_PER_GUILD) throw new ApiError(400, Text.of(guild, "dashboard.this_server_has_reached_the_limit", MAX_FLAGS_PER_GUILD));
        long id = flags.add(guild.getIdLong(), ticket.id(), ticket.number(), ticket.requesterId(), actor.getUser().getIdLong(), note);
        log.info("Dashboard: {} flagged the player of ticket {} (flag {})", actor.getId(), ticket.id(), id);
        return forTicket(guild, ticketId);
    }

    DataObject delete(Guild guild, Member actor, long flagId) {
        if (!flags.delete(guild.getIdLong(), flagId)) throw new ApiError(404, Text.of(guild, "dashboard.that_flag_doesn_t_exist"));
        log.info("Dashboard: {} removed ticket flag {}", actor.getId(), flagId);
        return DataObject.empty().put("deleted", true);
    }

    private Ticket ticket(Guild guild, long ticketId) {
        Ticket ticket = tickets.getTicket(ticketId);
        if (ticket == null || ticket.guildId() != guild.getIdLong()) throw new ApiError(404, Text.of(guild, "dashboard.that_ticket_doesn_t_exist"));
        return ticket;
    }

    private static long id(String raw) {
        if (!raw.matches("\\d{1,18}")) throw new ApiError(404, Text.en("dashboard.that_doesn_t_exist"));
        return Long.parseLong(raw);
    }

    private static String nameOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member.getEffectiveName();
        var user = guild.getJDA().getUserById(userId);
        return user == null ? null : user.getName();
    }

    private static DataObject toJson(Guild guild, Flag flag) {
        return DataObject.empty()
                .put("id", Long.toString(flag.id()))
                .put("ticketId", Long.toString(flag.ticketId()))
                .put("ticketNumber", flag.ticketNumber())
                .put("playerId", Long.toString(flag.playerId()))
                .put("playerName", nameOf(guild, flag.playerId()))
                .put("flaggedBy", Long.toString(flag.flaggedBy()))
                .put("flaggedByName", nameOf(guild, flag.flaggedBy()))
                .put("note", flag.note())
                .put("createdAt", flag.createdAt() == null ? null : flag.createdAt().toInstant().toString());
    }
}
