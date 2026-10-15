package com.younglings.bot.commands.events;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.BingoBoardImage;
import com.younglings.bot.events.BingoBoardView;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventBoardPanels;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.ClaimStatus;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSettings;
import com.younglings.bot.internal.MemberEventsApi;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
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
    private final EventBoardPanels panels;

    public EventsCommand(EventService service, EventRepository repository, MemberEventsApi view, EventBoardPanels panels) {
        this.service = service;
        this.repository = repository;
        this.view = view;
        this.panels = panels;
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
            Event existing = service.event(guildId, id);
            if (existing.status() != Status.SIGNUPS) {
                Containers.replyEphemeral(event, Containers.WARNING, "You can only leave while an event is taking signups. Ask an organiser if you need out of one that has started.");
                return;
            }
            service.leave(guildId, id, event.getUser().getIdLong());
            Containers.replyEphemeral(event, Containers.SUCCESS, "You've left event #" + id + ".");
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
        }
    }

    @JDASlashCommand(name = "events", subcommand = "board", description = "See your bingo board")
    public void onBoard(GuildSlashEvent event, @SlashOption(name = "event", description = "The event's number (your latest running bingo by default)") @Nullable Long eventId) {
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();
        Event target;
        try {
            target = eventId != null ? service.event(guildId, eventId) : repository.eventsForUser(guildId, userId, 30).stream()
                    .filter(e -> BingoTemplate.KEY.equals(e.template()) && (e.status() == Status.RUNNING || e.status() == Status.SCORING)).findFirst().orElse(null);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
            return;
        }
        if (target == null) {
            Containers.replyEphemeral(event, Containers.INFO, "You aren't in a running bingo. See `/events mine`.");
            return;
        }
        if (!BingoTemplate.KEY.equals(target.template())) {
            Containers.replyEphemeral(event, Containers.INFO, "That event doesn't have a board.");
            return;
        }
        if (repository.participant(target.id(), userId).isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, "You aren't in **" + target.name() + "**.");
            return;
        }
        event.deferReply(true).queue();
        try {
            DataObject config = DataObject.fromJson(target.config());
            var tiles = panels.personalTiles(target, userId);
            int done = (int) tiles.stream().filter(BingoBoardImage.TileView::done).count();
            byte[] png = BingoBoardImage.render(target.name(), "Your board", BingoBoardView.boardSize(config), tiles,
                    done + " of " + tiles.size() + " tiles done · submit proof with /events submit event:" + target.id());
            event.getHook().sendFiles(FileUpload.fromData(png, "my-bingo-board.png")).queue();
        } catch (IOException | RuntimeException e) {
            event.getHook().sendMessage("I couldn't draw the board right now.").queue();
        }
    }

    @JDASlashCommand(name = "events", subcommand = "submit", description = "Send a screenshot as proof for a tile or objective")
    public void onSubmit(GuildSlashEvent event,
                         @SlashOption(name = "event", description = "The event's number, from /events mine") long eventId,
                         @SlashOption(description = "The tile's number on the board") long tile,
                         @SlashOption(description = "A screenshot of the drop or the kill") Message.Attachment screenshot,
                         @SlashOption(description = "Anything staff should know") @Nullable String note) {
        long guildId = event.getGuild().getIdLong();
        long userId = event.getUser().getIdLong();
        Event target;
        String key;
        try {
            target = service.event(guildId, eventId);
            key = BingoBoardView.keyForNumber(DataObject.fromJson(target.config()), service.objectives(eventId), (int) tile);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
            return;
        }
        if (key == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "There's no tile " + tile + ". Use the number drawn on the tile.");
            return;
        }
        EventSettings settings = EventSettings.parse(DataObject.fromJson(target.config()));
        TextChannel review = settings.reviewChannelId() == null ? null : event.getGuild().getTextChannelById(settings.reviewChannelId());
        if (review == null) {
            Containers.replyEphemeral(event, Containers.INFO, "This event doesn't have a review channel yet, so screenshots can't be sent. Ask an organiser.");
            return;
        }
        String type = screenshot.getContentType();
        if (type == null || !type.startsWith("image/") || screenshot.getSize() > 8 * 1024 * 1024) {
            Containers.replyEphemeral(event, Containers.WARNING, "Attach a screenshot (an image, up to 8 MB).");
            return;
        }
        long claimId;
        String label;
        try {
            label = service.objectives(eventId).stream().filter(o -> o.key().equals(key)).map(o -> o.label()).findFirst().orElse(key);
            claimId = service.submitClaim(guildId, eventId, key, userId, 1, note == null ? "" : note, screenshot.getUrl());
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
            return;
        }
        event.deferReply(true).queue();
        String extra = note == null || note.isBlank() ? "" : "\n" + note.strip().replaceAll("[\\r\\n]+", " ");
        String text = "**" + target.name() + " · submission #" + claimId + "** from " + event.getUser().getAsMention()
                + "\nTile " + tile + ": " + label + extra + "\n-# Approve or deny it on the dashboard: Event manager, Progress and proof.";
        screenshot.getProxy().download().thenCompose(stream -> review.sendMessage(text).setAllowedMentions(List.of())
                        .addFiles(FileUpload.fromData(stream, screenshot.getFileName())).submit())
                .whenComplete((message, error) -> {
                    if (error != null) {
                        repository.decideClaim(eventId, claimId, ClaimStatus.DENIED, event.getJDA().getSelfUser().getIdLong(), "Couldn't be sent to the review channel");
                        event.getHook().editOriginal("I couldn't pass your screenshot to staff. Try again in a moment.").queue();
                        return;
                    }
                    repository.setClaimProof(eventId, claimId, message.getJumpUrl());
                    event.getHook().editOriginal("Sent to staff as submission #" + claimId + " for **" + label + "**. You'll get a DM when they decide.").queue();
                });
    }

    private static String trim(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
