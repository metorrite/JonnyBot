package com.runeclans.bot.warning;

import com.runeclans.bot.moderation.ModAction;
import com.runeclans.bot.moderation.ModCase;
import com.runeclans.bot.moderation.ModService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/** Applies the autopunish rule that a new warning triggers, if JonnyBot is allowed to. */
@BService
public class AutopunishService {
    private static final Logger log = LoggerFactory.getLogger(AutopunishService.class);

    private final WarningRepository repository;
    private final ModService mod;

    public AutopunishService(WarningRepository repository, ModService mod) {
        this.repository = repository;
        this.mod = mod;
    }

    /**
     * Called after a warning is recorded, with the member's new total. Returns a line for the moderator saying what was done or why
     * nothing was, or null when no rule fires at this count.
     */
    public String applyFor(Guild guild, Member target, int totalWarnings) {
        var rule = Autopunish.forCount(repository.autopunishRules(guild.getIdLong()), totalWarnings);
        if (rule.isEmpty()) return null;
        Autopunish.Rule r = rule.get();
        String reason = "Autopunish: " + totalWarnings + " warnings";
        Member self = guild.getSelfMember();
        Permission needed = switch (r.action()) {
            case TIMEOUT -> Permission.MODERATE_MEMBERS;
            case KICK -> Permission.KICK_MEMBERS;
            case BAN -> Permission.BAN_MEMBERS;
        };
        if (!self.hasPermission(needed) || !self.canInteract(target)) {
            return "Autopunish would have had them " + r.describe() + " (" + totalWarnings + " warnings), but the bot lacks the " + needed.getName()
                    + " permission or its role is below theirs.";
        }
        switch (r.action()) {
            case TIMEOUT -> target.timeoutFor(r.timeout()).reason(reason).queue(ok -> {}, e -> log.warn("Autopunish timeout of {} failed: {}", target.getId(), e.getMessage()));
            case KICK -> guild.kick(target).reason(reason).queue(ok -> {}, e -> log.warn("Autopunish kick of {} failed: {}", target.getId(), e.getMessage()));
            case BAN -> guild.ban(target, 0, TimeUnit.SECONDS).reason(reason).queue(ok -> {}, e -> log.warn("Autopunish ban of {} failed: {}", target.getId(), e.getMessage()));
        }
        ModAction done = switch (r.action()) {
            case TIMEOUT -> ModAction.TIMEOUT;
            case KICK -> ModAction.KICK;
            case BAN -> ModAction.BAN;
        };
        mod.recordOnly(guild, target.getIdLong(), target.getUser().getName(), self.getIdLong(), self.getEffectiveName(), done, reason,
                r.action() == Autopunish.Action.TIMEOUT ? r.timeoutMinutes() : null, ModCase.AUTOPUNISH);
        log.info("Autopunish: {} {} in guild {} at {} warnings", target.getId(), r.describe(), guild.getId(), totalWarnings);
        return "Autopunish: they have been " + r.describe() + " (" + totalWarnings + " warnings).";
    }
}
