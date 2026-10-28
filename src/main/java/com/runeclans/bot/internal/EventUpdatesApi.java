package com.runeclans.bot.internal;

import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.BoardUpdates;
import com.runeclans.bot.events.ClanVsClan;
import com.runeclans.bot.events.EventBoardPanels;
import com.runeclans.bot.events.EventBoardUpdates;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventStandings;
import com.runeclans.bot.events.EventUpdateRepository;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * A bingo's board update rules for the dashboard's Updates section (T-650): {@code GET events/{id}/updates} (the rules, each in plain words, a preview of
 * every post, when a held-back board is next drawn and the latest posts) and {@code PUT events/{id}/updates} (save them; allowed at any stage until the
 * event is over, and saving never posts anything by itself). The rules are {@link BoardUpdates}'s. Reached through {@link TicketAdminApi}, which has
 * already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class EventUpdatesApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EventUpdatesApi.class);
    static final int POSTS_SHOWN = 10;

    private final EventService service;
    private final EventRepository repository;
    private final EventUpdateRepository posts;
    private final EventBoardPanels panels;
    private final EventBoardUpdates updates;

    public EventUpdatesApi(EventService service, EventRepository repository, EventUpdateRepository posts, EventBoardPanels panels, EventBoardUpdates updates) {
        this.service = service;
        this.repository = repository;
        this.posts = posts;
        this.panels = panels;
        this.updates = updates;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 3 || !parts[0].equals("events") || !parts[2].equals("updates")) return null;
        try {
            if (!parts[1].matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_event"));
            long id = Long.parseLong(parts[1]);
            if (method.equals("GET")) return view(guild, id);
            AdminRouteExtension.requireMethod(method, "PUT");
            save(guild, id, body.get());
            log.info("Dashboard: {} changed the board update rules of event {}", actor.getId(), id);
            return view(guild, id);
        } catch (EventProblem e) {
            throw new ApiError(e.status() == 500 ? 500 : e.status(), e.getMessage(), e.problems());
        }
    }

    void save(Guild guild, long id, DataObject sent) {
        Event event = service.event(guild.getIdLong(), id);
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) throw new ApiError(409, "Only a bingo has board update rules.");
        if (event.status() == Status.DONE || event.status() == Status.CANCELLED) throw new ApiError(409, "This event is over, so its update rules can't change.");
        List<String> problems = BoardUpdates.problems(sent);
        if (!problems.isEmpty()) throw new ApiError(400, "Those update rules can't be saved yet.", problems);
        BoardUpdates.Settings rules = BoardUpdates.read(sent);
        if (rules.channelId() != null && guild.getChannelById(GuildMessageChannel.class, rules.channelId()) == null) {
            throw new ApiError(400, "Those update rules can't be saved yet.", List.of("The channel for the posts isn't a text channel in this server."));
        }
        DataObject config = BoardUpdates.withSettings(DataObject.fromJson(event.config()), rules);
        if (!repository.setConfig(guild.getIdLong(), id, config.toString())) throw new ApiError(404, "That event doesn't exist in this server.");
    }

    DataObject view(Guild guild, long id) {
        Event event = service.event(guild.getIdLong(), id);
        boolean bingo = ClanVsClan.plays(event, BingoTemplate.KEY);
        DataObject config = DataObject.fromJson(event.config());
        BoardUpdates.Settings rules = BoardUpdates.of(config);
        EventStandings.Visibility visibility = EventStandings.of(config);
        boolean hidden = !EventStandings.tableShown(visibility, Status.RUNNING);
        DataObject out = DataObject.empty().put("bingo", bingo).put("settings", BoardUpdates.toJson(rules)).put("isDefault", rules.isDefault())
                .put("standingsHidden", hidden)
                .put("limits", DataObject.empty().put("minMinutes", BoardUpdates.MIN_EVERY_MINUTES).put("maxMinutes", BoardUpdates.MAX_EVERY_MINUTES));
        DataArray lines = DataArray.empty();
        BoardUpdates.describe(rules, hidden).forEach(lines::add);
        out.put("rules", lines);
        DataObject previews = DataObject.empty();
        BoardUpdates.previews(event.name(), rules, bingo ? service.objectives(id) : List.of(), Instant.now(), hidden ? EventStandings.hiddenText(visibility) : null).forEach(previews::put);
        out.put("previews", previews);
        Instant next = panels.nextUpdate(id, rules);
        out.put("nextBoardUpdate", next == null ? null : next.toString());
        out.put("nextReset", BoardUpdates.nextReset(Instant.now()).toString());
        Long channel = bingo ? updates.channelFor(event, rules) : null;
        out.put("postChannelId", channel == null ? null : String.valueOf(channel));
        DataArray recent = DataArray.empty();
        for (EventUpdateRepository.Post p : posts.recent(id, POSTS_SHOWN)) {
            recent.add(DataObject.empty().put("key", p.key()).put("kind", p.kind()).put("text", p.text()).put("at", p.at().toString()));
        }
        out.put("posts", recent);
        return out;
    }
}
