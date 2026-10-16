package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.BingoBoardImage;
import com.younglings.bot.events.BingoLayout;
import com.younglings.bot.events.BingoLooks;
import com.younglings.bot.events.BingoSampleEvent;
import com.younglings.bot.events.BingoTemplate;
import com.younglings.bot.events.BingoTiles;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Dev-only: makes up a bingo event for each board shape (path, pyramid, convergence, world tree) and for square grids, plays it with six made-up
 * players, and posts the board in both looks side by side, the original template and the RuneScape one, so the shapes and looks can be compared in
 * Discord exactly as the live board draws them. Nothing is saved: no event is created and no real player is involved.
 */
@Command
public class DevBingoShapesCommand {
    private static final Logger log = LoggerFactory.getLogger(DevBingoShapesCommand.class);
    private static final long SEED = 20261016L;
    /** The grid sizes shown: the smallest and the largest tile sizes behave differently, so both are worth looking at. */
    private static final List<Integer> GRID_SIZES = List.of(5, 3, 7);

    private final BotConfig botConfig;
    private final BingoTemplate template;
    private final BingoTiles tiles;

    public DevBingoShapesCommand(BotConfig botConfig, BingoTemplate template, BingoTiles tiles) {
        this.botConfig = botConfig;
        this.template = template;
        this.tiles = tiles;
    }

    @JDASlashCommand(name = "dev", subcommand = "bingoshapes", description = "Posts every bingo shape and grid in both looks, from a made-up event")
    public void onBingoShapes(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }
        event.deferReply(false).queue();
        event.getHook().sendMessage("Making up a bingo for each of the " + BingoLayout.all().size() + " shapes and " + GRID_SIZES.size() + " grid sizes and drawing each in both looks ("
                + BingoSampleEvent.PLAYERS.size() + " made-up players). Template on the left, RuneScape look on the right.").queue();
        for (BingoLayout layout : BingoLayout.all()) {
            post(event, layout.id(), () -> {
                var sample = BingoSampleEvent.build(template, layout, tiles::iconKeyFor, SEED);
                return new Showcase("### " + layout.name() + "\n" + layout.rule() + "\n-# " + sample.completed() + " of " + layout.tiles() + " tiles done in this made-up event.",
                        BingoBoardImage.render(layout, BingoLooks.CLASSIC, sample.tiles()), BingoBoardImage.render(layout, BingoLooks.RUNESCAPE, sample.tiles()));
            });
        }
        for (int size : GRID_SIZES) {
            post(event, "grid-" + size, () -> {
                var sample = BingoSampleEvent.buildGrid(template, size, tiles::iconKeyFor, SEED);
                String subtitle = "Running · tiles show how many players have them";
                String footer = "Submit proof with /events submit event:12 and the tile's number";
                return new Showcase("### Square grid, " + size + " by " + size + "\n-# " + sample.completed() + " of " + size * size + " tiles done in this made-up event.",
                        BingoBoardImage.render("Christmas Bingo", subtitle, size, sample.tiles(), footer, BingoLooks.CLASSIC),
                        BingoBoardImage.render("Christmas Bingo", subtitle, size, sample.tiles(), footer, BingoLooks.RUNESCAPE));
            });
        }
    }

    private record Showcase(String text, byte[] template, byte[] runescape) {}

    private interface Drawing {
        Showcase draw() throws IOException;
    }

    private void post(GuildSlashEvent event, String id, Drawing drawing) {
        try {
            Showcase s = drawing.draw();
            event.getHook().sendMessage(s.text())
                    .addFiles(FileUpload.fromData(s.template(), id + "-1-template.png"), FileUpload.fromData(s.runescape(), id + "-2-runescape.png"))
                    .queue(ok -> { }, error -> log.warn("Could not post the {} board", id, error));
        } catch (IOException | RuntimeException | LinkageError e) {
            log.warn("Could not draw the {} board", id, e);
            event.getHook().sendMessage("Couldn't draw **" + id + "**: " + e.getMessage()).queue();
        }
    }
}
