package com.younglings.bot.events;

import com.younglings.bot.alerts.AutoPostRouter;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.events.EventRepository.Panel;
import com.younglings.bot.events.EventRules.ObjectiveSpec;
import com.younglings.bot.events.EventRules.Status;
import com.younglings.bot.events.EventScoring.Standing;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a bingo's live board in Discord: one message in the event's board channel with the board as a picture and a short leaderboard, edited
 * whenever progress changes (after a short pause, so a burst of approvals makes one update). Also tells a player when their proof is approved or
 * denied. Nothing here changes an event; it only reads it and posts.
 */
@BService
public class EventBoardPanels {
    private static final Logger log = LoggerFactory.getLogger(EventBoardPanels.class);
    private static final long DELAY_SECONDS = 4;

    private final EventRepository repository;
    private final EventService service;
    private final EventSpaceRepository space;
    private final BingoTiles tiles;
    private final ScheduledExecutorService executor = BotScheduler.lane("event-board-panels");
    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private volatile JDA jda;

    public EventBoardPanels(EventRepository repository, EventService service, EventSpaceRepository space, EventChanges changes, BingoTiles tiles) {
        this.repository = repository;
        this.service = service;
        this.space = space;
        this.tiles = tiles;
        changes.subscribe(this::onChange);
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        log.info("Event boards are ready: they will be posted to Discord when a bingo starts.");
    }

    private void onChange(EventChanges.Change change) {
        if (change.kind() == EventChanges.Kind.CLAIM_DECIDED) {
            executor.execute(() -> tellPlayer(change));
        }
        schedule(change.guildId(), change.eventId());
    }

    private void schedule(long guildId, long eventId) {
        if (jda == null) return;
        ScheduledFuture<?> old = pending.put(eventId, executor.schedule(() -> {
            pending.remove(eventId);
            if (AutoPostRouter.diverting()) return; // board posts are tracked and edited later: a bot that is not live makes none
            try {
                // always logged, so a board that didn't appear can be explained from the logs (the reason is also what the dashboard's button shows)
                log.info("Board of event {}: {}", eventId, refresh(guildId, eventId));
            } catch (RuntimeException e) {
                log.warn("Could not refresh the board of event {}", eventId, e);
            }
        }, DELAY_SECONDS, TimeUnit.SECONDS));
        if (old != null) old.cancel(false);
    }

    private static boolean shown(Status status) {
        return status == Status.RUNNING || status == Status.SCORING || status == Status.DONE;
    }

    /**
     * Posts or updates the event's boards now: the one everybody shares and/or each team's or player's own, as the event's board display says
     * ({@link EventBoards.Display}). Returns a sentence on what happened, for the dashboard's "refresh" button.
     */
    public String refresh(long guildId, long eventId) {
        JDA client = jda;
        if (client == null) return "The bot isn't connected to Discord yet.";
        Guild guild = client.getGuildById(guildId);
        Event event = repository.event(guildId, eventId).orElse(null);
        if (guild == null || event == null) return "That event or server wasn't found.";
        if (!ClanVsClan.plays(event, BingoTemplate.KEY)) return "Only bingo events have a board.";
        if (!shown(event.status())) return "The board is posted once the event starts.";
        EventBoards.Display display = EventBoards.displayOf(DataObject.fromJson(event.config()));
        List<String> parts = new java.util.ArrayList<>();
        if (display.shared()) parts.add(refreshShared(guildId, eventId));
        if (display.own()) parts.add(refreshOwn(guild, event));
        return String.join(" ", parts);
    }

    /**
     * Each team's (or player's) own board, in their own channel when the event made one, else in the live board channel. A board is drawn and sent only
     * when its tiles changed since it was last posted, so a busy event doesn't redraw every card for every approval.
     */
    private String refreshOwn(Guild guild, Event event) {
        long eventId = event.id();
        try {
            DataObject config = DataObject.fromJson(event.config());
            List<ObjectiveSpec> objectives = service.objectives(eventId);
            List<Standing> standings = service.standings(guild.getIdLong(), eventId);
            List<String> keys = BingoBoardView.boardKeys(config);
            boolean byTeam = event.scoringMode() == EventRules.ScoringMode.TEAM;
            var teams = repository.teams(eventId);
            var players = repository.participants(eventId).stream().filter(p -> !p.waiting()).toList();
            Map<String, Long> ownChannel = new java.util.HashMap<>();
            for (EventSpaceRepository.ChannelRow row : space.channels(eventId)) ownChannel.put(row.key(), row.channelId());

            List<EventBoards.Subject> subjects = new java.util.ArrayList<>();
            if (byTeam) {
                for (EventRepository.Team team : teams) {
                    if (players.stream().noneMatch(p -> p.teamId() != null && p.teamId() == team.id())) continue;
                    subjects.add(new EventBoards.Subject("t:" + team.id(), team.name(), ownChannel.get(EventSpace.teamKey(team.id()))));
                }
            } else {
                for (var player : players) {
                    Member member = guild.getMemberById(player.userId());
                    subjects.add(new EventBoards.Subject("u:" + player.userId(), member == null ? "Player" : member.getEffectiveName(), ownChannel.get(EventSpace.playerKey(player.userId()))));
                }
            }
            if (subjects.isEmpty()) return "No own boards yet: nobody is " + (byTeam ? "on a team." : "in the event.");

            Map<String, List<BingoBoardImage.TileView>> tilesOf = new java.util.HashMap<>();
            Map<String, String> hashes = new java.util.HashMap<>();
            for (EventBoards.Subject s : subjects) {
                Standing mine = standings.stream().filter(st -> st.subject().equals(s.key())).findFirst().orElse(null);
                var views = BingoBoardView.personal(keys, objectives, mine, tiles::iconKeyFor);
                tilesOf.put(s.key(), views);
                hashes.put(s.key(), EventBoards.hashOf(views));
            }
            Long fallback = settingsOf(event).boardChannelId();
            var steps = EventBoards.plan(subjects, fallback, repository.boardPanels(eventId), hashes);
            int posted = 0, edited = 0, same = 0, nowhere = 0;
            for (EventBoards.Step step : steps) {
                switch (step.what()) {
                    case UNCHANGED -> same++;
                    case NO_PLACE -> nowhere++;
                    case POST, EDIT -> {
                        if (sendOwn(guild, event, config, step, tilesOf.get(step.subject().key()))) {
                            if (step.what() == EventBoards.What.POST) posted++;
                            else edited++;
                        } else {
                            nowhere++;
                        }
                    }
                }
            }
            return "Own boards: " + posted + " posted, " + edited + " updated, " + same + " unchanged" + (nowhere > 0 ? ", " + nowhere + " with nowhere to post (set up the event's channels, or choose a live board channel)." : ".");
        } catch (RuntimeException e) {
            log.warn("Could not refresh the own boards of event {}", eventId, e);
            return "The own boards couldn't be built: " + e.getMessage();
        }
    }

    /** Draws one subject's board and posts it (or edits the message already there). False when the channel can't be used. */
    private boolean sendOwn(Guild guild, Event event, DataObject config, EventBoards.Step step, List<BingoBoardImage.TileView> tileViews) {
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, step.channelId());
        if (channel == null || !guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES)) return false;
        String subject = step.subject().key();
        int done = (int) tileViews.stream().filter(BingoBoardImage.TileView::done).count();
        String text = "### " + event.name() + " · " + step.subject().name() + "\n" + done + " of " + tileViews.size() + " tiles done · " + stageText(event.status());
        byte[] png;
        try {
            png = BingoBoardImage.render(config, event.name(), step.subject().name() + " · " + stageText(event.status()), done + " of " + tileViews.size() + " tiles done · send proof with /submit event in your channel", tileViews);
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("Could not draw the board of {} in event {}; posting it as text", subject, event.id(), e);
            png = null;
            text = withTiles(text, tileViews);
        }
        FileUpload file = png == null ? null : FileUpload.fromData(png, "bingo-board.png");
        String message = text;
        byte[] picture = png;
        long eventId = event.id();
        long channelId = channel.getIdLong();
        String hash = step.hash();
        if (step.what() == EventBoards.What.EDIT) {
            var existing = repository.boardPanels(eventId).get(subject);
            if (existing != null && existing.channelId() == channelId) {
                var edit = channel.editMessageById(existing.messageId(), message);
                edit = file == null ? edit.setAttachments(java.util.Collections.emptyList()) : edit.setAttachments(file);
                edit.queue(ok -> repository.setBoardPanel(eventId, subject, channelId, existing.messageId(), hash), error -> {
                    log.info("The board message of {} in event {} couldn't be edited ({}); posting a new one", subject, eventId, error.getMessage());
                    postOwn(channel, eventId, subject, message, picture, hash);
                });
                return true;
            }
        }
        postOwn(channel, eventId, subject, message, picture, hash);
        return true;
    }

    private void postOwn(GuildMessageChannel channel, long eventId, String subject, String text, byte[] picture, String hash) {
        var send = channel.sendMessage(text).setAllowedMentions(List.of());
        if (picture != null) send = send.addFiles(FileUpload.fromData(picture, "bingo-board.png"));
        send.queue(message -> repository.setBoardPanel(eventId, subject, channel.getIdLong(), message.getIdLong(), hash),
                error -> log.warn("Could not post the board of {} in event {}", subject, eventId, error));
    }

    /** The one live board everybody shares: how many teams or players have each tile. */
    private String refreshShared(long guildId, long eventId) {
        JDA client = jda;
        if (client == null) return "The bot isn't connected to Discord yet.";
        Guild guild = client.getGuildById(guildId);
        Event event = repository.event(guildId, eventId).orElse(null);
        if (guild == null || event == null) return "That event or server wasn't found.";
        EventSettings settings = settingsOf(event);
        if (settings.boardChannelId() == null) return "Shared board: there is no live board channel yet. Choose or make one on the Channels tab.";
        if (!shown(event.status())) return "The board is posted once the event starts.";
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, settings.boardChannelId());
        if (channel == null) return "The board channel isn't a channel JonnyBot can post in (it may have been deleted, or it is a forum or category).";
        if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_ATTACH_FILES)) {
            return "JonnyBot needs to see, send messages and attach files in #" + channel.getName() + ".";
        }

        byte[] png = null;
        String text;
        List<BingoBoardImage.TileView> tileViews;
        DataObject config;
        List<Standing> standings;
        try {
            config = DataObject.fromJson(event.config());
            List<ObjectiveSpec> objectives = service.objectives(eventId);
            standings = service.standings(guildId, eventId);
            int subjects = subjectCount(event, standings);
            Map<String, String> firstBy = new java.util.HashMap<>();
            if (BingoTemplate.layoutOf(config).isPresent()) {
                // who got each tile first, for the line under a finished tile on the picture shapes
                boolean byTeam = event.scoringMode() == EventRules.ScoringMode.TEAM;
                List<EventScoring.Entry> entries = new java.util.ArrayList<>();
                for (EventRepository.Progress p : repository.progress(eventId)) {
                    if (byTeam && p.teamId() == null) continue;
                    entries.add(new EventScoring.Entry(byTeam ? "t:" + p.teamId() : "u:" + p.userId(), p.objectiveKey(), p.amount(), p.createdAt()));
                }
                var teams = repository.teams(eventId);
                BingoBoardView.firstCompleters(objectives, entries).forEach((key, subject) -> firstBy.put(key, nameOf(guild, subject, teams)));
            }
            tileViews = BingoBoardView.shared(BingoBoardView.boardKeys(config), objectives, standings, subjects, tiles::iconKeyFor, firstBy);
            text = panelText(guild, event, standings);
        } catch (RuntimeException e) {
            log.warn("Could not build the board of event {}", eventId, e);
            return "The board couldn't be built: " + e.getMessage();
        }
        try {
            png = BingoBoardImage.render(config, event.name(),
                    stageText(event.status()) + " · tiles show how many " + (event.scoringMode() == EventRules.ScoringMode.TEAM ? "teams" : "players") + " have them",
                    "Submit proof with /events submit event:" + eventId + " and the tile's number", tileViews);
        } catch (IOException | RuntimeException | LinkageError e) {
            // a server with no usable fonts can't draw text onto a picture (LinkageError: the font system failed to start once and stays unusable);
            // the board is then posted as text so it still works
            log.warn("Could not draw the board picture of event {}; posting it as text", eventId, e);
            text = withTiles(text, tileViews);
        }

        FileUpload file = png == null ? null : FileUpload.fromData(png, "bingo-board.png");
        String message = text;
        byte[] picture = png;
        String how = png == null ? " (as text: the picture couldn't be drawn on this server)" : "";
        Optional<Panel> existing = repository.panel(eventId);
        if (existing.isPresent() && existing.get().channelId() == channel.getIdLong()) {
            var edit = channel.editMessageById(existing.get().messageId(), message);
            edit = file == null ? edit.setAttachments(java.util.Collections.emptyList()) : edit.setAttachments(file);
            edit.queue(
                    ok -> { },
                    error -> {
                        log.info("The board message of event {} couldn't be edited ({}); posting a new one", eventId, error.getMessage());
                        post(channel, eventId, message, picture == null ? null : FileUpload.fromData(picture, "bingo-board.png"));
                    });
            return "Updated the board in #" + channel.getName() + "." + how;
        }
        post(channel, eventId, message, file);
        return "Posted the board in #" + channel.getName() + "." + how;
    }

    /** The panel text with the tiles listed under it, for when the picture can't be drawn. Kept inside Discord's message length. */
    static String withTiles(String text, List<BingoBoardImage.TileView> tileViews) {
        StringBuilder out = new StringBuilder(text).append("\n");
        for (BingoBoardImage.TileView tile : tileViews) {
            String line = "\n" + (tile.done() ? "✅" : "⬜") + " **" + tile.number() + ".** " + tile.label() + " · " + tile.count() + "/" + tile.of();
            if (out.length() + line.length() > 1900) {
                out.append("\n…");
                break;
            }
            out.append(line);
        }
        return out.toString();
    }

    private void post(GuildMessageChannel channel, long eventId, String text, FileUpload file) {
        var send = channel.sendMessage(text).setAllowedMentions(List.of());
        if (file != null) send = send.addFiles(file);
        send.queue(
                message -> repository.setPanel(eventId, channel.getIdLong(), message.getIdLong()),
                error -> log.warn("Could not post the board of event {}", eventId, error));
    }

    private static EventSettings settingsOf(Event event) {
        try {
            return EventSettings.parse(DataObject.fromJson(event.config()));
        } catch (RuntimeException e) {
            return EventSettings.defaults();
        }
    }

    private int subjectCount(Event event, List<Standing> standings) {
        var participants = repository.participants(event.id()).stream().filter(p -> !p.waiting()).toList();
        if (event.scoringMode() == EventRules.ScoringMode.TEAM) {
            return (int) Math.max(standings.size(), participants.stream().filter(p -> p.teamId() != null).map(EventRepository.Participant::teamId).distinct().count());
        }
        return Math.max(standings.size(), participants.size());
    }

    private static String stageText(Status status) {
        return switch (status) {
            case RUNNING -> "Running";
            case SCORING -> "Scoring";
            case DONE -> "Finished";
            default -> status.name().toLowerCase();
        };
    }

    private String panelText(Guild guild, Event event, List<Standing> standings) {
        StringBuilder text = new StringBuilder("### " + event.name() + " · " + stageText(event.status()));
        EventStandings.Visibility visibility = EventStandings.of(DataObject.fromJson(event.config()));
        if (!EventStandings.tableShown(visibility, event.status())) {
            text.append("\n").append(EventStandings.hiddenText(visibility));
        } else if (standings.isEmpty()) {
            text.append("\nNo points yet.");
        } else {
            var teams = repository.teams(event.id());
            int rank = 1;
            for (Standing s : standings) {
                if (rank > 5) break;
                String name = nameOf(guild, s.subject(), teams);
                text.append("\n").append(rank++).append(". **").append(name).append("** · ").append(s.points()).append(" pts · ").append(s.objectivesCompleted()).append(" tiles");
            }
        }
        return text.toString();
    }

    /** A subject of the standings ("u:id" for a player, "t:id" for a team) as a name: the member's server name, or the team's name. */
    private static String nameOf(Guild guild, String subject, List<EventRepository.Team> teams) {
        long id = Long.parseLong(subject.substring(2));
        if (subject.startsWith("t:")) return teams.stream().filter(t -> t.id() == id).map(EventRepository.Team::name).findFirst().orElse("A team");
        Member member = guild.getMemberById(id);
        return member == null ? "Player" : member.getEffectiveName();
    }

    /** DMs a player whose proof was decided. A closed DM is fine: nothing else depends on it. */
    private void tellPlayer(EventChanges.Change change) {
        JDA client = jda;
        if (client == null) return;
        try {
            var claim = repository.claim(change.eventId(), change.claimId()).orElse(null);
            Event event = repository.event(change.guildId(), change.eventId()).orElse(null);
            if (claim == null || event == null) return;
            String tile = claim.kind() == EventRepository.ClaimKind.VERIFICATION ? "verification"
                    : "**" + service.objectives(change.eventId()).stream().filter(o -> o.key().equals(claim.objectiveKey())).map(ObjectiveSpec::label).findFirst().orElse(claim.objectiveKey()) + "**";
            String message = change.approved()
                    ? "Your proof for " + tile + " in **" + event.name() + "** was approved."
                    : "Your proof for " + tile + " in **" + event.name() + "** wasn't accepted" + (change.note().isBlank() ? "." : ": " + change.note())
                    + (claim.kind() == EventRepository.ClaimKind.VERIFICATION ? "\nSend it again with `/submit event-verify`." : "");
            client.retrieveUserById(change.userId()).flatMap(user -> user.openPrivateChannel()).flatMap(dm -> dm.sendMessage(message))
                    .queue(ok -> { }, error -> log.debug("Could not DM {} about claim {}: {}", change.userId(), change.claimId(), error.getMessage()));
        } catch (RuntimeException e) {
            log.warn("Could not tell a player about claim {}", change.claimId(), e);
        }
    }

    /** Takes a deleted event's live board out of Discord, if it had one and the message is still there. */
    public void removeMessage(EventRepository.Panel panel) {
        JDA client = jda;
        if (client == null || panel == null) return;
        try {
            GuildMessageChannel channel = client.getChannelById(GuildMessageChannel.class, panel.channelId());
            if (channel != null) channel.deleteMessageById(panel.messageId()).queue(ok -> { }, error -> log.debug("Could not remove an event's board message: {}", error.getMessage()));
        } catch (RuntimeException e) {
            log.debug("Could not remove an event's board message", e);
        }
    }

    /** The tiles to draw for one player or team, for {@code /events board}. */
    public List<BingoBoardImage.TileView> personalTiles(Event event, long userId) {
        DataObject config = DataObject.fromJson(event.config());
        var player = repository.participant(event.id(), userId).orElse(null);
        String subject = player == null ? null : event.scoringMode() == EventRules.ScoringMode.TEAM ? (player.teamId() == null ? null : "t:" + player.teamId()) : "u:" + userId;
        Standing mine = subject == null ? null : service.standings(event.guildId(), event.id()).stream().filter(s -> s.subject().equals(subject)).findFirst().orElse(null);
        return BingoBoardView.personal(BingoBoardView.boardKeys(config), service.objectives(event.id()), mine, tiles::iconKeyFor);
    }
}
