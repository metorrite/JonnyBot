package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

/** Sets a member's nickname from the website ({@link ServerProfile#setNickname}), in the background. Setting it twice is harmless. */
@BService
public class NicknameJob implements DiscordJobHandler {
    @Override
    public String kind() {
        return ServerProfile.NICKNAME_JOB;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        String nickname = job.payload().isNull("nickname") ? null : job.payload().getString("nickname", null);
        ServerProfile.applyNickname(DiscordJobs.member(guild, job.payload().getLong("userId")), nickname);
        return DataObject.empty().put("nickname", nickname);
    }
}
