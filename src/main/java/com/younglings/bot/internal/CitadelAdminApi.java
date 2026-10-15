package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWeek;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
import com.younglings.bot.tracking.CitadelWinners;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>{@code GET citadel/winners[/date]}, {@code POST citadel/lock}, {@code POST citadel/unlock}, {@code POST citadel/gif} and
 *   {@code POST citadel/winners/date/post}: locking in a week's winner with the wheel GIF that goes with them, getting it back at any time with the
 *   announcement text ready to copy, and posting it.</li>
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
    private final CitadelWinners winners;
    private Executor worker = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "citadel-wheel-post");
        thread.setDaemon(true);
        return thread;
    });

    public CitadelAdminApi(CitadelWheelEntries entries, WheelSettingsRepository settings, CitadelWheelPoster poster, CitadelWinners winners) {
        this.winners = winners;
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
        if (parts.length < 2 || parts.length > 4 || !parts[0].equals("citadel")) return null;
        try {
            return routeCitadel(guild, actor, method, parts, body);
        } catch (CitadelWinners.CannotLock e) {
            throw new ApiError(e.status(), e.getMessage());
        }
    }

    private DataObject routeCitadel(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 4 && !(parts[1].equals("winners") && parts[3].equals("post"))) return null;
        switch (parts[1]) {
            case "winners" -> {
                if (parts.length == 2) {
                    AdminRouteExtension.requireMethod(method, "GET");
                    return winnerList(guild);
                }
                LocalDate end = weekEndOf(parts[2]);
                if (parts.length == 4) {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return postLocked(guild, actor, end);
                }
                AdminRouteExtension.requireMethod(method, "GET");
                return winnerJson(guild, winners.get(guild.getIdLong(), end).orElseThrow(() -> new ApiError(404, "That week hasn't been locked.")), true);
            }
            case "lock" -> {
                if (parts.length != 2) return null;
                AdminRouteExtension.requireMethod(method, "POST");
                return lock(guild, actor, body.get());
            }
            case "unlock" -> {
                if (parts.length != 2) return null;
                AdminRouteExtension.requireMethod(method, "POST");
                LocalDate end = weekEndOf(body.get().getString("end", ""));
                winners.unlock(guild.getIdLong(), end);
                log.info("Dashboard: {} unlocked the Citadel wheel winner for the week ending {}", actor.getId(), end);
                return winnerList(guild);
            }
            case "gif" -> {
                if (parts.length != 2) return null;
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject b = body.get();
                LocalDate end = weekEndOf(b.getString("end", ""));
                winners.replaceGif(guild.getIdLong(), end, decode(b.getString("gif", "")));
                return winnerJson(guild, winners.get(guild.getIdLong(), end).orElseThrow(() -> new ApiError(404, "That week hasn't been locked.")), true);
            }
            default -> { }
        }
        if (parts.length > 3) return null;
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
        Map<LocalDate, Winner> locked = new HashMap<>();
        winners.list(guild.getIdLong(), 100).forEach(w -> locked.put(w.weekEnd(), w));
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
            Winner w = locked.get(end);
            weeks.add(DataObject.empty().put("end", end.toString()).put("start", end.minusDays(7).toString()).put("winner", w == null ? null : w.winner()));
        }
        Winner thisWeek = locked.get(window[1].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
        return DataObject.empty().put("winner", thisWeek == null ? null : winnerJson(guild, thisWeek, true)).put("end", window[1].toLocalDate().toString()).put("start", window[0].toLocalDate().toString())
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
        LocalDate weekEnd = window[1].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        Winner lockedWinner = winners.get(guild.getIdLong(), weekEnd).orElse(null);
        if (lockedWinner != null && !lockedWinner.winner().equalsIgnoreCase(winner)) throw new ApiError(409, "This week is locked with " + lockedWinner.winner() + " as the winner.");
        worker.execute(() -> {
            try {
                if (lockedWinner != null && lockedWinner.hasGif()) {
                    poster.postLocked(guild, weekEnd);
                    log.info("Dashboard: {} announced the locked Citadel wheel winner {}", actor.getId(), winner);
                    return;
                }
                poster.postFromDashboard(guild, window, winner, drawn);
                log.info("Dashboard: {} announced the Citadel wheel winner {}", actor.getId(), winner);
            } catch (Exception e) {
                log.error("Could not announce the Citadel wheel winner from the dashboard", e);
            }
        });
        return DataObject.empty().put("queued", true).put("channel", channel.getName());
    }

    // ---------- locked winners ----------

    private static LocalDate weekEndOf(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text.strip());
        } catch (DateTimeException e) {
            throw new ApiError(400, "Say which week, by the date it ended, like 2026-10-14.");
        }
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "The picture couldn't be read.");
        }
    }

    /** One locked week. {@code withMessage} adds the announcement text, ready to copy (it looks the winner up, so a list leaves it out). */
    private DataObject winnerJson(Guild guild, Winner w, boolean withMessage) {
        OffsetDateTime[] window = CitadelWeek.windowEndingOn(w.weekEnd());
        DataArray entryNames = DataArray.empty();
        w.entries().forEach(entryNames::add);
        Member by = w.lockedBy() == null ? null : guild.getMemberById(w.lockedBy());
        DataObject json = DataObject.empty().put("end", w.weekEnd().toString()).put("start", w.weekEnd().minusDays(7).toString()).put("winner", w.winner())
                .put("method", w.method().name()).put("lockedAt", w.lockedAt().toString()).put("lockedBy", w.lockedBy() == null ? null : Long.toString(w.lockedBy()))
                .put("lockedByName", by == null ? null : by.getEffectiveName()).put("entries", entryNames).put("entryCount", w.entries().size())
                .put("hasGif", w.hasGif()).put("gifSource", w.gifSource() == null ? null : w.gifSource().name()).put("gifToken", w.gifToken());
        if (withMessage) json.put("message", poster.messageFor(guild, window, w.winner(), w.entries().size()));
        return json;
    }

    private DataObject winnerList(Guild guild) {
        DataArray list = DataArray.empty();
        winners.list(guild.getIdLong(), 100).forEach(w -> list.add(winnerJson(guild, w, false)));
        return DataObject.empty().put("winners", list);
    }

    /** {@code {end, winner, entries[], manual, gif (base64, optional)}}: locks the week's winner. Without a picture the bot draws its own. */
    private DataObject lock(Guild guild, Member actor, DataObject body) {
        LocalDate end = weekEndOf(body.getString("end", ""));
        String winner = body.getString("winner", "");
        List<String> slices = new ArrayList<>();
        if (body.hasKey("entries") && !body.isNull("entries")) {
            DataArray array = body.getArray("entries");
            for (int i = 0; i < array.length(); i++) slices.add(array.getString(i));
        }
        byte[] gif = body.hasKey("gif") && !body.isNull("gif") && !body.getString("gif", "").isBlank() ? decode(body.getString("gif")) : null;
        Method method = body.getBoolean("manual", false) ? Method.MANUAL : Method.SPUN;
        Winner locked = winners.lock(guild.getIdLong(), end, winner, slices, method, actor.getIdLong(), gif, GifSource.WEBSITE);
        log.info("Dashboard: {} locked {} as the Citadel wheel winner for the week ending {} ({}, {})", actor.getId(), locked.winner(), end, method, gif == null ? "bot picture" : "website picture");
        return winnerJson(guild, locked, true);
    }

    private DataObject postLocked(Guild guild, Member actor, LocalDate end) {
        winners.get(guild.getIdLong(), end).orElseThrow(() -> new ApiError(404, "That week hasn't been locked."));
        GuildMessageChannel channel;
        try {
            channel = poster.channelFor(guild);
        } catch (CitadelWheelPoster.CannotPost e) {
            throw new ApiError(409, e.getMessage());
        }
        worker.execute(() -> {
            try {
                poster.postLocked(guild, end);
                log.info("Dashboard: {} announced the locked Citadel wheel winner for the week ending {}", actor.getId(), end);
            } catch (Exception e) {
                log.error("Could not announce the locked Citadel wheel winner", e);
            }
        });
        return DataObject.empty().put("queued", true).put("channel", channel.getName());
    }
}
