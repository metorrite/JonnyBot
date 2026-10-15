package com.younglings.bot.commands.events;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.internal.MemberEventsApi;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /events}: what a member can do with the server's events from Discord. {@code mine} shows their place, points, buy-in and prizes,
 * {@code open} lists events taking signups, and {@code join} / {@code leave} sign up or step out. Everything is the same engine and rules as the
 * website, so the team policy, deadline, waitlist and buy-in all apply. Creating and running an event stays on the website.
 */
@Command
public class EventsCommand {
    private final EventService service;
    private final EventRepository repository;
    private final MemberEventsApi view;

    public EventsCommand(EventService service, EventRepository repository, MemberEventsApi view) {
        this.service = service;
        this.repository = repository;
        this.view = view;
    }

    private static String gp(long amount) {
        return String.format("%,d gp", amount);
    }

    @TopLevelSlashCommandData(description = "The server's events")
    @JDASlashCommand(name = "events", subcommand = "mine", description = "Your events: place, points, buy-in and prizes")
    public void onMine(GuildSlashEvent event) {
        DataObject data = view.mine(event.getGuild(), event.getUser().getIdLong());
        DataArray events = data.getArray("events");
        if (events.length() == 0) {
            Containers.replyEphemeral(event, Containers.INFO, "You haven't joined any events. See what's open with `/events open`.");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Your events");
        for (int i = 0; i < events.length(); i++) {
            DataObject e = events.getObject(i);
            StringBuilder line = new StringBuilder("`#" + e.getString("id") + "` **" + e.getString("name") + "** · " + e.getString("status").toLowerCase().replace('_', ' '));
            if (e.getBoolean("waiting")) {
                line.append(" · on the waitlist");
            } else if (e.getInt("rank") > 0) {
                line.append(" · place ").append(e.getInt("rank")).append(" of ").append(e.getInt("of")).append(" · ").append(e.getLong("points")).append(" pts");
            }
            DataObject buyIn = e.getObject("buyIn");
            if (buyIn.getBoolean("enabled") && buyIn.getString("status").equals("DUE")) line.append(" · buy-in due (").append(gp(buyIn.getLong("amount"))).append(")");
            if (e.getInt("pendingProof") > 0) line.append(" · ").append(e.getInt("pendingProof")).append(" proof waiting");
            DataArray awards = e.getArray("awards");
            for (int a = 0; a < awards.length(); a++) {
                DataObject award = awards.getObject(a);
                line.append("\n-# prize #").append(award.getInt("place")).append(": ").append(award.getString("description").isBlank() ? award.getString("kind").toLowerCase() : award.getString("description"))
                        .append(" · ").append(award.getString("status").toLowerCase());
            }
            lines.add(line.toString());
        }
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "events", subcommand = "open", description = "Events you can sign up for")
    public void onOpen(GuildSlashEvent event) {
        List<Event> open = repository.events(event.getGuild().getIdLong(), List.of(Status.SIGNUPS), 15);
        if (open.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "No events are taking signups right now.");
            return;
        }
        List<String> lines = new ArrayList<>();
        lines.add("### Open for signup");
        for (Event e : open) {
            lines.add("`#" + e.id() + "` **" + e.name() + "** · " + (e.scoringMode().name().equals("TEAM") ? "teams" : "solo") + (e.description().isBlank() ? "" : "\n-# " + trim(e.description())));
        }
        lines.add("-# Sign up with /events join and the event's number.");
        Containers.replyEphemeral(event, Containers.INFO, lines.toArray(String[]::new));
    }

    @JDASlashCommand(name = "events", subcommand = "join", description = "Sign up for an event")
    public void onJoin(GuildSlashEvent event, @SlashOption(description = "The event's number, from /events open") long id) {
        long guildId = event.getGuild().getIdLong();
        try {
            var joined = service.join(guildId, id, guildId, event.getUser().getIdLong(), null, false, null);
            Event e = service.event(guildId, id);
            if (joined.waiting()) {
                Containers.replyEphemeral(event, Containers.WARNING, "**" + e.name() + "** is full. You're on the waitlist at position " + joined.position() + ".");
            } else {
                Containers.replyEphemeral(event, Containers.SUCCESS, "You're in **" + e.name() + "**.", "-# Check `/events mine` for your buy-in and team once the organisers set them.");
            }
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
        }
    }

    @JDASlashCommand(name = "events", subcommand = "leave", description = "Step out of an event")
    public void onLeave(GuildSlashEvent event, @SlashOption(description = "The event's number, from /events mine") long id) {
        long guildId = event.getGuild().getIdLong();
        try {
            service.event(guildId, id);
            service.leave(guildId, id, event.getUser().getIdLong());
            Containers.replyEphemeral(event, Containers.SUCCESS, "You've left event #" + id + ".");
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
        }
    }

    private static String trim(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
