package com.younglings.bot.internal;

import com.younglings.bot.autopurge.AutoPurgeRepository;
import com.younglings.bot.autopurge.AutoPurgeRule;
import com.younglings.bot.autopurge.AutoPurgeService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's auto-clean rules for the website. Reached only through {@link TicketAdminApi}, which has already checked the asker may
 * use the dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET autopurge}</li>
 *   <li>{@code PUT autopurge} {@code {channelId, maxAgeMinutes, keepPinned}} (adds or changes that channel's rule)</li>
 *   <li>{@code DELETE autopurge/{channelId}}</li>
 * </ul>
 */
@BService
public class AutoPurgeAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AutoPurgeAdminApi.class);

    private final AutoPurgeRepository repository;

    public AutoPurgeAdminApi(AutoPurgeRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("autopurge")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray rules = DataArray.empty();
        for (AutoPurgeRule rule : repository.forGuild(guild.getIdLong())) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, rule.channelId());
            String problem = channel == null ? "This channel no longer exists." : AutoPurgeService.problemWith(channel);
            rules.add(DataObject.empty().put("channelId", Long.toString(rule.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("maxAgeMinutes", rule.maxAgeMinutes()).put("keepPinned", rule.keepPinned()).put("problem", problem));
        }
        return DataObject.empty().put("rules", rules).put("minAge", AutoPurgeRule.MIN_AGE_MINUTES).put("maxAge", AutoPurgeRule.MAX_AGE_MINUTES)
                .put("maxRules", AutoPurgeRule.MAX_RULES_PER_GUILD);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        GuildMessageChannel channel = null;
        if (!raw.matches("\\d{1,20}")) {
            problems.add("Choose a channel to clean.");
        } else {
            channel = guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw));
            if (channel == null) problems.add("That channel doesn't exist in this server.");
            else {
                String permission = AutoPurgeService.problemWith(channel);
                if (permission != null) problems.add(permission);
            }
        }
        int age = body.getInt("maxAgeMinutes", -1);
        if (!AutoPurgeRule.validAge(age)) {
            problems.add("Messages must be kept for between " + AutoPurgeRule.describe(AutoPurgeRule.MIN_AGE_MINUTES) + " and " + AutoPurgeRule.describe(AutoPurgeRule.MAX_AGE_MINUTES) + ".");
        }
        boolean keepPinned = body.getBoolean("keepPinned", true);
        if (channel != null) {
            List<AutoPurgeRule> existing = repository.forGuild(guild.getIdLong());
            final long channelId = channel.getIdLong();
            if (existing.size() >= AutoPurgeRule.MAX_RULES_PER_GUILD && existing.stream().noneMatch(r -> r.channelId() == channelId)) {
                problems.add("A server can clean at most " + AutoPurgeRule.MAX_RULES_PER_GUILD + " channels.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That rule can't be saved yet.", problems);

        repository.save(new AutoPurgeRule(guild.getIdLong(), channel.getIdLong(), age, keepPinned));
        log.info("Dashboard: {} set auto-clean for #{} to {}{}", actor.getId(), channel.getName(), AutoPurgeRule.describe(age), keepPinned ? "" : " (pinned too)");
        return list(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawChannelId) {
        if (rawChannelId == null || !rawChannelId.strip().matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid channel.");
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawChannelId.strip()))) throw new ApiError(404, "That channel has no auto-clean rule.");
        log.info("Dashboard: {} removed auto-clean for channel {}", actor.getId(), rawChannelId.strip());
        return list(guild);
    }
}
