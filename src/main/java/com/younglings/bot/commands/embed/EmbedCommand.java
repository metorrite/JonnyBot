package com.younglings.bot.commands.embed;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.annotations.Filter;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;

import java.util.List;

/**
 * Single entry point for posting/removing the bot's pre-designed embeds (see {@link EmbedType}) —
 * replaces {@code /teamforming post|revert} (retired, kept as reference — see
 * {@code TeamformingCommand}) with a generic system any future embed type can plug into.
 * <p>
 * Gated once here via {@code @Filter} rather than re-checked per button: the resulting menu is an
 * ephemeral message, which Discord only ever shows to the user who triggered it, so there's no
 * separate path for an unauthorized member to reach the Post/Remove buttons at all.
 */
@Command
public class EmbedCommand {

    @Filter(AdminRoleFilter.class)
    @JDASlashCommand(name = "embed", description = "Post or remove one of the server's pre-designed embeds")
    public void onEmbed(GuildSlashEvent event) {
        if (event.getGuild() == null) {
            event.reply("This command can only be used in a server.").setEphemeral(true).queue();
            return;
        }

        Container hub = Container.of(
                TextDisplay.of("# Embed Manager"),
                TextDisplay.of("Post one of the server's designed embeds, or remove one already posted."),
                ActionRow.of(
                        Button.primary("embed_post:_", "Post"),
                        Button.secondary("embed_remove:_", "Remove"),
                        Button.danger("embed_remove_all:_", "Remove All")
                )
        ).withAccentColor(Containers.PRIMARY);

        event.replyComponents(List.of(hub)).useComponentsV2(true).setEphemeral(true).queue();
    }
}
