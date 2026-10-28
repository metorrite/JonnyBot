package com.runeclans.bot.commands.lockdown;

import com.runeclans.bot.lockdown.LockdownService;
import com.runeclans.bot.lockdown.LockdownService.Outcome;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /lock [channel] [reason]}, {@code /unlock [channel]} and {@code /lockdown start|end}: stop everyone but chosen roles from talking in a channel, or in the
 * server's saved lockdown set, and put the channel's permissions back exactly as they were. Who may use them is the Hub setting of each command (admins only
 * unless the server widens it on the dashboard's Commands page); the set and the roles that keep talking are chosen on the dashboard's Lockdown page.
 */
@Command
public class LockCommands {
    private final LockdownService service;

    public LockCommands(LockdownService service) {
        this.service = service;
    }

    @TopLevelSlashCommandData(description = "Stop everyone from talking in a channel")
    @JDASlashCommand(name = "lock", description = "Stop everyone but the chosen roles from talking in a channel")
    public void onLock(GuildSlashEvent event,
                       @SlashOption(description = "The channel (this one if left out)") @Nullable TextChannel channel,
                       @SlashOption(description = "Why, shown in the channel") @Nullable String reason) {
        StandardGuildMessageChannel target = target(event, channel);
        if (target == null) return;
        event.deferReply(true).queue();
        Outcome outcome = service.lock(event.getGuild(), target, event.getUser().getIdLong(), reason, false);
        event.getHook().editOriginal(describe(outcome, target, true)).queue();
    }

    @TopLevelSlashCommandData(description = "Let everyone talk in a locked channel again")
    @JDASlashCommand(name = "unlock", description = "Put a locked channel's permissions back as they were")
    public void onUnlock(GuildSlashEvent event, @SlashOption(description = "The channel (this one if left out)") @Nullable TextChannel channel) {
        StandardGuildMessageChannel target = target(event, channel);
        if (target == null) return;
        event.deferReply(true).queue();
        Outcome outcome = service.unlock(event.getGuild(), target, event.getUser().getIdLong());
        event.getHook().editOriginal(describe(outcome, target, false)).queue();
    }

    @TopLevelSlashCommandData(description = "Lock or unlock the server's saved set of channels")
    @JDASlashCommand(name = "lockdown", subcommand = "start", description = "Lock every channel in the saved lockdown set")
    public void onStart(GuildSlashEvent event, @SlashOption(description = "Why, shown in each channel") @Nullable String reason) {
        Guild guild = event.getGuild();
        if (service.config(guild.getIdLong()).channelIds().isEmpty()) {
            event.reply("There is no lockdown set yet. Choose the channels on the dashboard's Lockdown page first.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        int[] result = service.start(guild, event.getUser().getIdLong(), reason);
        event.getHook().editOriginal("Lockdown started: " + result[0] + " locked" + (result[1] > 0 ? ", " + result[1] + " already locked" : "")
                + (result[2] > 0 ? ", " + result[2] + " could not be locked (missing channel or permission)" : "") + ". `/lockdown end` puts them back.").queue();
    }

    @JDASlashCommand(name = "lockdown", subcommand = "end", description = "Unlock the channels a lockdown locked")
    public void onEnd(GuildSlashEvent event) {
        event.deferReply(true).queue();
        int[] result = service.end(event.getGuild(), event.getUser().getIdLong());
        event.getHook().editOriginal("Lockdown ended: " + result[0] + " unlocked" + (result[1] > 0 ? ", " + result[1] + " could not be unlocked" : "")
                + ". Channels locked by hand with `/lock` stay locked.").queue();
    }

    private static StandardGuildMessageChannel target(GuildSlashEvent event, TextChannel chosen) {
        StandardGuildMessageChannel target = chosen != null ? chosen : event.getChannel() instanceof StandardGuildMessageChannel here ? here : null;
        if (target == null) event.reply("Choose a text channel (this one can't be locked).").setEphemeral(true).queue();
        return target;
    }

    static String describe(Outcome outcome, StandardGuildMessageChannel channel, boolean locking) {
        return switch (outcome) {
            case DONE -> (locking ? "Locked " : "Unlocked ") + channel.getAsMention() + ".";
            case ALREADY -> channel.getAsMention() + " is already locked.";
            case NOT_LOCKED -> channel.getAsMention() + " isn't locked by the bot, so there is nothing to put back.";
            case NO_PERMISSION -> "The bot needs to see, send messages in and manage permissions of " + channel.getAsMention() + " to do that.";
            case FAILED -> "Discord refused part of that, so " + channel.getAsMention() + " was put back as it was. Try again.";
        };
    }
}
