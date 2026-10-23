package com.younglings.bot.internal;

import com.younglings.bot.autodelete.AutoDeleteRepository;
import com.younglings.bot.autodelete.AutoDeleteRule;
import com.younglings.bot.autodelete.AutoDeleteRule.Filter;
import com.younglings.bot.i18n.Text;
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
                    : "The bot needs the View Channel and Manage Messages permissions in #" + channel.getName() + ".";
            DataArray filters = DataArray.empty();
            for (Filter f : Filter.values()) if (rule.filters().contains(f)) filters.add(f.name());
            rules.add(DataObject.empty().put("channelId", Long.toString(rule.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("filters", filters).put("words", DataArray.fromCollection(rule.words())).put("roleMode", rule.roleMode().name())
                    .put("roleIds", DataArray.fromCollection(rule.roleIds().stream().map(String::valueOf).toList())).put("delaySeconds", rule.delaySeconds())
                    .put("problem", problem));
        }
        DataArray available = DataArray.empty();
        for (Filter f : Filter.values()) available.add(DataObject.empty().put("key", f.name()).put("label", f.label()));
        return DataObject.empty().put("rules", rules).put("available", available).put("maxRules", AutoDeleteRule.MAX_RULES_PER_GUILD)
                .put("maxDelaySeconds", AutoDeleteRule.MAX_DELAY_SECONDS).put("maxWords", AutoDeleteRule.MAX_WORDS).put("minWordLength", AutoDeleteRule.MIN_WORD_LENGTH)
                .put("maxRoles", AutoDeleteRule.MAX_ROLES);
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
                problems.add("The bot needs the View Channel and Manage Messages permissions in #" + channel.getName() + " to delete messages there.");
            }
        }
        Set<Filter> filters = EnumSet.noneOf(Filter.class);
        DataArray given = body.hasKey("filters") && !body.isNull("filters") ? body.getArray("filters") : DataArray.empty();
        for (int i = 0; i < given.length(); i++) {
            try {
                filters.add(Filter.valueOf(given.getString(i).strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                problems.add("One of the filters isn't one the bot knows.");
            }
        }
        List<String> words = new ArrayList<>();
        if (body.hasKey("words") && !body.isNull("words")) {
            DataArray wordInput = body.getArray("words");
            List<String> wordList = new ArrayList<>();
            for (int i = 0; i < wordInput.length(); i++) wordList.add(wordInput.getString(i, ""));
            words = AutoDeleteRule.cleanWords(wordList);
            if (words.size() > AutoDeleteRule.MAX_WORDS) problems.add("At most " + AutoDeleteRule.MAX_WORDS + " words.");
            for (String word : words) {
                String problem = AutoDeleteRule.wordProblem(word);
                if (problem != null) problems.add(problem);
            }
        }
        if (!words.isEmpty()) filters.add(Filter.TEXT);
        else if (filters.contains(Filter.TEXT)) problems.add("Add at least one word to delete messages containing certain words.");

        AutoDeleteRule.RoleMode roleMode = AutoDeleteRule.RoleMode.EVERYONE;
        if (body.hasKey("roleMode") && !body.isNull("roleMode")) {
            try {
                roleMode = AutoDeleteRule.RoleMode.valueOf(body.getString("roleMode", "").strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("Choose who the rule is for: everyone, only people with a role, or everyone except them.");
            }
        }
        Set<Long> roleIds = new java.util.LinkedHashSet<>();
        if (body.hasKey("roleIds") && !body.isNull("roleIds")) {
            DataArray roleInput = body.getArray("roleIds");
            for (int i = 0; i < roleInput.length(); i++) {
                String id = roleInput.getString(i, "").strip();
                if (!id.matches("\\d{1,20}") || guild.getRoleById(Long.parseLong(id)) == null) problems.add("A chosen role doesn't exist in this server.");
                else roleIds.add(Long.parseLong(id));
            }
            if (roleIds.size() > AutoDeleteRule.MAX_ROLES) problems.add("At most " + AutoDeleteRule.MAX_ROLES + " roles.");
        }
        if (roleMode != AutoDeleteRule.RoleMode.EVERYONE && roleIds.isEmpty()) problems.add("Choose at least one role, or make the rule for everyone.");
        if (roleMode == AutoDeleteRule.RoleMode.EVERYONE) roleIds.clear();

        int delay = body.hasKey("delaySeconds") && !body.isNull("delaySeconds") ? body.getInt("delaySeconds", -1) : 0;
        if (delay < 0 || delay > AutoDeleteRule.MAX_DELAY_SECONDS) problems.add("The delay can be 0 to " + AutoDeleteRule.MAX_DELAY_SECONDS + " seconds.");

        if (filters.isEmpty() && problems.isEmpty()) problems.add("Choose at least one thing to delete.");
        if (channel != null) {
            final long channelId = channel.getIdLong();
            var existing = repository.forGuild(guild.getIdLong());
            if (existing.size() >= AutoDeleteRule.MAX_RULES_PER_GUILD && existing.stream().noneMatch(r -> r.channelId() == channelId)) {
                problems.add("A server can have rules for at most " + AutoDeleteRule.MAX_RULES_PER_GUILD + " channels.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_rule_can_t_be_saved"), problems.stream().distinct().toList());

        repository.save(new AutoDeleteRule(guild.getIdLong(), channel.getIdLong(), filters, words, roleMode, roleIds, delay));
        log.info("Dashboard: {} set auto-delete for #{} to {}", actor.getId(), channel.getName(), filters);
        return list(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawChannelId) {
        if (rawChannelId == null || !rawChannelId.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_channel"));
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawChannelId.strip()))) throw new ApiError(404, Text.of(guild, "dashboard.that_channel_has_no_auto_delete"));
        log.info("Dashboard: {} removed auto-delete for channel {}", actor.getId(), rawChannelId.strip());
        return list(guild);
    }
}
