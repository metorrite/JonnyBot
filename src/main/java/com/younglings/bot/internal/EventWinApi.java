package com.younglings.bot.internal;

import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.ClanVsClan;
import com.younglings.bot.events.EventDraws;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.WinModes;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * How an event is won, for the dashboard's event manager (T-649): {@code GET events/{id}/win} (the rules in words, who would qualify for a draw right
 * now or the finishing order so far, and the draw with its seed and entries), {@code PUT events/{id}/win} (save the rules; only until the winners are
 * drawn or the awards worked out), {@code POST events/{id}/win/draw} ("Draw now") and {@code POST events/{id}/win/reroll} ({@code {place}}: draw again
 * for a winner who doesn't claim). Reached through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server
 * (staff) and audit-logs every change.
 */
@BService
public class EventWinApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventWinApi.class);
    static final int ORDER_SHOWN = 10;

    private final EventService service;
    private final EventRepository repository;
    private final EventDraws draws;

    public EventWinApi(EventService service, EventRepository repository, EventDraws draws) {
        this.service = service;
        this.repository = repository;
        this.draws = draws;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 3 || parts.length > 4 || !parts[0].equals("events") || !parts[2].equals("win")) return null;
        if (parts.length == 4 && !parts[3].equals("draw") && !parts[3].equals("reroll")) return null;
        try {
            if (!parts[1].matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_event"));
            long id = Long.parseLong(parts[1]);
            if (parts.length == 3) {
                if (method.equals("GET")) return view(guild, id);
                AdminRouteExtension.requireMethod(method, "PUT");
                save(guild, id, body.get());
                log.info("Dashboard: {} changed how event {} is won", actor.getId(), id);
                return view(guild, id);
            }
            AdminRouteExtension.requireMethod(method, "POST");
            if (parts[3].equals("draw")) {
                draws.drawNow(guild, id, actor.getIdLong());
                log.info("Dashboard: {} drew the winners of event {}", actor.getId(), id);
            } else {
                draws.reroll(guild, id, placeOf(body.get()), actor.getIdLong());
            }
            return view(guild, id);
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    private static int placeOf(DataObject body) {
        try {
            Object v = body.get("place");
            int place = v instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(v).strip());
            if (place < 1 || place > WinModes.MAX_WINNERS) throw new NumberFormatException();
            return place;
        } catch (RuntimeException e) {
            throw new ApiError(400, "Say which place to draw again for.");
        }
    }

    void save(Guild guild, long id, DataObject sent) {
        Event event = service.event(guild.getIdLong(), id);
        boolean bingo = ClanVsClan.plays(event, BingoTemplate.KEY);
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new ApiError(409, "This event is over, so how it's won can't change.");
        if (!draws.rounds(id).isEmpty()) throw new ApiError(409, "The winners were already drawn, so how the event is won can't change.");
        if (!repository.awards(id).isEmpty()) throw new ApiError(409, "The awards were already worked out, so how the event is won can't change.");
        List<String> problems = WinModes.problems(sent, bingo);
        if (!problems.isEmpty()) throw new ApiError(400, "Those win rules can't be saved yet.", problems);
        DataObject config = WinModes.withSettings(DataObject.fromJson(event.config()), WinModes.read(sent));
        if (!repository.setConfig(guild.getIdLong(), id, config.toString())) throw new ApiError(404, "That event doesn't exist in this server.");
    }

    DataObject view(Guild guild, long id) {
        Event event = service.event(guild.getIdLong(), id);
        boolean bingo = ClanVsClan.plays(event, BingoTemplate.KEY);
        WinModes.Settings win = WinModes.of(DataObject.fromJson(event.config()));
        List<WinModes.Round> rounds = draws.rounds(id);
        var teams = repository.teams(id);
        boolean over = event.status() == Status.DONE || event.status() == Status.CANCELLED;
        boolean running = event.status() == Status.RUNNING || event.status() == Status.SCORING || event.status() == Status.DONE;
        DataObject out = DataObject.empty().put("bingo", bingo).put("settings", WinModes.toJson(win)).put("isDefault", win.isDefault())
                .put("status", event.status().name()).put("endsAt", event.endsAt() == null ? null : event.endsAt().toString())
                .put("limits", DataObject.empty().put("maxWinners", WinModes.MAX_WINNERS).put("maxMinTiles", WinModes.MAX_MIN_TILES))
                .put("canChange", !over && rounds.isEmpty() && repository.awards(id).isEmpty())
                .put("mayDraw", win.draws() && running && WinModes.mayDraw(rounds))
                .put("hasPrizes", !service.prizes(id).isEmpty());
        DataArray lines = DataArray.empty();
        WinModes.describe(win).forEach(lines::add);
        out.put("rules", lines);

        // who would qualify right now (a draw) or the finishing order so far (first to a line or the board), so staff see it before the end
        DataArray now = DataArray.empty();
        if (running && win.draws()) {
            Instant until = event.endsAt() != null && event.endsAt().isBefore(Instant.now()) ? event.endsAt() : Instant.now();
            var participants = repository.participants(id);
            for (WinModes.Entrant e : WinModes.entrants(win, service.objectives(id), service.progressLog(event), until, subject -> {
                long sid = Long.parseLong(subject.substring(2));
                if (subject.startsWith("t:")) return teams.stream().anyMatch(t -> t.id() == sid);
                return participants.stream().anyMatch(p -> p.userId() == sid && !p.waiting());
            })) {
                now.add(DataObject.empty().put("name", name(guild, e.subject(), teams)).put("tiles", e.tiles()).put("tickets", e.tickets()));
            }
        }
        out.put("qualifiers", now);
        DataArray order = DataArray.empty();
        if (running && (win.mode() == WinModes.Mode.FIRST_LINE || win.mode() == WinModes.Mode.FIRST_BLACKOUT)) {
            List<String> subjects = WinModes.finishOrder(win.mode(), service.objectives(id), service.bonusesOf(event), service.progressLog(event));
            for (int i = 0; i < Math.min(ORDER_SHOWN, subjects.size()); i++) order.add(DataObject.empty().put("place", i + 1).put("name", name(guild, subjects.get(i), teams)));
        }
        out.put("finishOrder", order);
        out.put("draw", rounds.isEmpty() ? null : drawJson(guild, teams, rounds, true));
        return out;
    }

    /**
     * The draw as the website shows it: the winners now (place 1 first), and every round with its seed and the entries it drew from, so anyone can check
     * it. {@code staff} adds who pressed the button.
     */
    static DataObject drawJson(Guild guild, List<EventRepository.Team> teams, List<WinModes.Round> rounds, boolean staff) {
        DataArray winners = DataArray.empty();
        List<String> places = WinModes.winners(rounds);
        for (int i = 0; i < places.size(); i++) {
            String s = places.get(i);
            winners.add(DataObject.empty().put("place", i + 1).put("name", s == null ? null : name(guild, s, teams)));
        }
        DataArray list = DataArray.empty();
        for (WinModes.Round r : rounds) {
            DataArray entries = DataArray.empty();
            for (WinModes.Entrant e : r.entrants()) entries.add(DataObject.empty().put("name", name(guild, e.subject(), teams)).put("tiles", e.tiles()).put("tickets", e.tickets()));
            DataArray picked = DataArray.empty();
            r.picked().forEach(p -> picked.add(name(guild, p, teams)));
            DataObject row = DataObject.empty().put("round", r.round()).put("seed", Long.toUnsignedString(r.seed())).put("at", r.at().toString()).put("auto", r.by() == null)
                    .put("place", r.place()).put("replaced", r.replaced() == null ? null : name(guild, r.replaced(), teams)).put("picked", picked)
                    .put("entries", entries).put("tickets", r.tickets());
            if (staff) row.put("by", r.by() == null ? null : String.valueOf(r.by()));
            list.add(row);
        }
        return DataObject.empty().put("winners", winners).put("rounds", list);
    }

    static String name(Guild guild, String subject, List<EventRepository.Team> teams) {
        long id = Long.parseLong(subject.substring(2));
        if (subject.startsWith("t:")) return teams.stream().filter(t -> t.id() == id).map(EventRepository.Team::name).findFirst().orElse("A team");
        Member member = guild.getMemberById(id);
        return member == null ? "Player" : member.getEffectiveName();
    }
}
