package com.younglings.bot.internal;

import com.younglings.bot.autodelete.AutoDeleteRepository;
import com.younglings.bot.autodelete.AutoDeleteRule;
import com.younglings.bot.autodelete.AutoDeleteRule.Filter;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's auto-delete rules for the website: {@code GET autodelete}, {@code PUT autodelete} {@code {channelId, filters}} (adds or
 * changes a channel's rule) and {@code DELETE autodelete/{channelId}}. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class AutoDeleteAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AutoDeleteAdminApi.class);

    private final AutoDeleteRepository repository;

    public AutoDeleteAdminApi(AutoDeleteRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("autodelete")) return null;
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
        for (AutoDeleteRule rule : repository.forGuild(guild.getIdLong())) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, rule.channelId());
            String problem = channel == null ? "This channel no longer exists."
                    : guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_MANAGE) ? null
                    : "JonnyBot needs the View Channel and Manage Messages permissions in #" + channel.getName() + ".";
            DataArray filters = DataArray.empty();
            for (Filter f : Filter.values()) if (rule.filters().contains(f)) filters.add(f.name());
            rules.add(DataObject.empty().put("channelId", Long.toString(rule.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("filters", filters).put("problem", problem));
        }
        DataArray available = DataArray.empty();
        for (Filter f : Filter.values()) available.add(DataObject.empty().put("key", f.name()).put("label", f.label()));
        return DataObject.empty().put("rules", rules).put("available", available).put("maxRules", AutoDeleteRule.MAX_RULES_PER_GUILD);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        GuildMessageChannel channel = null;
        if (!raw.matches("\\d{1,20}")) {
            problems.add("Choose a channel.");
        } else {
            channel = guild.getChannelById(GuildMessageChannel.class, Long.parseLong(raw));
            if (channel == null) problems.add("That channel doesn't exist in this server.");
            else if (!guild.getSelfMember().hasPermission(channel, Permission.VIEW_CHANNEL, Permission.MESSAGE_MANAGE)) {
                problems.add("JonnyBot needs the View Channel and Manage Messages permissions in #" + channel.getName() + " to delete messages there.");
            }
        }
        Set<Filter> filters = EnumSet.noneOf(Filter.class);
        DataArray given = body.hasKey("filters") && !body.isNull("filters") ? body.getArray("filters") : DataArray.empty();
        for (int i = 0; i < given.length(); i++) {
            try {
                filters.add(Filter.valueOf(given.getString(i).strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                problems.add("One of the filters isn't one JonnyBot knows.");
            }
        }
        if (filters.isEmpty() && problems.isEmpty()) problems.add("Choose at least one thing to delete.");
        if (channel != null) {
            final long channelId = channel.getIdLong();
            var existing = repository.forGuild(guild.getIdLong());
            if (existing.size() >= AutoDeleteRule.MAX_RULES_PER_GUILD && existing.stream().noneMatch(r -> r.channelId() == channelId)) {
                problems.add("A server can have rules for at most " + AutoDeleteRule.MAX_RULES_PER_GUILD + " channels.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That rule can't be saved yet.", problems.stream().distinct().toList());

        repository.save(new AutoDeleteRule(guild.getIdLong(), channel.getIdLong(), filters));
        log.info("Dashboard: {} set auto-delete for #{} to {}", actor.getId(), channel.getName(), filters);
        return list(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawChannelId) {
        if (rawChannelId == null || !rawChannelId.strip().matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid channel.");
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawChannelId.strip()))) throw new ApiError(404, "That channel has no auto-delete rule.");
        log.info("Dashboard: {} removed auto-delete for channel {}", actor.getId(), rawChannelId.strip());
        return list(guild);
    }
}
