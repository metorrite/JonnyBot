package com.younglings.bot.commands.signup;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only diagnostic command — lists every currently active signup across every guild the bot
 * is in (not just this one), with a one-click "close everything" tool for wiping out test data
 * between iterations. {@code @Test} (with {@link CommandScope#GUILD}) keeps this out of
 * production entirely, and {@link BotConfig#getLiveEnvironment()} is checked again at runtime as
 * a second layer, since a bulk-close tool is exactly the kind of thing worth not trusting to a
 * single gate.
 */
@Command
public class SignupDevCommand {
    private static final int MAX_LISTED = 40;

    private final SignupService signupService;
    private final BotConfig botConfig;

    public SignupDevCommand(SignupService signupService, BotConfig botConfig) {
        this.signupService = signupService;
        this.botConfig = botConfig;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "devsignups", description = "[Dev only] Lists every active signup across every server this bot is in")
    public void onDevSignups(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            event.reply("This command is dev-only.").setEphemeral(true).queue();
            return;
        }

        List<SignupSession> all = signupService.getAllActiveSignups();

        if (all.isEmpty()) {
            event.reply("No active signups found across any server.").setEphemeral(true).queue();
            return;
        }

        Map<Long, List<SignupSession>> byGuild = new LinkedHashMap<>();
        for (SignupSession session : all) {
            byGuild.computeIfAbsent(session.guildId(), id -> new ArrayList<>()).add(session);
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;

        outer:
        for (var entry : byGuild.entrySet()) {
            Guild guild = event.getJDA().getGuildById(entry.getKey());
            sb.append("**").append(guild != null ? guild.getName() : "Unknown guild")
                    .append("** (`").append(entry.getKey()).append("`)\n");

            for (SignupSession session : entry.getValue()) {
                if (shown++ >= MAX_LISTED) {
                    sb.append("*...and more.*\n");
                    break outer;
                }
                sb.append("  • `").append(session.signupId()).append("` — ")
                        .append(session.title()).append(" (").append(session.type()).append(")\n");
            }
        }

        sb.append("\n**Total: ").append(all.size()).append(" active signup(s) across ")
                .append(byGuild.size()).append(" server(s).**");

        event.reply(sb.toString())
                .setEphemeral(true)
                .addComponents(ActionRow.of(Button.danger("signup_dev_close_all", "Close ALL Active Signups")))
                .queue();
    }
}
