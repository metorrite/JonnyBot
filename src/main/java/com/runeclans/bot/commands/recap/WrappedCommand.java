package com.runeclans.bot.commands.recap;

import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.RecapGate;
import com.runeclans.bot.member.MemberProfileRepository;
import com.runeclans.bot.permission.MemberAccess;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkRepository;
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
 * <p>
 * A member's recap is private until that member has opened it ({@link RecapGate}): asking for your own counts as opening it (the card is
 * then posted for the channel, and the website, which draws it for an anonymous fetch, shows it too), unless you turned recaps off, when
 * only you get the link. Someone else's recap is posted only once its member has opened it, otherwise the asker is told privately.
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
    private final RecapGate recapGate;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Long, Long> lastUse = new ConcurrentHashMap<>();

    public WrappedCommand(BotConfig botConfig, MemberAccess memberAccess, PlayerLinkRepository links, MemberProfileRepository profiles) {
        this.botConfig = botConfig;
        this.memberAccess = memberAccess;
        this.links = links;
        this.recapGate = new RecapGate(profiles);
    }

    @JDASlashCommand(name = "wrapped", description = "Your recap card — XP, Citadel, boss kills and more — for a week, month, year or all time")
    public void onWrapped(GuildSlashEvent event,
                          @SlashOption(description = "How far back to look (default: this month)", usePredefinedChoices = true) @Nullable Period period,
                          @SlashOption(description = "A RuneScape name, or clan for the whole clan (default: you)") @Nullable String who) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.this_command_can_only_be_used"));
            return;
        }
        if (!memberAccess.isMemberTier(guild, member)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.recaps_are_for_clan_members_link"));
            return;
        }
        String site = botConfig.getSiteUrl();
        if (site == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.recaps_aren_t_set_up_yet"));
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = lastUse.get(member.getIdLong());
        if (previous != null && now - previous < COOLDOWN.toMillis()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.give_it_a_few_seconds_before"));
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
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.no_runescape_name_is_linked_to"));
                return;
            }
            rsn = mine.getFirst().rsn();
        }

        String path = clan ? "/recap/clan/" + chosen.token : "/recap/member/" + URLEncoder.encode(rsn, StandardCharsets.UTF_8).replace("+", "%20") + "/" + chosen.token;
        String title = (clan ? "The clan" : rsn) + " — " + chosen.label;

        if (!clan) {
            // No spoilers: your own recap counts as opened now; someone else's only once they've opened it themselves.
            RecapGate.Decision decision;
            try {
                decision = recapGate.check(guild.getIdLong(), rsn, chosen.token, member.getIdLong());
            } catch (RuntimeException e) {
                log.warn("Couldn't check who may see a recap in server {}", guild.getIdLong(), e);
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "recap.couldn_t_check_that_recap"));
                return;
            }
            if (decision.outcome() == RecapGate.Outcome.PRIVATE) {
                Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "recap.that_recap_is_private_until", rsn));
                return;
            }
            if (decision.outcome() == RecapGate.Outcome.SUBJECT && !decision.sharedWithClan()) {
                // they turned recaps off: the channel doesn't get it (and the website wouldn't draw it for anyone else)
                event.replyComponents(List.of(Containers.card(Containers.INFO, TextDisplay.of("### " + title + "\n" + Text.of(event, "recap.only_you_can_see_your_recaps")),
                        ActionRow.of(Button.link(site + path, "Open your recap"))))).useComponentsV2(true).setEphemeral(true).queue();
                return;
            }
        }

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
