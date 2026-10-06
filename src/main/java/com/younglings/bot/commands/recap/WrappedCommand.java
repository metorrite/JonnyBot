package com.younglings.bot.commands.recap;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.MemberAccess;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.FileUpload;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /wrapped} — posts a "year in review" style recap card for a member or the whole clan. The card itself is
 * drawn by the website (one design, in one place, shared with the animated recap pages); the bot just fetches the
 * finished picture and shows it, with a button to the full animated version. If the website can't be reached, the
 * member still gets the link.
 */
@Command
public class WrappedCommand {
    private static final Logger log = LoggerFactory.getLogger(WrappedCommand.class);
    private static final Duration COOLDOWN = Duration.ofSeconds(12);
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    public enum Period {
        WEEK("week", "This week"), LAST_WEEK("last-week", "Last week"), MONTH("month", "This month"), LAST_MONTH("last-month", "Last month"),
        YEAR("year", "This year"), LAST_YEAR("last-year", "Last year"), ALL("all", "All time");

        final String token;
        final String label;

        Period(String token, String label) {
            this.token = token;
            this.label = label;
        }
    }

    private final BotConfig botConfig;
    private final MemberAccess memberAccess;
    private final PlayerLinkRepository links;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Long, Long> lastUse = new ConcurrentHashMap<>();

    public WrappedCommand(BotConfig botConfig, MemberAccess memberAccess, PlayerLinkRepository links) {
        this.botConfig = botConfig;
        this.memberAccess = memberAccess;
        this.links = links;
    }

    @JDASlashCommand(name = "wrapped", description = "Your recap card — XP, Citadel, boss kills and more — for a week, month, year or all time")
    public void onWrapped(GuildSlashEvent event,
                          @SlashOption(description = "How far back to look (default: this month)", usePredefinedChoices = true) @Nullable Period period,
                          @SlashOption(description = "A RuneScape name, or clan for the whole clan (default: you)") @Nullable String who) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command can only be used in a server.");
            return;
        }
        if (!memberAccess.isMemberTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, "Recaps are for clan members — link your RuneScape name with `/rs` to get access.");
            return;
        }
        String site = botConfig.getSiteUrl();
        if (site == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "Recaps aren't set up yet — the bot doesn't know the website's address.");
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastUse.get(member.getIdLong());
        if (previous != null && now - previous < COOLDOWN.toMillis()) {
            Containers.replyEphemeral(event, Containers.WARNING, "Give it a few seconds before asking for another recap.");
            return;
        }
        lastUse.put(member.getIdLong(), now);

        Period chosen = period == null ? Period.MONTH : period;
        String target = who == null ? "" : who.trim();
        boolean clan = target.equalsIgnoreCase("clan");
        String rsn = target;
        if (!clan && rsn.isEmpty()) {
            List<PlayerLink> mine = links.getLinksForUser(guild.getIdLong(), member.getIdLong());
            if (mine.isEmpty()) {
                Containers.replyEphemeral(event, Containers.WARNING, "No RuneScape name is linked to your account — use `/rs` first, or give a name: `/wrapped who:SomeName`.");
                return;
            }
            rsn = mine.getFirst().rsn();
        }

        String path = clan ? "/recap/clan/" + chosen.token : "/recap/member/" + URLEncoder.encode(rsn, StandardCharsets.UTF_8).replace("+", "%20") + "/" + chosen.token;
        String title = (clan ? "The clan" : rsn) + " — " + chosen.label;

        // The picture can take a moment to draw the first time, so acknowledge now and answer when it's ready.
        event.deferReply().queue();
        byte[] image = fetchImage(site + path + "/image");

        if (image == null) {
            event.getHook().editOriginalComponents(List.of(Containers.card(Containers.PRIMARY, TextDisplay.of("### " + title + "\nThe recap card couldn't be drawn just now — the full recap is on the website."),
                    ActionRow.of(Button.link(site + path, "Open the recap"))))).useComponentsV2(true).queue();
            return;
        }

        Container container = Containers.card(Containers.PRIMARY,
                TextDisplay.of("### " + title),
                MediaGallery.of(MediaGalleryItem.fromFile(FileUpload.fromData(image, "recap.png"))),
                ActionRow.of(Button.link(site + path, "Open the animated recap")));
        event.getHook().editOriginalComponents(List.of(container)).useComponentsV2(true).queue();
    }

    /** The finished picture from the website, or {@code null} if it couldn't be fetched. */
    private byte[] fetchImage(String url) {
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.statusCode() != 200 || !type.startsWith("image/") || response.body().length > MAX_IMAGE_BYTES) {
                log.warn("Recap image {} returned {} ({}, {} bytes)", url, response.statusCode(), type, response.body().length);
                return null;
            }
            return response.body();
        } catch (IOException e) {
            log.warn("Couldn't fetch recap image {}", url, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
