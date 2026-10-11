package com.younglings.bot.internal;

import com.younglings.bot.automod.AutomodRepository;
import com.younglings.bot.automod.AutomodRepository.Config;
import com.younglings.bot.automod.AutomodRules;
import com.younglings.bot.automod.AutomodRules.Rule;
import com.younglings.bot.automod.AutomodRules.RuleConfig;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's automod settings for the website: {@code GET automod} and {@code PUT automod}. Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class AutomodAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AutomodAdminApi.class);
    private static final int MAX_EXEMPT = 25;

    private final AutomodRepository repository;

    public AutomodAdminApi(AutomodRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("automod")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        Config c = repository.get(guild.getIdLong());
        DataArray rules = DataArray.empty();
        for (Rule rule : Rule.values()) {
            RuleConfig rc = c.rules().getOrDefault(rule, AutomodRules.defaults(rule));
            DataArray words = DataArray.empty();
            rc.words().forEach(words::add);
            rules.add(DataObject.empty().put("key", rule.name()).put("label", rule.label()).put("enabled", rc.enabled()).put("warn", rc.warn())
                    .put("limit", rc.limit()).put("seconds", rc.seconds()).put("words", words));
        }
        DataArray roles = DataArray.empty();
        c.exemptRoles().forEach(id -> roles.add(Long.toString(id)));
        DataArray channels = DataArray.empty();
        c.exemptChannels().forEach(id -> channels.add(Long.toString(id)));
        return DataObject.empty().put("enabled", c.enabled()).put("rules", rules).put("exemptRoles", roles).put("exemptChannels", channels)
                .put("canDelete", guild.getSelfMember().hasPermission(Permission.MESSAGE_MANAGE)).put("maxWords", AutomodRules.MAX_WORDS).put("maxDomains", AutomodRules.MAX_DOMAINS);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        Map<Rule, RuleConfig> rules = new EnumMap<>(Rule.class);
        DataArray given = body.hasKey("rules") && !body.isNull("rules") ? body.getArray("rules") : DataArray.empty();
        for (Rule rule : Rule.values()) rules.put(rule, repository.get(guild.getIdLong()).rules().getOrDefault(rule, AutomodRules.defaults(rule)));
        for (int i = 0; i < given.length(); i++) {
            DataObject item = given.getObject(i);
            Rule rule;
            try {
                rule = Rule.valueOf(item.getString("key", "").strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("One of the rules wasn't recognised.");
                continue;
            }
            List<String> words = new ArrayList<>();
            if (item.hasKey("words") && !item.isNull("words")) {
                DataArray array = item.getArray("words");
                for (int w = 0; w < array.length(); w++) words.addAll(AutomodRules.cleanList(array.getString(w)));
            }
            RuleConfig rc = new RuleConfig(item.getBoolean("enabled", false), item.getBoolean("warn", false), item.getInt("limit", 0), item.getInt("seconds", 0), new ArrayList<>(new LinkedHashSet<>(words)));
            problems.addAll(AutomodRules.problems(rule, rc));
            rules.put(rule, rc);
        }
        Set<Long> roles = ids(body, "exemptRoles", problems, "role", id -> guild.getRoleById(id) != null);
        Set<Long> channels = ids(body, "exemptChannels", problems, "channel", id -> guild.getGuildChannelById(id) != null);
        boolean enabled = body.getBoolean("enabled", false);
        if (enabled && !guild.getSelfMember().hasPermission(Permission.MESSAGE_MANAGE)) problems.add("JonnyBot needs the Manage Messages permission to delete messages.");
        if (enabled && rules.values().stream().noneMatch(RuleConfig::enabled)) problems.add("Switch at least one rule on, or turn automod off.");
        if (!problems.isEmpty()) throw new ApiError(400, "Automod can't be saved yet.", problems.stream().distinct().toList());
        repository.save(new Config(guild.getIdLong(), enabled, roles, channels, rules));
        log.info("Dashboard: {} saved the automod settings", actor.getId());
        return get(guild);
    }

    private static Set<Long> ids(DataObject body, String key, List<String> problems, String what, java.util.function.LongPredicate exists) {
        Set<Long> out = new LinkedHashSet<>();
        if (!body.hasKey(key) || body.isNull(key)) return out;
        DataArray array = body.getArray(key);
        if (array.length() > MAX_EXEMPT) problems.add("At most " + MAX_EXEMPT + " exempt " + what + "s.");
        for (int i = 0; i < Math.min(array.length(), MAX_EXEMPT); i++) {
            String raw = array.getString(i).strip();
            if (!raw.matches("\\d{1,20}") || !exists.test(Long.parseLong(raw))) problems.add("An exempt " + what + " isn't in this server.");
            else out.add(Long.parseLong(raw));
        }
        return out;
    }
}
