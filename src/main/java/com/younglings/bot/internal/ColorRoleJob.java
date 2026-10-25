package com.younglings.bot.internal;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordJobHandler;
import com.younglings.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/** Swaps a member's name-colour role from the website ({@link ServerProfile#setColorRole}), in the background. It sets an end state, so it may run twice. */
@BService
public class ColorRoleJob implements DiscordJobHandler {
    @Override
    public String kind() {
        return ServerProfile.COLOR_ROLE_JOB;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        String color = job.payload().isNull("color") ? null : job.payload().getString("color", null);
        ServerProfile.applyColorRole(guild, DiscordJobs.member(guild, job.payload().getLong("userId")), color);
        return DataObject.empty().put("colorRole", color);
    }
}
