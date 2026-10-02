package com.younglings.bot.commands;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkService;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.CommandScope;
import io.github.freya022.botcommands.api.commands.application.annotations.Test;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dev-only: writes every human member of this server to {@value #OUTPUT_FILE} (relative to the
 * bot's working directory, i.e. the project root for the IDE run configuration) as Discord username,
 * server display name, and any linked RSN(s) — for filling in RSNs by hand in a spreadsheet. Reads
 * links from whichever database this instance is connected to, so a dev bot only sees the links in
 * the dev database. {@code @Test} (with {@link CommandScope#GUILD}) keeps this out of production's
 * command list entirely, and {@link BotConfig#getLiveEnvironment()} is checked again at runtime as a
 * second layer, same pattern as {@code DevTogglePostingCommand}.
 */
@Command
public class DevExportMembersCommand {
    private static final Logger log = LoggerFactory.getLogger(DevExportMembersCommand.class);

    static final String OUTPUT_FILE = "discord_members_export.csv";

    private final BotConfig botConfig;
    private final PlayerLinkService linkService;

    public DevExportMembersCommand(BotConfig botConfig, PlayerLinkService linkService) {
        this.botConfig = botConfig;
        this.linkService = linkService;
    }

    @TopLevelSlashCommandData(scope = CommandScope.GUILD)
    @Test({})
    @JDASlashCommand(name = "devexportmembers", description = "[Dev only] Writes a local CSV of every member's username, display name, and linked RSN(s)")
    public void onDevExportMembers(GuildSlashEvent event) {
        if (botConfig.getLiveEnvironment()) {
            Containers.replyEphemeral(event, Containers.WARNING, "This command is dev-only.");
            return;
        }

        event.deferReply(true).queue();
        Guild guild = event.getGuild();

        // loadMembers, not guild.getMembers() — the member cache policy is ONLINE-only, so the cache
        // alone would silently leave out everyone who's offline right now.
        guild.loadMembers()
                .onSuccess(members -> {
                    try {
                        Path file = writeCsv(guild.getIdLong(), members);
                        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                                "Wrote **" + countHumans(members) + "** members to `" + file.toAbsolutePath() + "`."))).useComponentsV2(true).queue();
                    } catch (IOException | RuntimeException e) {
                        log.error("Failed to write member export for guild {}", guild.getIdLong(), e);
                        event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                                "Failed to write the CSV — check the bot's logs."))).useComponentsV2(true).queue();
                    }
                })
                .onError(error -> {
                    log.error("Failed to load members for guild {}", guild.getIdLong(), error);
                    event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER,
                            "Couldn't load the member list — check the bot's logs."))).useComponentsV2(true).queue();
                });
    }

    private static long countHumans(List<Member> members) {
        return members.stream().filter(m -> !m.getUser().isBot()).count();
    }

    private Path writeCsv(long guildId, List<Member> members) throws IOException {
        Map<Long, List<String>> rsnsByUser = new LinkedHashMap<>();
        for (PlayerLink link : linkService.getAllLinks(guildId)) {
            rsnsByUser.computeIfAbsent(link.discordUserId(), id -> new ArrayList<>()).add(link.rsn());
        }

        List<Member> humans = members.stream()
                .filter(m -> !m.getUser().isBot())
                .sorted(Comparator.comparing(Member::getEffectiveName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        StringBuilder csv = new StringBuilder("﻿"); // BOM so Excel reads it as UTF-8 without an import wizard
        csv.append("Discord Username,Server Display Name,RSN\r\n");
        for (Member member : humans) {
            String rsns = String.join(", ", rsnsByUser.getOrDefault(member.getIdLong(), List.of()));
            csv.append(escape(member.getUser().getName())).append(',')
                    .append(escape(member.getEffectiveName())).append(',')
                    .append(escape(rsns)).append("\r\n");
        }

        Path file = Path.of(OUTPUT_FILE);
        Files.writeString(file, csv.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static String escape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
