package com.younglings.bot.internal;

import com.younglings.bot.autopurge.AutoPurgeRepository;
import com.younglings.bot.autopurge.AutoPurgeRule;
import com.younglings.bot.autopurge.AutoPurgeService;
import com.younglings.bot.i18n.Text;
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
    private final AutoPurgeService service;

    public AutoPurgeAdminApi(AutoPurgeRepository repository, AutoPurgeService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("autopurge")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("preview")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return preview(guild, body.get());
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
                    .put("maxAgeMinutes", rule.maxAgeMinutes()).put("keepPinned", rule.keepPinned()).put("problem", problem)
                    .put("authors", rule.authors().name()).put("onlyWithAttachments", rule.onlyWithAttachments()).put("containsText", rule.containsText()));
        }
        return DataObject.empty().put("rules", rules).put("minAge", AutoPurgeRule.MIN_AGE_MINUTES).put("maxAge", AutoPurgeRule.MAX_AGE_MINUTES)
                .put("maxRules", AutoPurgeRule.MAX_RULES_PER_GUILD).put("maxTextLength", AutoPurgeRule.MAX_TEXT_LENGTH);
    }

    /** A rule as the request describes it, checked; throws a 400 naming every problem. Nothing is stored or deleted here. */
    private AutoPurgeRule parse(Guild guild, DataObject body, GuildMessageChannel[] channelOut) {
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

        AutoPurgeRule.Authors authors = AutoPurgeRule.Authors.ALL;
        if (body.hasKey("authors") && !body.isNull("authors")) {
            try {
                authors = AutoPurgeRule.Authors.valueOf(body.getString("authors", "").strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Whose messages must be everyone, only bots or only people.");
            }
        }
        boolean attachments = body.getBoolean("onlyWithAttachments", false);
        String text = body.hasKey("containsText") && !body.isNull("containsText") ? body.getString("containsText", "") : null;
        String textProblem = AutoPurgeRule.textProblem(text);
        if (textProblem != null) problems.add(textProblem);

        if (channel != null) {
            List<AutoPurgeRule> existing = repository.forGuild(guild.getIdLong());
            final long channelId = channel.getIdLong();
            if (existing.size() >= AutoPurgeRule.MAX_RULES_PER_GUILD && existing.stream().noneMatch(r -> r.channelId() == channelId)) {
                problems.add("A server can clean at most " + AutoPurgeRule.MAX_RULES_PER_GUILD + " channels.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_rule_can_t_be_saved"), problems);
        channelOut[0] = channel;
        return new AutoPurgeRule(guild.getIdLong(), channel.getIdLong(), age, keepPinned, authors, attachments, text);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        GuildMessageChannel[] channel = new GuildMessageChannel[1];
        AutoPurgeRule rule = parse(guild, body, channel);
        repository.save(rule);
        log.info("Dashboard: {} set auto-clean for #{} to {}{}{}", actor.getId(), channel[0].getName(), AutoPurgeRule.describe(rule.maxAgeMinutes()), rule.keepPinned() ? "" : " (pinned too)",
                rule.filtered() ? " (filtered: " + rule.authors() + (rule.onlyWithAttachments() ? ", attachments" : "") + (rule.containsText() != null ? ", text" : "") + ")" : "");
        return list(guild);
    }

    /**
     * The dry run: how many of the messages a sweep would look at right now this rule would delete. Reads the channel's history like a sweep does and
     * deletes nothing, so the dashboard can show the number before the rule is saved.
     */
    DataObject preview(Guild guild, DataObject body) {
        GuildMessageChannel[] channel = new GuildMessageChannel[1];
        AutoPurgeRule rule = parse(guild, body, channel);
        AutoPurgeService.Scan scan = service.scan(channel[0], rule, java.time.Instant.now(), false);
        return DataObject.empty().put("matched", scan.matched()).put("scanned", scan.scanned()).put("mayHaveMore", scan.mayHaveMore())
                .put("scanLimit", AutoPurgeService.PAGES * 100).put("channelName", channel[0].getName());
    }

    DataObject delete(Guild guild, Member actor, String rawChannelId) {
        if (rawChannelId == null || !rawChannelId.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_channel"));
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawChannelId.strip()))) throw new ApiError(404, Text.of(guild, "dashboard.that_channel_has_no_auto_clean"));
        log.info("Dashboard: {} removed auto-clean for channel {}", actor.getId(), rawChannelId.strip());
        return list(guild);
    }
}
