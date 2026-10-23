package com.younglings.bot.internal;

import com.younglings.bot.appeal.AppealLinks;
import com.younglings.bot.appeal.AppealRepository;
import com.younglings.bot.appeal.AppealRules;
import com.younglings.bot.appeal.AppealRules.Appeal;
import com.younglings.bot.appeal.AppealService;
import com.younglings.bot.moderation.ModCase;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The dashboard's Appeals page (T-033), under {@code /internal/admin/appeals}. {@link TicketAdminApi} has checked the asker may use the dashboard here and
 * audit-logs every change.
 * <ul>
 *   <li>{@code GET appeals}: the settings, the ticket panels to pick from, the appeal page's address and the appeals (open first).</li>
 *   <li>{@code PUT appeals/settings} {@code {enabled, panelId, cooldownDays, mirrorChannelId}}: off by default.</li>
 *   <li>{@code POST appeals/{id}/approve} {@code {note}} lifts the ban or timeout as the asker (who needs the permission for it);
 *       {@code POST appeals/{id}/reject} {@code {note}} turns it down with the reason the person sees.</li>
 * </ul>
 */
@BService
public class AppealsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AppealsAdminApi.class);

    private final AppealService service;
    private final AppealRepository appeals;
    private final AppealLinks links;
    private final TicketRepository tickets;

    public AppealsAdminApi(AppealService service, AppealRepository appeals, AppealLinks links, TicketRepository tickets) {
        this.service = service;
        this.appeals = appeals;
        this.links = links;
        this.tickets = tickets;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 0 || !parts[0].equals("appeals")) return null;
        try {
            if (parts.length == 1) {
                AdminRouteExtension.requireMethod(method, "GET");
                return view(guild);
            }
            if (parts.length == 2 && parts[1].equals("settings")) {
                AdminRouteExtension.requireMethod(method, "PUT");
                saveSettings(guild, actor, body.get());
                return view(guild);
            }
            if (parts.length == 3 && (parts[2].equals("approve") || parts[2].equals("reject"))) {
                AdminRouteExtension.requireMethod(method, "POST");
                long id = idOf(parts[1]);
                String note = body.get().getString("note", "");
                if (parts[2].equals("reject")) service.reject(guild, actor, id, note);
                else service.approve(guild, actor, id, note).get(20, TimeUnit.SECONDS);
                return view(guild);
            }
            return null;
        } catch (AppealService.AppealProblem e) {
            throw AdminRouteExtension.refuse(e.status(), e.getMessage());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AppealService.AppealProblem p) throw AdminRouteExtension.refuse(p.status(), p.getMessage());
            log.warn("Approving an appeal in {} failed", guild.getId(), e.getCause());
            throw AdminRouteExtension.refuse(500, "That didn't work. Try again in a moment.");
        } catch (TimeoutException e) {
            throw AdminRouteExtension.refuse(504, "Discord is slow to answer. Refresh in a moment to see whether it went through.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AdminRouteExtension.refuse(500, "Interrupted.");
        }
    }

    private static long idOf(String raw) {
        if (raw == null || !raw.matches("\\d{1,18}")) throw AdminRouteExtension.refuse(404, "No such appeal.");
        return Long.parseLong(raw);
    }

    void saveSettings(Guild guild, Member actor, DataObject in) {
        AppealRules.Settings current = appeals.settings(guild.getIdLong());
        boolean enabled = in.hasKey("enabled") && in.isType("enabled", DataType.BOOLEAN) ? in.getBoolean("enabled") : current.enabled();
        Long panelId = in.hasKey("panelId") ? optionalId(in, "panelId") : current.panelId();
        if (panelId != null) {
            Panel panel = tickets.getPanel(panelId);
            if (panel == null || panel.guildId() != guild.getIdLong()) throw AdminRouteExtension.refuse(400, "Pick one of this server's ticket panels.");
        }
        int cooldown = current.cooldownDays();
        if (in.hasKey("cooldownDays") && !in.isNull("cooldownDays")) {
            try {
                cooldown = in.getInt("cooldownDays");
            } catch (RuntimeException e) {
                throw AdminRouteExtension.refuse(400, "The cooldown is a whole number of days.");
            }
            if (cooldown < 0 || cooldown > AppealRules.MAX_COOLDOWN_DAYS) throw AdminRouteExtension.refuse(400, "The cooldown is 0 to " + AppealRules.MAX_COOLDOWN_DAYS + " days.");
        }
        Long mirror = in.hasKey("mirrorChannelId") ? optionalId(in, "mirrorChannelId") : current.mirrorChannelId();
        if (mirror != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, mirror);
            if (channel == null || !channel.canTalk()) throw AdminRouteExtension.refuse(400, "Pick a channel the bot can post in for the copies.");
        }
        appeals.saveSettings(new AppealRules.Settings(guild.getIdLong(), enabled, panelId, cooldown, mirror));
        log.info("Dashboard: {} saved the appeal settings of {} (on: {}, panel {}, cooldown {} d, copies to {})", actor.getId(), guild.getId(), enabled, panelId, cooldown, mirror);
    }

    private static Long optionalId(DataObject in, String key) {
        if (in.isNull(key)) return null;
        String raw = in.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw AdminRouteExtension.refuse(400, "That id looks wrong.");
        return Long.parseUnsignedLong(raw);
    }

    DataObject view(Guild guild) {
        AppealRules.Settings s = appeals.settings(guild.getIdLong());
        DataArray panels = DataArray.empty();
        for (Panel p : tickets.getPanels(guild.getIdLong())) panels.add(DataObject.empty().put("id", Long.toString(p.id())).put("name", p.name()));
        List<Appeal> list = service.list(guild.getIdLong());
        Map<Long, ModCase> byCase = service.casesOf(guild.getIdLong(), list).stream().collect(Collectors.toMap(ModCase::caseNo, c -> c, (a, b) -> a));
        DataArray rows = DataArray.empty();
        for (Appeal a : list) {
            ModCase c = byCase.get(a.caseNo());
            Long channelId = null;
            if (a.ticketId() != null) {
                Ticket t = tickets.getTicket(a.ticketId());
                if (t != null && !t.channelDeleted()) channelId = t.channelId();
            }
            rows.add(DataObject.empty().put("id", Long.toString(a.id())).put("userId", Long.toString(a.userId())).put("userName", a.userName()).put("caseNo", Long.toString(a.caseNo()))
                    .put("action", c == null ? null : c.action().name()).put("actionLabel", c == null ? null : c.action().label).put("caseReason", c == null ? null : c.reason())
                    .put("text", a.text()).put("status", a.status().name()).put("createdAt", a.createdAt().toString())
                    .put("decidedAt", a.decidedAt() == null ? null : a.decidedAt().toString()).put("decidedBy", a.decidedBy() == null ? null : Long.toString(a.decidedBy()))
                    .put("note", a.note()).put("ticketChannelId", channelId == null ? null : Long.toString(channelId)));
        }
        String link = links.linkFor(guild.getIdLong());
        return DataObject.empty().put("settings", DataObject.empty().put("enabled", s.enabled()).put("panelId", s.panelId() == null ? null : Long.toString(s.panelId()))
                        .put("cooldownDays", s.cooldownDays()).put("mirrorChannelId", s.mirrorChannelId() == null ? null : Long.toString(s.mirrorChannelId())))
                .put("panels", panels).put("appeals", rows).put("appealLink", link);
    }
}
