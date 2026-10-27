package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelWeek;
import com.younglings.bot.tracking.CitadelWheelEntries;
import com.younglings.bot.tracking.CitadelWheelPoster;
import com.younglings.bot.tracking.CitadelPrizes;
import com.younglings.bot.tracking.CitadelWinners;
import com.younglings.bot.wheel.WheelWinnerRepository.Prize;
import com.younglings.bot.wheel.WheelWinnerRepository.PrizeStatus;
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
 *   {@code POST citadel/winners/date/post}: locking in a week's winners (one or several, each with the wheel GIF that goes with them), getting them
 *   back at any time with the announcement text ready to copy, and posting it.</li>
 *   <li>{@code GET citadel/prize/holders}, {@code POST citadel/prize} and {@code POST citadel/prize/coffer}: whether each winner has been given their prize,
 *   and recording it in the clan coffer.</li>
 *   <li>{@code GET|PUT citadel/prize/defaults}: the prize a winner usually gets (amount and note) and "auto record to coffer", which locks the
 *   "Mark prize as given" form to them.</li>
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
    private final CitadelPrizes prizes;
    private Executor worker = Executors.newFixedThreadPool(1, runnable -> {
        Thread thread = new Thread(runnable, "citadel-wheel-post");
        thread.setDaemon(true);
        return thread;
    });

    public CitadelAdminApi(CitadelWheelEntries entries, WheelSettingsRepository settings, CitadelWheelPoster poster, CitadelWinners winners, CitadelPrizes prizes) {
        this.prizes = prizes;
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
                if (winners.forWeek(guild.getIdLong(), end).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.that_week_hasn_t_been_locked"));
                return weekJson(guild, end, true);
            }
            case "prize" -> {
                if (parts.length == 3 && parts[2].equals("holders")) {
                    AdminRouteExtension.requireMethod(method, "GET");
                    return holders(guild);
                }
                if (parts.length == 2) {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return prize(guild, actor, body.get());
                }
                if (parts.length == 3 && parts[2].equals("coffer")) {
                    AdminRouteExtension.requireMethod(method, "POST");
                    return prizeInCoffer(guild, actor, body.get());
                }
                if (parts.length == 3 && parts[2].equals("defaults")) {
                    if (method.equals("PUT")) return savePrizeDefaults(guild, actor, body.get());
                    AdminRouteExtension.requireMethod(method, "GET");
                    return prizeDefaults(guild);
                }
                return null;
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
                winners.unlockWeek(guild.getIdLong(), end);
                log.info("Dashboard: {} unlocked the Citadel wheel winners for the week ending {}", actor.getId(), end);
                return winnerList(guild);
            }
            case "gif" -> {
                if (parts.length != 2) return null;
                AdminRouteExtension.requireMethod(method, "POST");
                DataObject b = body.get();
                LocalDate end = weekEndOf(b.getString("end", ""));
                winners.replaceGif(guild.getIdLong(), end, b.getInt("place", 1), decode(b.getString("gif", "")));
                return weekJson(guild, end, true);
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
                if (end.getDayOfWeek() != java.time.DayOfWeek.WEDNESDAY) throw new ApiError(400, Text.of(guild, "dashboard.a_citadel_week_ends_on_a"));
                window = CitadelWeek.windowEndingOn(end);
            } catch (DateTimeException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.give_the_date_the_week_ended"));
            }
            if (window[1].isAfter(now.plusDays(7))) throw new ApiError(400, Text.of(guild, "dashboard.that_week_hasn_t_happened_yet"));
        }
        var eligible = entries.forWeek(guild, window[0], window[1]);
        Map<LocalDate, List<Winner>> locked = new HashMap<>();
        winners.list(guild.getIdLong(), 300).forEach(w -> locked.computeIfAbsent(w.weekEnd(), k -> new ArrayList<>()).add(w));
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
            DataArray names2 = DataArray.empty();
            locked.getOrDefault(end, List.of()).forEach(w -> names2.add(w.winner()));
            weeks.add(DataObject.empty().put("end", end.toString()).put("start", end.minusDays(7).toString()).put("winners", names2));
        }
        LocalDate thisEnd = window[1].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        DataObject lockedWeek = locked.containsKey(thisEnd) ? weekJson(guild, thisEnd, true) : null;
        return DataObject.empty().put("locked", lockedWeek).put("end", window[1].toLocalDate().toString()).put("start", window[0].toLocalDate().toString())
                .put("from", window[0].toString()).put("to", window[1].toString()).put("cappers", names).put("leftOut", leftOut).put("merged", merged).put("weeks", weeks);
    }

    // ---------- announcing a winner ----------

    DataObject wheelSettings(Guild guild) {
        WheelSettingsRepository.Settings s = settings.get(guild.getIdLong());
        return DataObject.empty().put("autoPost", s.autoPost()).put("channelId", s.channelId() == null ? null : Long.toString(s.channelId()))
                .put("video", s.video()).put("message", s.message()).put("defaultMessage", WheelMessage.DEFAULT).put("maxLength", WheelMessage.MAX_LENGTH);
    }

    DataObject saveWheelSettings(Guild guild, Member actor, DataObject body) {
        WheelSettingsRepository.Settings current = settings.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean autoPost = body.hasKey("autoPost") ? body.getBoolean("autoPost", false) : current.autoPost();
        boolean video = body.hasKey("video") ? body.getBoolean("video", false) : current.video();
        String message = body.hasKey("message") ? body.getString("message", "").strip() : current.message();
        problems.addAll(WheelMessage.problems(message));

        Long channelId = current.channelId();
        if (body.hasKey("channelId")) {
            String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
            if (raw.isEmpty()) {
                channelId = null;
            } else if (!raw.matches("\\d{1,20}") || !fitsALong(raw)) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel, send messages and attach files there.");
            }
        }
        if (autoPost && channelId == null) problems.add("Choose the channel before switching auto-posting on.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_wheel_settings_can_t_be"), problems);

        settings.save(guild.getIdLong(), new WheelSettingsRepository.Settings(autoPost, channelId, message, video));
        log.info("Dashboard: {} saved the Citadel wheel settings (auto-post {}, video {})", actor.getId(), autoPost ? "on" : "off", video ? "on" : "off");
        return wheelSettings(guild);
    }

    /**
     * Announces the winner the dashboard's wheel picked. The GIF takes a while to draw, so it is made and posted in the background and this answers at
     * once; a problem with the channel is reported straight away.
     */
    DataObject post(Guild guild, Member actor, DataObject body) {
        String winner = body.getString("winner", "").strip();
        if (winner.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.say_who_won"));
        if (winner.length() > WheelEntries.MAX_NAME) throw new ApiError(400, Text.of(guild, "dashboard.that_name_is_too_long"));
        List<String> drawn = new ArrayList<>();
        if (body.hasKey("entries") && !body.isNull("entries")) {
            DataArray array = body.getArray("entries");
            if (array.length() > WheelEntries.MAX_ENTRIES) throw new ApiError(400, Text.of(guild, "dashboard.the_wheel_holds_up_to_entries", WheelEntries.MAX_ENTRIES));
            for (int i = 0; i < array.length(); i++) drawn.add(array.getString(i));
        }
        if (drawn.stream().noneMatch(e -> e.equalsIgnoreCase(winner))) throw new ApiError(400, Text.of(guild, "dashboard.the_winner_has_to_be_one"));
        OffsetDateTime[] window;
        try {
            LocalDate end = LocalDate.parse(body.getString("end", ""));
            window = CitadelWeek.windowEndingOn(end);
        } catch (DateTimeException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.say_which_week_this_was"));
        }
        GuildMessageChannel channel;
        try {
            channel = poster.channelFor(guild);
        } catch (CitadelWheelPoster.CannotPost e) {
            throw new ApiError(409, e.getMessage());
        }
        LocalDate weekEnd = window[1].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        if (!winners.forWeek(guild.getIdLong(), weekEnd).isEmpty()) throw new ApiError(409, Text.of(guild, "dashboard.this_week_is_locked_post_it"));
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

    // ---------- locked winners ----------

    private static LocalDate weekEndOf(String text) {
        try {
            return LocalDate.parse(text == null ? "" : text.strip());
        } catch (DateTimeException e) {
            throw new ApiError(400, Text.en("dashboard.say_which_week_by_the_date"));
        }
    }

    private static byte[] decode(String base64) {
        try {
            return Base64.getDecoder().decode(base64 == null ? "" : base64.strip());
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.en("dashboard.the_picture_couldn_t_be_read"));
        }
    }

    private static String nameOf(Guild guild, Long userId) {
        if (userId == null) return null;
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }

    private static String idText(Long id) {
        return id == null ? null : Long.toString(id);
    }

    /** One locked winner with their prize. */
    private DataObject winnerJson(Guild guild, Winner w, Long linked) {
        DataArray entryNames = DataArray.empty();
        w.entries().forEach(entryNames::add);
        Prize prize = w.prize();
        DataObject prizeJson = DataObject.empty().put("status", prize.status().name()).put("amount", prize.amount()).put("paidBy", idText(prize.paidBy()))
                .put("paidByName", nameOf(guild, prize.paidBy())).put("recipient", idText(prize.recipient())).put("recipientName", nameOf(guild, prize.recipient()))
                .put("givenBy", idText(prize.givenBy())).put("givenByName", nameOf(guild, prize.givenBy())).put("givenAt", prize.givenAt() == null ? null : prize.givenAt().toString())
                .put("note", prize.note()).put("inCoffer", prize.inCoffer());
        return DataObject.empty().put("end", w.weekEnd().toString()).put("start", w.weekEnd().minusDays(7).toString()).put("place", w.place()).put("winner", w.winner())
                .put("method", w.method().name()).put("lockedAt", w.lockedAt().toString()).put("lockedBy", idText(w.lockedBy())).put("lockedByName", nameOf(guild, w.lockedBy()))
                .put("entries", entryNames).put("entryCount", w.entries().size()).put("hasGif", w.hasGif()).put("gifSource", w.gifSource() == null ? null : w.gifSource().name())
                .put("gifToken", w.gifToken()).put("prize", prizeJson).put("linkedUser", idText(linked)).put("linkedUserName", nameOf(guild, linked));
    }

    /** One locked week: its winners in order of place, and (when asked) the announcement text ready to copy. */
    private DataObject weekJson(Guild guild, LocalDate end, boolean withMessage) {
        List<Winner> rows = winners.forWeek(guild.getIdLong(), end);
        DataArray array = DataArray.empty();
        var linked = prizes.linkedUsers(guild, rows.stream().map(Winner::winner).toList());
        for (Winner w : rows) array.add(winnerJson(guild, w, linked.get(w.winner())));
        DataObject json = DataObject.empty().put("end", end.toString()).put("start", end.minusDays(7).toString()).put("winners", array);
        if (withMessage && !rows.isEmpty()) {
            json.put("message", poster.messageFor(guild, CitadelWeek.windowEndingOn(end), rows.stream().map(Winner::winner).toList(), rows.get(0).entries().size()));
        }
        return json;
    }

    private DataObject winnerList(Guild guild) {
        Map<LocalDate, List<Winner>> byWeek = new java.util.LinkedHashMap<>();
        var all = winners.list(guild.getIdLong(), 300);
        all.forEach(w -> byWeek.computeIfAbsent(w.weekEnd(), k -> new ArrayList<>()).add(w));
        var linked = prizes.linkedUsers(guild, all.stream().map(Winner::winner).toList());
        DataArray weeks = DataArray.empty();
        for (var entry : byWeek.entrySet()) {
            DataArray array = DataArray.empty();
            for (Winner w : entry.getValue()) array.add(winnerJson(guild, w, linked.get(w.winner())));
            weeks.add(DataObject.empty().put("end", entry.getKey().toString()).put("start", entry.getKey().minusDays(7).toString()).put("winners", array));
        }
        return DataObject.empty().put("weeks", weeks);
    }

    /** {@code {end, winner, entries[], manual, gif (base64, optional)}}: locks the next place of the week. Without a picture the bot draws its own. */
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
        log.info("Dashboard: {} locked {} as place {} of the Citadel wheel for the week ending {} ({}, {})", actor.getId(), locked.winner(), locked.place(), end, method, gif == null ? "bot picture" : "website picture");
        return weekJson(guild, end, true);
    }

    private DataObject postLocked(Guild guild, Member actor, LocalDate end) {
        if (winners.forWeek(guild.getIdLong(), end).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.that_week_hasn_t_been_locked"));
        GuildMessageChannel channel;
        try {
            channel = poster.channelFor(guild);
        } catch (CitadelWheelPoster.CannotPost e) {
            throw new ApiError(409, e.getMessage());
        }
        worker.execute(() -> {
            try {
                poster.postLocked(guild, end);
                log.info("Dashboard: {} announced the locked Citadel wheel winners for the week ending {}", actor.getId(), end);
            } catch (Exception e) {
                log.error("Could not announce the locked Citadel wheel winners", e);
            }
        });
        return DataObject.empty().put("queued", true).put("channel", channel.getName());
    }

    // ---------- prizes ----------

    /** Who holds gp in the clan coffer (and how much), for choosing who paid a prize. Names of people not in the cache are looked up. */
    private DataObject holders(Guild guild) {
        var holders = prizes.holders(guild.getIdLong());
        Map<Long, String> names = memberNames(guild, holders.stream().map(com.younglings.bot.commands.coffer.CofferHolder::discordUserId).toList());
        DataArray array = DataArray.empty();
        holders.forEach(h -> array.add(DataObject.empty().put("userId", Long.toString(h.discordUserId()))
                .put("name", names.get(h.discordUserId())).put("balance", h.amount())));
        return DataObject.empty().put("holders", array);
    }

    /**
     * Display names for these members: the cache first (the bot only caches who is online), then one lookup for the rest, waited on for at most
     * {@link com.younglings.bot.discord.DiscordLookups#BUDGET}. Missing ones (and ones Discord didn't return in time) are left out.
     */
    static Map<Long, String> memberNames(Guild guild, java.util.Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        List<Long> missing = new ArrayList<>();
        for (long id : ids) {
            String name = nameOf(guild, id);
            if (name != null) names.put(id, name);
            else if (!missing.contains(id)) missing.add(id);
        }
        if (!missing.isEmpty()) {
            net.dv8tion.jda.api.utils.concurrent.Task<List<Member>> task = null;
            try {
                task = guild.retrieveMembersByIds(missing);
                java.util.concurrent.CompletableFuture<List<Member>> found = new java.util.concurrent.CompletableFuture<>();
                task.onSuccess(found::complete);
                task.onError(found::completeExceptionally);
                found.get(com.younglings.bot.discord.DiscordLookups.BUDGET.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .forEach(m -> names.put(m.getIdLong(), m.getEffectiveName()));
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel();
                log.debug("Discord didn't return member names in time; showing the cached ones");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (task != null) task.cancel();
            } catch (Exception e) {
                log.debug("Couldn't look up member names: {}", e.getMessage());
            }
        }
        return names;
    }

    private DataObject prizeDefaults(Guild guild) {
        WheelSettingsRepository.PrizeDefaults d = settings.getPrizeDefaults(guild.getIdLong());
        return DataObject.empty().put("autoRecord", d.autoRecord()).put("amount", d.amount()).put("note", d.note() == null ? "" : d.note());
    }

    /** {@code {autoRecord?, amount?, note?}}: changes only what was sent. An empty amount clears it; the amount takes the same shorthand as /coffer. */
    private DataObject savePrizeDefaults(Guild guild, Member actor, DataObject body) {
        WheelSettingsRepository.PrizeDefaults current = settings.getPrizeDefaults(guild.getIdLong());
        boolean autoRecord = body.hasKey("autoRecord") ? body.getBoolean("autoRecord", false) : current.autoRecord();
        Long amount = current.amount();
        if (body.hasKey("amount")) {
            String raw = body.isNull("amount") ? "" : body.getString("amount", "").strip();
            if (raw.isEmpty()) {
                amount = null;
            } else {
                try {
                    amount = com.younglings.bot.commands.coffer.GpAmountParser.parse(raw);
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, e.getMessage());
                }
            }
        }
        String note = current.note();
        if (body.hasKey("note")) {
            String raw = body.isNull("note") ? "" : body.getString("note", "").strip();
            if (raw.length() > CitadelPrizes.MAX_NOTE) throw new ApiError(400, "Keep the note to " + CitadelPrizes.MAX_NOTE + " characters or fewer.");
            note = raw.isEmpty() ? null : raw;
        }
        settings.savePrizeDefaults(guild.getIdLong(), new WheelSettingsRepository.PrizeDefaults(autoRecord, amount, note));
        log.info("Dashboard: {} saved the Citadel wheel prize defaults (auto record {}, amount {})", actor.getId(), autoRecord ? "on" : "off", amount);
        return prizeDefaults(guild);
    }

    private static int placeOf(DataObject body) {
        int place = body.getInt("place", 1);
        if (place < 1 || place > CitadelWinners.MAX_WINNERS) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_place_on"));
        return place;
    }

    private static Long optionalId(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        // digits that overflow a long are still the asker's mistake (a 400), never a 500
        if (!raw.matches("\\d{1,20}") || !fitsALong(raw)) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_discord_user"));
        return Long.parseLong(raw);
    }

    /** Whether these digits are a number a long holds (an id typed with 20 digits can overflow it). */
    static boolean fitsALong(String digits) {
        try {
            Long.parseLong(digits);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code {end, place, action: "given" | "pending", amount?, paidBy?, note?}}: marks a prize handed over (without the coffer) or puts it back to not given. */
    private DataObject prize(Guild guild, Member actor, DataObject body) {
        LocalDate end = weekEndOf(body.getString("end", ""));
        int place = placeOf(body);
        String action = body.getString("action", "");
        if (action.equals("given")) {
            Long amount = null;
            if (body.hasKey("amount") && !body.isNull("amount") && !body.getString("amount", "").isBlank()) {
                try {
                    amount = com.younglings.bot.commands.coffer.GpAmountParser.parse(body.getString("amount"));
                } catch (IllegalArgumentException e) {
                    throw new ApiError(400, e.getMessage());
                }
            }
            Long paidBy = optionalId(body, "paidBy");
            if (paidBy == null) prizes.markGiven(guild.getIdLong(), end, place, amount, body.getString("note", ""), actor.getIdLong());
            else prizes.markGiven(guild.getIdLong(), end, place, amount, paidBy, body.getString("note", ""), actor.getIdLong());
        } else if (action.equals("pending")) {
            prizes.markPending(guild.getIdLong(), end, place);
        } else {
            throw new ApiError(400, Text.of(guild, "dashboard.say_whether_the_prize_was_given"));
        }
        return weekJson(guild, end, false);
    }

    /** {@code {end, place, amount, paidBy, recipient?, note?}}: records the prize in the clan coffer as a giveaway from the person who paid it. */
    private DataObject prizeInCoffer(Guild guild, Member actor, DataObject body) {
        LocalDate end = weekEndOf(body.getString("end", ""));
        int place = placeOf(body);
        Long paidBy = optionalId(body, "paidBy");
        if (paidBy == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_who_paid_the_prize"));
        prizes.recordInCoffer(guild, end, place, body.getString("amount", ""), paidBy, optionalId(body, "recipient"), body.getString("note", ""), actor.getIdLong());
        log.info("Dashboard: {} recorded the Citadel wheel prize for place {} of the week ending {} in the coffer", actor.getId(), place, end);
        return weekJson(guild, end, false);
    }
}
