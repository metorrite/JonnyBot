package com.runeclans.bot.commands.events;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.events.BingoBoardImage;
import com.runeclans.bot.events.EventDefaultsRepository;
import com.runeclans.bot.events.BingoBoardView;
import com.runeclans.bot.events.BingoTemplate;
import com.runeclans.bot.events.EventBoardPanels;
import com.runeclans.bot.events.EventClaimListener;
import com.runeclans.bot.events.EventProblem;
import com.runeclans.bot.events.EventQuickCreate;
import com.runeclans.bot.events.EventRepository;
import com.runeclans.bot.events.EventRepository.ClaimStatus;
import com.runeclans.bot.events.EventRepository.Event;
import com.runeclans.bot.events.EventRules.Status;
import com.runeclans.bot.events.EventService;
import com.runeclans.bot.events.EventSettings;
import com.runeclans.bot.events.EventSignups;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.MemberEventsApi;
import com.runeclans.bot.permission.AdminRoleFilter;
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
 * {@code finish}) with the template's usual settings, close and reopen its signups ({@code close-signups}, {@code reopen-signups}), and name organisers for one event
 * ({@code add-organiser}, {@code remove-organiser}); teams are built with {@code /events team} (see {@link EventsTeamCommand}), prizes and fine-tuning are on the website.
 */
@Command
public class EventsCommand {
    private final EventService service;
    private final EventRepository repository;
    private final MemberEventsApi view;
    private final EventBoardPanels panels;
    private final AdminRoleFilter adminRoleFilter;
    private final EventSignups signups;
    private final EventDefaultsRepository eventDefaults;

    public EventsCommand(EventService service, EventRepository repository, MemberEventsApi view, EventBoardPanels panels, AdminRoleFilter adminRoleFilter, EventSignups signups, EventDefaultsRepository eventDefaults) {
        this.eventDefaults = eventDefaults;
        this.adminRoleFilter = adminRoleFilter;
        this.signups = signups;
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
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.you_haven_t_joined_any_events"));
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
            DataObject verification = e.getObject("verification");
            if (verification.getBoolean("required") && !List.of("VERIFIED", "WAIVED").contains(verification.getString("state"))) {
                line.append(" · verification ").append(verification.getString("state").toLowerCase().replace('_', ' '));
                if (!verification.getString("note").isBlank()) line.append(" (").append(verification.getString("note")).append(")");
            }
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
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.no_events_are_taking_signups_right"));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.is_full_you_re_on_the", e.name(), joined.position()));
            } else {
                var verification = EventSettings.verificationOf(DataObject.fromJson(e.config()));
                Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.you_re_in", e.name()),
                        verification.required() ? Text.of(event, "events.this_event_asks_for_verification_you", com.runeclans.bot.events.EventRoster.askText(verification)) : Text.of(event, "events.check_events_mine_for_your_buy"));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.you_can_only_leave_while_an"));
                return;
            }
            service.leave(guildId, id, event.getUser().getIdLong());
            Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "events.you_ve_left_event", id));
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
                    .filter(e -> com.runeclans.bot.events.ClanVsClan.plays(e, BingoTemplate.KEY) && (e.status() == Status.RUNNING || e.status() == Status.SCORING)).findFirst().orElse(null);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
            return;
        }
        if (target == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.you_aren_t_in_a_running"));
            return;
        }
        if (!com.runeclans.bot.events.ClanVsClan.plays(target, BingoTemplate.KEY)) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.that_event_doesn_t_have_a"));
            return;
        }
        if (repository.participant(target.id(), userId).isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.you_aren_t_in", target.name()));
            return;
        }
        event.deferReply(true).queue();
        try {
            DataObject config = DataObject.fromJson(target.config());
            var tiles = panels.personalTiles(target, userId);
            int done = (int) tiles.stream().filter(BingoBoardImage.TileView::done).count();
            byte[] png = BingoBoardImage.render(config, target.name(), "Your board",
                    done + " of " + tiles.size() + " tiles done · submit proof with /events submit event:" + target.id(), tiles);
            event.getHook().sendFiles(FileUpload.fromData(png, "my-bingo-board.png")).queue();
        } catch (IOException | RuntimeException e) {
            event.getHook().sendMessage(Text.of(event, "events.i_couldn_t_draw_the_board")).queue();
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
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.there_no_tile_use_the_number", tile));
            return;
        }
        EventSettings settings = EventSettings.parse(DataObject.fromJson(target.config()));
        TextChannel review = settings.reviewChannelId() == null ? null : event.getGuild().getTextChannelById(settings.reviewChannelId());
        if (review == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "events.this_event_doesn_t_have_a"));
            return;
        }
        String type = screenshot.getContentType();
        if (type == null || !type.startsWith("image/") || screenshot.getSize() > 8 * 1024 * 1024) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.attach_a_screenshot_an_image_up"));
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
                        .addFiles(FileUpload.fromData(stream, screenshot.getFileName())).setComponents(EventClaimListener.buttons(review.getGuild().getIdLong(), eventId, claimId)).submit())
                .whenComplete((message, error) -> {
                    if (error != null) {
                        repository.decideClaim(eventId, claimId, ClaimStatus.DENIED, event.getJDA().getSelfUser().getIdLong(), "Couldn't be sent to the review channel");
                        event.getHook().editOriginal(Text.of(event, "events.i_couldn_t_pass_your_screenshot")).queue();
                        return;
                    }
                    repository.setClaimProof(eventId, claimId, message.getJumpUrl());
                    event.getHook().editOriginal(Text.of(event, "events.sent_to_staff_as_submission_for", claimId, label)).queue();
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
                         @SlashOption(description = "Bingo: board size or shape. Competition: skill. Boss night: the boss. Scavenger hunt: item count") @Nullable String detail,
                         @SlashOption(description = "Bingo only: what the board is drawn on (default RuneScape)", usePredefinedChoices = true) @Nullable EventQuickCreate.Look look) {
        Guild guild = event.getGuild();
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_can_create_events"));
            return;
        }
        long guildId = guild.getIdLong();
        try {
            EventQuickCreate.Plan plan = EventQuickCreate.plan(kind, name, days == null ? 7 : days, startsInHours == null ? 24 : startsInHours, detail, Instant.now(), look == null ? null : look.id,
                    eventDefaults.get(guildId));
            long id = service.createEvent(guildId, event.getUser().getIdLong(), plan.input());
            service.moveStage(guildId, id, Status.SIGNUPS);
            Containers.reply(event, Containers.SUCCESS, false,
                    Text.of(event, "events.new_event", plan.input().name()),
                    plan.summary(),
                    Text.of(event, "events.sign_up_with_events_join_id", id),
                    Text.of(event, "events.staff_prizes_and_buy_ins_are", id));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_and_this_event_organisers"));
                return;
            }
            service.moveStage(guildId, id, to);
            Containers.reply(event, Containers.SUCCESS, false, "**" + e.name() + "** " + what + ".", "-# " + hint);
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", subcommand = "close-signups", description = "Staff: stop taking signups, pause the signup and sync the roster one last time")
    public void onCloseSignups(GuildSlashEvent event, @SlashOption(description = "The event's number") long id) {
        Guild guild = event.getGuild();
        try {
            Event e = service.event(guild.getIdLong(), id);
            if (!service.mayOrganise(e, event.getUser().getIdLong(), isStaff(guild, event.getMember()))) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_and_this_event_organisers"));
                return;
            }
            EventSignups.Closed closed = signups.closeSignups(guild, id, event.getUser().getIdLong());
            List<String> lines = new ArrayList<>();
            lines.add("**" + e.name() + "**: signups are closed.");
            lines.add("-# " + closed.active() + " on the roster" + (closed.waiting() > 0 ? ", " + closed.waiting() + " waiting" : "") + "; the last sync added " + closed.added()
                    + ". Check the roster on the dashboard's Event manager, then confirm it.");
            if (!closed.refused().isEmpty()) lines.add("-# " + closed.refused().size() + " signup" + (closed.refused().size() == 1 ? " was" : "s were") + " turned away by the event's rules; the dashboard says why.");
            Containers.reply(event, Containers.SUCCESS, false, lines.toArray(String[]::new));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    @JDASlashCommand(name = "events", subcommand = "reopen-signups", description = "Staff: take signups again after closing them")
    public void onReopenSignups(GuildSlashEvent event, @SlashOption(description = "The event's number") long id) {
        Guild guild = event.getGuild();
        try {
            Event e = service.event(guild.getIdLong(), id);
            if (!service.mayOrganise(e, event.getUser().getIdLong(), isStaff(guild, event.getMember()))) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_and_this_event_organisers"));
                return;
            }
            signups.reopenSignups(guild, id);
            Containers.reply(event, Containers.SUCCESS, false, Text.of(event, "events.signups_are_open_again", e.name()), Text.of(event, "events.the_roster_has_to_be_confirmed"));
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
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.only_staff_can_change_who_organises"));
            return;
        }
        if (user.isBot()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "events.choose_a_person_not_a_bot"));
            return;
        }
        long guildId = event.getGuild().getIdLong();
        try {
            Event e = service.event(guildId, id);
            var after = service.changeOrganisers(guildId, id, add ? List.of(user.getIdLong()) : List.of(), add ? List.of() : List.of(user.getIdLong()));
            Containers.replyEphemeral(event, Containers.SUCCESS, (add ? user.getAsMention() + " can now run **" + e.name() + "**: start and finish it, and approve or deny proof."
                    : user.getAsMention() + " is no longer an organiser of **" + e.name() + "**."), Text.of(event, "events.organiser_on_this_event", after.size(), (after.size() == 1 ? "" : "s")));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, refusal(p));
        }
    }

    private static String trim(String text) {
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }
}
