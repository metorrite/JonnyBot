package com.younglings.bot.tracking;

import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelSpin;
import com.younglings.bot.wheel.WheelWinnerRepository;
import com.younglings.bot.wheel.WheelWinnerRepository.GifSource;
import com.younglings.bot.wheel.WheelWinnerRepository.Method;
import com.younglings.bot.wheel.WheelWinnerRepository.PrizeStatus;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Locking in the Citadel wheel's winners for a week, so they can't change and can be announced again at any time. A week can have several winners
 * (first place, second place, ...), locked one after the other. Every locked winner has a wheel GIF that lands on them: one the website drew and sent
 * along, or, when none came, the bot draws its own in the background (the bot works without the website). A place is never replaced; unlocking the
 * week is a separate, deliberate step, and not possible once a prize has been recorded as given.
 */
@BService
public class CitadelWinners {
    private static final Logger log = LoggerFactory.getLogger(CitadelWinners.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Discord's upload limit is about 10 MB; a picture bigger than this could never be attached. */
    public static final int MAX_GIF_BYTES = 9 * 1024 * 1024;
    /** The most winners one week can have. */
    public static final int MAX_WINNERS = 10;

    /** Why a week can't be locked (or unlocked), in words. */
    public static final class CannotLock extends RuntimeException {
        private final int status;

        public CannotLock(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final WheelWinnerRepository repository;
    private Executor worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "citadel-winner-gif");
        thread.setDaemon(true);
        return thread;
    });

    public CitadelWinners(WheelWinnerRepository repository) {
        this.repository = repository;
    }

    /** For tests: draw the bot's own GIF on the calling thread. */
    void useExecutor(Executor executor) {
        this.worker = executor;
    }

    /** Checks the Wednesday a week ended on: it has to be a real Citadel week end that is not in the future. */
    public static LocalDate checkWeek(LocalDate end, OffsetDateTime now) {
        if (end.getDayOfWeek() != DayOfWeek.WEDNESDAY) throw new CannotLock(400, "A Citadel week ends on a Wednesday.");
        if (end.isAfter(now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate().plusDays(7))) throw new CannotLock(400, "That week hasn't happened yet.");
        return end;
    }

    /**
     * Locks {@code winner} as the next place of the week (first place for the first one). {@code entries} is the wheel they were picked from; a winner who
     * isn't on it (a past week typed in by hand) is added. {@code gif} is a picture that lands on the winner and {@code gifSource} where it came from, or
     * null to have the bot draw one. The same person can't win twice in one week.
     */
    public Winner lock(long guildId, LocalDate weekEnd, String winner, List<String> entries, Method method, Long lockedBy, byte[] gif, GifSource gifSource) {
        checkWeek(weekEnd, OffsetDateTime.now(ZoneOffset.UTC));
        String name = winner == null ? "" : winner.strip();
        if (name.isEmpty()) throw new CannotLock(400, "Say who won.");
        if (name.length() > WheelEntries.MAX_NAME) throw new CannotLock(400, "That name is too long.");
        List<String> slices = new ArrayList<>();
        if (entries != null) {
            if (entries.size() > WheelEntries.MAX_ENTRIES) throw new CannotLock(400, "The wheel holds up to " + WheelEntries.MAX_ENTRIES + " entries.");
            for (String e : entries) {
                String t = e == null ? "" : e.strip();
                if (!t.isEmpty() && t.length() <= WheelEntries.MAX_NAME) slices.add(t);
            }
        }
        String exact = slices.stream().filter(e -> e.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (exact == null) {
            if (slices.size() >= WheelEntries.MAX_ENTRIES) throw new CannotLock(400, "The wheel holds up to " + WheelEntries.MAX_ENTRIES + " entries.");
            slices.add(name);
            exact = name;
        }
        if (gif != null && (gif.length == 0 || gif.length > MAX_GIF_BYTES || !isGif(gif))) throw new CannotLock(400, "That picture isn't a GIF Discord can take (up to 9 MB).");

        List<Winner> existing = repository.forWeek(guildId, weekEnd);
        if (existing.size() >= MAX_WINNERS) throw new CannotLock(409, "A week can have up to " + MAX_WINNERS + " winners.");
        for (Winner w : existing) {
            if (w.winner().equalsIgnoreCase(exact)) throw new CannotLock(409, exact + " is already a winner this week (place " + w.place() + ").");
        }
        int place = repository.nextPlace(guildId, weekEnd);
        Winner row = new Winner(guildId, weekEnd, place, exact, slices, method, lockedBy, Instant.now(), gif != null, gif == null ? null : gifSource, newToken(), WheelWinnerRepository.Prize.pending());
        if (!repository.insert(row, gif)) throw new CannotLock(409, "Someone else locked a winner for this week at the same moment. Reload and check the week.");
        log.info("Locked the Citadel wheel winner (place {}) of guild {} for the week ending {} ({}).", place, guildId, weekEnd, method);
        if (gif == null) drawBotGif(guildId, weekEnd, place);
        return repository.get(guildId, weekEnd, place).orElse(row);
    }

    /**
     * Takes the lock off a whole week (every winner and picture is removed). Refused when a prize has been recorded as given, because that money has
     * already moved and the record of it must stay.
     */
    public void unlockWeek(long guildId, LocalDate weekEnd) {
        List<Winner> rows = repository.forWeek(guildId, weekEnd);
        if (rows.isEmpty()) throw new CannotLock(404, "That week isn't locked.");
        for (Winner w : rows) {
            if (w.prize().status() == PrizeStatus.GIVEN) throw new CannotLock(409, "A prize for " + w.winner() + " has been recorded as given, so this week can't be unlocked.");
        }
        repository.deleteWeek(guildId, weekEnd);
        log.info("Unlocked the Citadel wheel winners of guild {} for the week ending {}.", guildId, weekEnd);
    }

    public Optional<Winner> get(long guildId, LocalDate weekEnd, int place) {
        return repository.get(guildId, weekEnd, place);
    }

    /** A week's winners in order of place. */
    public List<Winner> forWeek(long guildId, LocalDate weekEnd) {
        return repository.forWeek(guildId, weekEnd);
    }

    /** Locked winners, newest week first. */
    public List<Winner> list(long guildId, int limit) {
        return repository.list(guildId, limit);
    }

    public Optional<byte[]> gif(long guildId, LocalDate weekEnd, int place) {
        return repository.gif(guildId, weekEnd, place);
    }

    public Optional<byte[]> gifByToken(String token) {
        return token == null || !token.matches("[A-Za-z0-9_-]{20,64}") ? Optional.empty() : repository.gifByToken(token);
    }

    /** Replaces a locked winner's picture, for example with a better one the website drew. */
    public void replaceGif(long guildId, LocalDate weekEnd, int place, byte[] gif) {
        if (gif == null || gif.length == 0 || gif.length > MAX_GIF_BYTES || !isGif(gif)) throw new CannotLock(400, "That picture isn't a GIF Discord can take (up to 9 MB).");
        if (!repository.setGif(guildId, weekEnd, place, gif, GifSource.WEBSITE, false)) throw new CannotLock(404, "That winner isn't locked.");
    }

    /** Draws the bot's own wheel GIF for a winner that has none, in the background, without ever replacing one that arrives first. */
    void drawBotGif(long guildId, LocalDate weekEnd, int place) {
        worker.execute(() -> {
            try {
                Winner row = repository.get(guildId, weekEnd, place).orElse(null);
                if (row == null || row.hasGif()) return;
                List<String> slices = WheelEntries.spread(row.entries());
                int at = -1;
                for (int i = 0; i < slices.size(); i++) {
                    if (slices.get(i).equalsIgnoreCase(row.winner())) {
                        at = i;
                        break;
                    }
                }
                if (at < 0) return;
                byte[] gif = WheelSpin.spinTo(slices, at, WeeklyDigestInteractionListener.WHEEL_SIZE, RANDOM).gif();
                repository.setGif(guildId, weekEnd, place, gif, GifSource.BOT, true);
                log.info("Drew the bot's wheel GIF for place {} of the week ending {} in guild {}.", place, weekEnd, guildId);
            } catch (Exception e) {
                log.error("Could not draw the wheel GIF for place {} of the week ending {} in guild {}", place, weekEnd, guildId, e);
            }
        });
    }

    static boolean isGif(byte[] bytes) {
        return bytes.length > 10 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == '8' && (bytes[4] == '7' || bytes[4] == '9') && bytes[5] == 'a';
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
