package com.younglings.bot.tracking;

import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.wheel.WheelEligibility;
import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelMessage;
import com.younglings.bot.wheel.WheelSettingsRepository;
import com.younglings.bot.wheel.WheelSpin;
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
        Long user = linkedUser(guild, winner);
        return WheelMessage.render(settings.get(guild.getIdLong()).message(), winner, user == null ? null : "<@" + user + ">", weekLabel(window), entryCount);
    }

    /** Posts a locked week's announcement with the picture saved for it, in the channel the server chose. */
    public GuildMessageChannel postLocked(Guild guild, LocalDate weekEnd) {
        var row = winners.get(guild.getIdLong(), weekEnd).orElseThrow(() -> new CannotPost("That week isn't locked."));
        byte[] gif = winners.gif(guild.getIdLong(), weekEnd).orElse(null);
        return postWinner(guild, CitadelWeek.windowEndingOn(weekEnd), row.winner(), row.entries().size(), gif);
    }

    /** Posts the configured message with {@code gif}. Returns the channel it went to. */
    public GuildMessageChannel postWinner(Guild guild, OffsetDateTime[] window, String winner, int entryCount, byte[] gif) {
        GuildMessageChannel channel = channelFor(guild);
        Long user = linkedUser(guild, winner);
        String text = messageFor(guild, window, winner, entryCount);
        var action = channel.sendMessage(text).setAllowedMentions(List.of());
        if (user != null) action = action.mentionUsers(user);
        if (gif != null) action = action.addFiles(FileUpload.fromData(gif, "citadel-wheel.gif"));
        action.queue(ok -> { }, error -> log.warn("Could not post the Citadel wheel winner in guild {}", guild.getIdLong(), error));
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
            if (winners.get(guild.getIdLong(), weekEnd).isPresent()) {
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
                if (link.rsn().equalsIgnoreCase(winner) && guild.getMemberById(link.discordUserId()) != null) return link.discordUserId();
            }
        } catch (RuntimeException e) {
            log.debug("Couldn't look up the winner's Discord account", e);
        }
        return null;
    }
}
