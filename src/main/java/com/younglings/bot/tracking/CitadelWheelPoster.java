package com.younglings.bot.tracking;

import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.wheel.WheelEligibility;
import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelMessage;
import com.younglings.bot.wheel.WheelSettingsRepository;
import com.younglings.bot.wheel.WheelSpin;
import com.younglings.bot.wheel.WheelVideo;
import com.younglings.bot.wheel.WheelWinnerRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Announces the Citadel wheel's winner in the channel the server chose, with its message and the wheel GIF. Three ways in: automatically when the weekly
 * list is made ({@link #autoPost}), from the "Post winner" button under a spin in Discord ({@link #postWinner} with the GIF that was spun), and from the
 * dashboard ({@link #postFromDashboard}, which draws a GIF that lands on the winner the dashboard picked). Settings are in {@link WheelSettingsRepository}.
 */
@BService
public class CitadelWheelPoster {
    private static final Logger log = LoggerFactory.getLogger(CitadelWheelPoster.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** A reason a post can't be made, in words for the person who asked. */
    public static final class CannotPost extends RuntimeException {
        public CannotPost(String message) {
            super(message);
        }
    }

    private final CitadelWheelEntries entries;
    private final WheelSettingsRepository settings;
    private final PlayerLinkRepository links;
    private final TrackingPostingToggle postingToggle;
    private final CitadelWinners winners;

    public CitadelWheelPoster(CitadelWheelEntries entries, WheelSettingsRepository settings, PlayerLinkRepository links, TrackingPostingToggle postingToggle,
                              CitadelWinners winners) {
        this.winners = winners;
        this.entries = entries;
        this.settings = settings;
        this.links = links;
        this.postingToggle = postingToggle;
    }

    public static String weekLabel(OffsetDateTime[] window) {
        return window[0].withOffsetSameInstant(ZoneOffset.UTC).format(DAY) + " to " + window[1].withOffsetSameInstant(ZoneOffset.UTC).minusDays(1).format(DAY);
    }

    /** The channel winners are announced in, or why there isn't one. */
    public GuildMessageChannel channelFor(Guild guild) {
        WheelSettingsRepository.Settings current = settings.get(guild.getIdLong());
        if (current.channelId() == null) throw new CannotPost("Choose the channel winners are posted in on the dashboard's Citadel wheel page first.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, current.channelId());
        if (channel == null) throw new CannotPost("The channel chosen for wheel posts no longer exists. Choose another on the dashboard.");
        if (!channel.canTalk()) throw new CannotPost("JonnyBot can't post in #" + channel.getName() + ". It needs to see the channel and send messages and attach files there.");
        return channel;
    }

    /**
     * The announcement text for a winner: the server's message with the winner, their Discord mention (when their RuneScape account is linked to a
     * member), the week and the number of entries filled in. This is what is posted, and what the dashboard offers to copy.
     */
    public String messageFor(Guild guild, OffsetDateTime[] window, String winner, int entryCount) {
        return messageFor(guild, window, List.of(winner), entryCount);
    }

    /** As above for a week with several winners (in order of place): the message lists all of them. */
    public String messageFor(Guild guild, OffsetDateTime[] window, List<String> winnerNames, int entryCount) {
        List<String> mentions = new java.util.ArrayList<>();
        for (String name : winnerNames) {
            Long user = linkedUser(guild, name);
            mentions.add(user == null ? null : "<@" + user + ">");
        }
        return WheelMessage.renderMany(settings.get(guild.getIdLong()).message(), winnerNames, mentions, weekLabel(window), entryCount);
    }

    private static String placeLabel(int place) {
        return switch (place) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> place + "th";
        };
    }

    /**
     * Posts a locked week's announcement in the channel the server chose: the message with the first winner's saved picture, then, for a week with
     * more winners, one short message each with their own picture.
     */
    public GuildMessageChannel postLocked(Guild guild, LocalDate weekEnd) {
        var rows = winners.forWeek(guild.getIdLong(), weekEnd);
        if (rows.isEmpty()) throw new CannotPost("That week isn't locked.");
        OffsetDateTime[] window = CitadelWeek.windowEndingOn(weekEnd);
        GuildMessageChannel channel = channelFor(guild);
        String text = messageFor(guild, window, rows.stream().map(WheelWinnerRepository.Winner::winner).toList(), rows.get(0).entries().size());
        List<Long> everyone = new java.util.ArrayList<>();
        for (var row : rows) {
            Long user = linkedUser(guild, row.winner());
            if (user != null) everyone.add(user);
        }
        sendWithGif(guild, channel, text, everyone, winners.gif(guild.getIdLong(), weekEnd, rows.get(0).place()).orElse(null));
        for (int i = 1; i < rows.size(); i++) {
            var row = rows.get(i);
            Long user = linkedUser(guild, row.winner());
            String caption = "**" + placeLabel(row.place()) + " place:** " + (user == null ? "**" + row.winner() + "**" : "<@" + user + ">");
            sendWithGif(guild, channel, caption, user == null ? List.of() : List.of(user), winners.gif(guild.getIdLong(), weekEnd, row.place()).orElse(null));
        }
        return channel;
    }

    /**
     * Sends {@code text} with {@code gif}. Normally the picture is attached to the message. When the message has the {@code {wheel}} tag the text goes
     * first (without the tag) and the picture follows in a message of its own: Discord hides a link or picture's name only when it is alone, and always
     * draws it under a message.
     */
    private void sendWithGif(Guild guild, GuildMessageChannel channel, String text, List<Long> usersToPing, byte[] gif) {
        String fileName = "citadel-wheel.gif";
        byte[] picture = gif;
        // a server can choose to get the spin as a video; if it can't be made (or is too big for Discord) the GIF goes out as usual
        if (gif != null && settings.get(guild.getIdLong()).video()) {
            byte[] video = WheelVideo.fromGif(gif);
            if (video != null) {
                picture = video;
                fileName = "citadel-wheel.mp4";
            }
        }
        boolean separate = picture != null && text != null && text.contains(WheelMessage.WHEEL);
        var action = channel.sendMessage(WheelMessage.withoutWheel(text)).setAllowedMentions(List.of());
        if (!usersToPing.isEmpty()) action = action.mentionUsers(usersToPing.stream().mapToLong(Long::longValue).toArray());
        if (picture != null && !separate) action = action.addFiles(FileUpload.fromData(picture, fileName));
        action.queue(ok -> { }, error -> log.warn("Could not post the Citadel wheel winner in guild {}", guild.getIdLong(), error));
        if (separate) {
            channel.sendFiles(FileUpload.fromData(picture, fileName))
                    .queue(ok -> { }, error -> log.warn("Could not post the Citadel wheel GIF in guild {}", guild.getIdLong(), error));
        }
    }

    /** Posts the configured message with {@code gif}. Returns the channel it went to. */
    public GuildMessageChannel postWinner(Guild guild, OffsetDateTime[] window, String winner, int entryCount, byte[] gif) {
        GuildMessageChannel channel = channelFor(guild);
        Long user = linkedUser(guild, winner);
        sendWithGif(guild, channel, messageFor(guild, window, winner, entryCount), user == null ? List.of() : List.of(user), gif);
        return channel;
    }

    /** The dashboard's wheel was spun there; draw a GIF that stops on its winner and announce it. {@code drawn} is every entry, one per slice's owner. */
    public GuildMessageChannel postFromDashboard(Guild guild, OffsetDateTime[] window, String winner, List<String> drawn) throws IOException {
        channelFor(guild);
        List<String> slices = WheelEntries.spread(drawn);
        int at = -1;
        for (int i = 0; i < slices.size(); i++) if (slices.get(i).equalsIgnoreCase(winner)) { at = i; break; }
        if (at < 0) throw new CannotPost("The winner isn't on the wheel.");
        byte[] gif = WheelSpin.spinTo(slices, at, WeeklyDigestInteractionListener.WHEEL_SIZE, new java.security.SecureRandom()).gif();
        return postWinner(guild, window, slices.get(at), slices.size(), gif);
    }

    /**
     * When the weekly list has just been made: if the server turned auto-posting on, spins the wheel for that week (everyone who capped, one entry per
     * person, staff left off) and announces the winner. Does nothing when it is off, nobody is on the wheel, or posting is switched off for this bot
     * (a development copy).
     */
    public void autoPost(Guild guild, OffsetDateTime[] window) {
        try {
            WheelSettingsRepository.Settings current = settings.get(guild.getIdLong());
            if (!current.autoPost() || current.channelId() == null) return;
            if (!postingToggle.isEnabled()) {
                log.info("Wheel auto-post for guild {} skipped: posting is switched off on this instance.", guild.getIdLong());
                return;
            }
            LocalDate weekEnd = window[1].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
            if (!winners.forWeek(guild.getIdLong(), weekEnd).isEmpty()) {
                log.info("Wheel auto-post for guild {} skipped: the week ending {} already has a locked winner.", guild.getIdLong(), weekEnd);
                return;
            }
            WheelEligibility.Result eligible = entries.forWeek(guild, window[0], window[1]);
            if (eligible.names().isEmpty()) return;
            List<String> slices = WheelEntries.spread(eligible.names());
            var spin = WheelSpin.spin(slices, WeeklyDigestInteractionListener.WHEEL_SIZE);
            // the winner is locked in first, so the week has exactly one winner and its picture can be fetched again later
            winners.lock(guild.getIdLong(), weekEnd, spin.winner(), slices, WheelWinnerRepository.Method.AUTO, null, spin.gif(), WheelWinnerRepository.GifSource.BOT);
            postWinner(guild, window, spin.winner(), slices.size(), spin.gif());
            log.info("Posted the Citadel wheel winner for guild {} ({} entries).", guild.getIdLong(), slices.size());
        } catch (CannotPost e) {
            log.warn("Wheel auto-post for guild {} couldn't post: {}", guild.getIdLong(), e.getMessage());
        } catch (Exception e) {
            log.error("Wheel auto-post failed for guild {}", guild.getIdLong(), e);
        }
    }

    /** The Discord user a winner's account is linked to, when they are in the server, so the message can mention them. */
    private Long linkedUser(Guild guild, String winner) {
        try {
            for (var link : links.getAllLinks(guild.getIdLong())) {
                // no check that the member is cached: JDA may not hold every member, and a mention by id works for anyone in the server
                if (CitadelPrizes.sameName(link.rsn(), winner)) return link.discordUserId();
            }
        } catch (RuntimeException e) {
            log.debug("Couldn't look up the winner's Discord account", e);
        }
        return null;
    }
}
