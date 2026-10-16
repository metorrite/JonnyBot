package com.younglings.bot.commands.events;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.BingoBoardImage;
import com.younglings.bot.events.BingoBoardView;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.EventBoardPanels;
import com.younglings.bot.events.EventClaimListener;
import com.younglings.bot.events.EventProblem;
import com.younglings.bot.events.EventQuickCreate;
import com.younglings.bot.events.EventRepository;
import com.younglings.bot.events.EventRepository.ClaimStatus;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventService;
import com.younglings.bot.events.EventSettings;
import com.younglings.bot.internal.MemberEventsApi;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /events}: what a member can do with the server's events from Discord. {@code mine} shows their place, points, buy-in and prizes,
 * {@code open} lists events taking signups, and {@code join} / {@code leave} sign up or step out. Everything is the same engine and rules as the
 * website, so the team policy, deadline, waitlist and buy-in all apply. Staff can also start an event from here ({@code create}, {@code start},
 * {@code finish}) with the template's usual settings, and name organisers for one event ({@code add-organiser}, {@code remove-organiser}); prizes, teams and fine-tuning are on the website.
 */
@Command
public class EventsCommand {
    private final EventService service;
    private final EventRepository repository;
    private final MemberEventsApi view;
    private final EventBoardPanels panels;
    private final AdminRoleFilter adminRoleFilter;

    public EventsCommand(EventService service, EventRepository repository, MemberEventsApi view, EventBoardPanels panels, AdminRoleFilter adminRoleFilter) {
        this.adminRoleFilter = adminRoleFilter;
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
            var layout = BingoTemplate.layoutOf(config);
            byte[] png = layout.isPresent()
                    ? BingoBoardImage.render(layout.get(), tiles)
                    : BingoBoardImage.render(target.name(), "Your board", BingoBoardView.boardSize(config), tiles,
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
                + "\nTile " + tile + ": " + label + extra + "\n-# Approve or deny it with the buttons, or on the dashboard (Event manager, Progress and proof).";
        screenshot.getProxy().download().thenCompose(stream -> review.sendMessage(text).setAllowedMentions(List.of())
                        .addFiles(FileUpload.fromData(stream, screenshot.getFileName())).setComponents(EventClaimListener.buttons(eventId, claimId)).submit())
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

    private boolean isStaff(Guild guild, Member member) {
        return member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
    }

    private static String refusal(EventProblem p) {
        return p.problems().isEmpty() ? p.getMessage() : p.getMessage() + "\n- " + String.join("\n- ", p.problems());
    }

    @JDASlashCommand(name = "events", subcommand = "create", description = "Staff: start a new event with the usual settings")
    public void onCreate(GuildSlashEvent event,
                         @SlashOption(description = "The kind of event", usePredefinedChoices = true) EventQuickCreate.Kind kind,
                         @SlashOption(description = "The event's name") String name,
                         @SlashOption(description = "How many days it runs (default 7)") @Nullable Integer days,
                         @SlashOption(name = "starts_in_hours", description = "Hours until it starts (default 24)") @Nullable Integer startsInHours,
                         @SlashOption(description = "Bingo: board size. Competition: skill. Boss night: the boss. Scavenger hunt: item count") @Nullable String detail) {
        Guild guild = event.getGuild();
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, "Only staff can create events.");
            return;
        }
        long guildId = guild.getIdLong();
        try {
            EventQuickCreate.Plan plan = EventQuickCreate.plan(kind, name, days == null ? 7 : days, startsInHours == null ? 24 : startsInHours, detail, Instant.now());
            long id = service.createEvent(guildId, event.getUser().getIdLong(), plan.input());
            service.moveStage(guildId, id, Status.SIGNUPS);
            Containers.reply(event, Containers.SUCCESS, false,
                    "### 🎉 New event: " + plan.input().name(),
                    plan.summary(),
                    "Sign up with `/events join id:" + id + "`. See everything open with `/events open`.",
                    "-# Staff: teams, prizes and buy-ins are on the dashboard's Event manager, and `/events start id:" + id + "` begins it.");
        } catch (IllegalArgumentException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", subcommand = "start", description = "Staff: start an event")
    public void onStart(GuildSlashEvent event, @SlashOption(description = "The event's number, from /events open") long id) {
        moveEvent(event, id, Status.RUNNING, "is on", "Players can now earn points.");
    }

    @JDASlashCommand(name = "events", subcommand = "finish", description = "Staff: stop an event taking progress so prizes can be sorted")
    public void onFinish(GuildSlashEvent event, @SlashOption(description = "The event's number") long id) {
        moveEvent(event, id, Status.SCORING, "has finished", "Standings are final once you've checked the proof. Close it out and pay prizes on the dashboard.");
    }

    private void moveEvent(GuildSlashEvent event, long id, Status to, String what, String hint) {
        long guildId = event.getGuild().getIdLong();
        try {
            Event e = service.event(guildId, id);
            if (!service.mayOrganise(e, event.getUser().getIdLong(), isStaff(event.getGuild(), event.getMember()))) {
                Containers.replyEphemeral(event, Containers.WARNING, "Only staff and this event's organisers can do that.");
                return;
            }
            service.moveStage(guildId, id, to);
            Containers.reply(event, Containers.SUCCESS, false, "**" + e.name() + "** " + what + ".", "-# " + hint);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", subcommand = "add-organiser", description = "Staff: let someone run one event (start it, finish it, decide proof)")
    public void onAddOrganiser(GuildSlashEvent event, @SlashOption(description = "The event's number") long id, @SlashOption(description = "The person to make an organiser") User user) {
        changeOrganiser(event, id, user, true);
    }

    @JDASlashCommand(name = "events", subcommand = "remove-organiser", description = "Staff: take someone off an event's organisers")
    public void onRemoveOrganiser(GuildSlashEvent event, @SlashOption(description = "The event's number") long id, @SlashOption(description = "The organiser to remove") User user) {
        changeOrganiser(event, id, user, false);
    }

    private void changeOrganiser(GuildSlashEvent event, long id, User user, boolean add) {
        if (!isStaff(event.getGuild(), event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, "Only staff can change who organises an event.");
            return;
        }
        if (user.isBot()) {
            Containers.replyEphemeral(event, Containers.WARNING, "Choose a person, not a bot.");
            return;
        }
        long guildId = event.getGuild().getIdLong();
        try {
            Event e = service.event(guildId, id);
            var after = service.changeOrganisers(guildId, id, add ? List.of(user.getIdLong()) : List.of(), add ? List.of() : List.of(user.getIdLong()));
            Containers.replyEphemeral(event, Containers.SUCCESS, (add ? user.getAsMention() + " can now run **" + e.name() + "**: start and finish it, and approve or deny proof."
                    : user.getAsMention() + " is no longer an organiser of **" + e.name() + "**."), "-# " + after.size() + " organiser" + (after.size() == 1 ? "" : "s") + " on this event.");
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    private static String trim(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
