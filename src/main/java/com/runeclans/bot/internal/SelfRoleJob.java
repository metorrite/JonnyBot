package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataObject;

/**
 * Gives or takes a self-assignable role a member picked on the website ({@code POST /internal/me/roles}), in the background. The payload is
 * the end state ("on" or "off"), never a toggle, so running it twice is harmless.
 */
@BService
public class SelfRoleJob implements DiscordJobHandler {
    static final String KIND = "member.self-role";

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        Role role = guild.getRoleById(job.payload().getLong("roleId"));
        if (role == null) throw new DiscordJobs.Refused("That role no longer exists.");
        // checked when it was asked for, and again now: the role may have been given dangerous permissions since
        if (!MemberApi.safeToAssign(guild, role)) throw new DiscordJobs.Refused("That role can't be assigned from here.");
        boolean on = job.payload().getBoolean("on", true);
        MemberApi.applySelfRole(guild, DiscordJobs.member(guild, job.payload().getLong("userId")), role, on);
        return DataObject.empty().put("roleId", role.getId()).put("has", on);
    }
}
