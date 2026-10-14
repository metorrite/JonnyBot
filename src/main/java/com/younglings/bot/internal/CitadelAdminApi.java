package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWeek;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelMessage;
import com.younglings.bot.wheel.WheelSettingsRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The dashboard's Citadel wheel page.
 * <ul>
 *   <li>{@code GET citadel/cappers[/date]}: who is on the wheel for a week (one entry per person who capped, staff left off; see
 *   {@link CitadelWheelEntries}), the week that just finished by default, plus the last few weeks to pick from.</li>
 *   <li>{@code GET|PUT citadel/wheel}: the settings for announcing a winner: auto-post on or off, the channel and the message.</li>
 *   <li>{@code POST citadel/post}: announce a winner the dashboard's wheel picked, with a GIF that stops on them.</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class CitadelAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(CitadelAdminApi.class);
    private static final int WEEKS_LISTED = 8;

    private final CitadelWheelEntries entries;
    private final WheelSettingsRepository settings;
    private final CitadelWheelPoster poster;
    private Executor worker = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "citadel-wheel-post");
        thread.setDaemon(true);
        return thread;
    });

    public CitadelAdminApi(CitadelWheelEntries entries, WheelSettingsRepository settings, CitadelWheelPoster poster) {
        this.entries = entries;
        this.settings = settings;
        this.poster = poster;
    }

    /** For tests: run the posting on the calling thread. */
    void useExecutor(Executor executor) {
        this.worker = executor;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || parts.length > 3 || !parts[0].equals("citadel")) return null;
        switch (parts[1]) {
            case "cappers" -> {
                AdminRouteExtension.requireMethod(method, "GET");
                return cappers(guild, parts.length == 3 ? parts[2] : null, OffsetDateTime.now(ZoneOffset.UTC));
            }
            case "wheel" -> {
                if (parts.length != 2) return null;
                if (method.equals("GET")) return wheelSettings(guild);
                AdminRouteExtension.requireMethod(method, "PUT");
                return saveWheelSettings(guild, actor, body.get());
            }
            case "post" -> {
                if (parts.length != 2) return null;
                AdminRouteExtension.requireMethod(method, "POST");
                return post(guild, actor, body.get());
            }
            default -> {
                return null;
            }
        }
    }

    DataObject cappers(Guild guild, String endDate, OffsetDateTime now) {
        OffsetDateTime[] window;
        if (endDate == null) {
            window = CitadelWeek.lastCompleted(now);
        } else {
            try {
                LocalDate end = LocalDate.parse(endDate);
                if (end.getDayOfWeek() != java.time.DayOfWeek.WEDNESDAY) throw new ApiError(400, "A Citadel week ends on a Wednesday.");
                window = CitadelWeek.windowEndingOn(end);
            } catch (DateTimeException e) {
                throw new ApiError(400, "Give the date the week ended, like 2026-10-14.");
            }
            if (window[1].isAfter(now.plusDays(7))) throw new ApiError(400, "That week hasn't happened yet.");
        }
        var eligible = entries.forWeek(guild, window[0], window[1]);
        DataArray names = DataArray.empty();
        eligible.names().forEach(names::add);
        DataArray leftOut = DataArray.empty();
        eligible.leftOut().forEach(l -> leftOut.add(DataObject.empty().put("name", l.name()).put("reason", l.reason())));
        DataArray merged = DataArray.empty();
        eligible.merged().forEach(m -> {
            DataArray accounts = DataArray.empty();
            m.accounts().forEach(accounts::add);
            merged.add(DataObject.empty().put("name", m.name()).put("accounts", accounts));
        });

        DataArray weeks = DataArray.empty();
        OffsetDateTime[] latest = CitadelWeek.lastCompleted(now);
        for (int i = 0; i < WEEKS_LISTED; i++) {
            LocalDate end = latest[1].toLocalDate().minusWeeks(i);
            weeks.add(DataObject.empty().put("end", end.toString()).put("start", end.minusDays(7).toString()));
        }
        return DataObject.empty().put("end", window[1].toLocalDate().toString()).put("start", window[0].toLocalDate().toString())
                .put("from", window[0].toString()).put("to", window[1].toString()).put("cappers", names).put("leftOut", leftOut).put("merged", merged).put("weeks", weeks);
    }

    // ---------- announcing a winner ----------

    DataObject wheelSettings(Guild guild) {
        WheelSettingsRepository.Settings s = settings.get(guild.getIdLong());
        return DataObject.empty().put("autoPost", s.autoPost()).put("channelId", s.channelId() == null ? null : Long.toString(s.channelId()))
                .put("message", s.message()).put("defaultMessage", WheelMessage.DEFAULT).put("maxLength", WheelMessage.MAX_LENGTH);
    }

    DataObject saveWheelSettings(Guild guild, Member actor, DataObject body) {
        WheelSettingsRepository.Settings current = settings.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean autoPost = body.hasKey("autoPost") ? body.getBoolean("autoPost", false) : current.autoPost();
        String message = body.hasKey("message") ? body.getString("message", "").strip() : current.message();
        problems.addAll(WheelMessage.problems(message));

        Long channelId = current.channelId();
        if (body.hasKey("channelId")) {
            String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
            if (raw.isEmpty()) {
                channelId = null;
            } else if (!raw.matches("\\d{1,20}")) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel, send messages and attach files there.");
            }
        }
        if (autoPost && channelId == null) problems.add("Choose the channel before switching auto-posting on.");
        if (!problems.isEmpty()) throw new ApiError(400, "Those wheel settings can't be saved yet.", problems);

        settings.save(guild.getIdLong(), new WheelSettingsRepository.Settings(autoPost, channelId, message));
        log.info("Dashboard: {} saved the Citadel wheel settings (auto-post {})", actor.getId(), autoPost ? "on" : "off");
        return wheelSettings(guild);
    }

    /**
     * Announces the winner the dashboard's wheel picked. The GIF takes a while to draw, so it is made and posted in the background and this answers at
     * once; a problem with the channel is reported straight away.
     */
    DataObject post(Guild guild, Member actor, DataObject body) {
        String winner = body.getString("winner", "").strip();
        if (winner.isEmpty()) throw new ApiError(400, "Say who won.");
        if (winner.length() > WheelEntries.MAX_NAME) throw new ApiError(400, "That name is too long.");
        List<String> drawn = new ArrayList<>();
        if (body.hasKey("entries") && !body.isNull("entries")) {
            DataArray array = body.getArray("entries");
            if (array.length() > WheelEntries.MAX_ENTRIES) throw new ApiError(400, "The wheel holds up to " + WheelEntries.MAX_ENTRIES + " entries.");
            for (int i = 0; i < array.length(); i++) drawn.add(array.getString(i));
        }
        if (drawn.stream().noneMatch(e -> e.equalsIgnoreCase(winner))) throw new ApiError(400, "The winner has to be one of the entries on the wheel.");
        OffsetDateTime[] window;
        try {
            LocalDate end = LocalDate.parse(body.getString("end", ""));
            window = CitadelWeek.windowEndingOn(end);
        } catch (DateTimeException e) {
            throw new ApiError(400, "Say which week this was.");
        }
        GuildMessageChannel channel;
        try {
            channel = poster.channelFor(guild);
        } catch (CitadelWheelPoster.CannotPost e) {
            throw new ApiError(409, e.getMessage());
        }
        worker.execute(() -> {
            try {
                poster.postFromDashboard(guild, window, winner, drawn);
                log.info("Dashboard: {} announced the Citadel wheel winner {}", actor.getId(), winner);
            } catch (Exception e) {
                log.error("Could not announce the Citadel wheel winner from the dashboard", e);
            }
        });
        return DataObject.empty().put("queued", true).put("channel", channel.getName());
    }
}
