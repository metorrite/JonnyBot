package com.younglings.bot.internal;

import com.younglings.bot.config.PrivilegedIntents;
import com.younglings.bot.highlight.HighlightConfig;
import com.younglings.bot.highlight.HighlightRepository;
import com.younglings.bot.highlight.HighlightRepository.Keyword;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's Highlights settings for the website. A save is checked whole before anything is stored. Reached only through {@link TicketAdminApi},
 * which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class HighlightAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(HighlightAdminApi.class);

    private final HighlightRepository repository;

    public HighlightAdminApi(HighlightRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("highlights")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        HighlightConfig c = repository.config(guild.getIdLong());
        List<Keyword> all = repository.keywords(guild.getIdLong());
        Set<Long> members = new LinkedHashSet<>();
        all.forEach(k -> members.add(k.userId()));
        return DataObject.empty().put("enabled", c.enabled()).put("maxKeywords", c.maxKeywords()).put("cooldownSeconds", c.cooldownSeconds())
                .put("excludedChannels", ids(c.excludedChannels())).put("allowedRoles", ids(c.allowedRoles()))
                .put("memberCount", members.size()).put("wordCount", all.size()).put("messageContent", PrivilegedIntents.messageContent())
                .put("limits", DataObject.empty().put("maxKeywords", HighlightConfig.MAX_KEYWORDS_LIMIT).put("minCooldown", HighlightConfig.MIN_COOLDOWN)
                        .put("maxCooldown", HighlightConfig.MAX_COOLDOWN).put("maxList", HighlightConfig.MAX_LIST));
    }

    private static DataArray ids(Set<Long> ids) {
        return DataArray.fromCollection(ids.stream().map(String::valueOf).toList());
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        HighlightConfig current = repository.config(guild.getIdLong());
        List<String> problems = new ArrayList<>();

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        int max = body.hasKey("maxKeywords") ? body.getInt("maxKeywords", -1) : current.maxKeywords();
        if (max < 1 || max > HighlightConfig.MAX_KEYWORDS_LIMIT) problems.add("Words per member must be between 1 and " + HighlightConfig.MAX_KEYWORDS_LIMIT + ".");
        int cooldown = body.hasKey("cooldownSeconds") ? body.getInt("cooldownSeconds", -1) : current.cooldownSeconds();
        if (cooldown < HighlightConfig.MIN_COOLDOWN || cooldown > HighlightConfig.MAX_COOLDOWN) problems.add("The wait between messages must be between " + HighlightConfig.MIN_COOLDOWN + " seconds and 24 hours.");

        Set<Long> excluded = new LinkedHashSet<>(current.excludedChannels());
        if (body.hasKey("excludedChannels")) {
            excluded.clear();
            DataArray input = body.getArray("excludedChannels");
            if (input.length() > HighlightConfig.MAX_LIST) problems.add("At most " + HighlightConfig.MAX_LIST + " channels can be left out.");
            for (int i = 0; i < input.length() && i < HighlightConfig.MAX_LIST; i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getGuildChannelById(Long.parseLong(raw)) == null) problems.add("A channel to leave out doesn't exist in this server.");
                else excluded.add(Long.parseLong(raw));
            }
        }
        Set<Long> roles = new LinkedHashSet<>(current.allowedRoles());
        if (body.hasKey("allowedRoles")) {
            roles.clear();
            DataArray input = body.getArray("allowedRoles");
            if (input.length() > HighlightConfig.MAX_LIST) problems.add("At most " + HighlightConfig.MAX_LIST + " roles can be listed.");
            for (int i = 0; i < input.length() && i < HighlightConfig.MAX_LIST; i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getRoleById(Long.parseLong(raw)) == null) problems.add("A role doesn't exist in this server.");
                else roles.add(Long.parseLong(raw));
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, "Highlights can't be saved yet.", problems);

        repository.saveConfig(new HighlightConfig(guild.getIdLong(), enabled, max, cooldown, excluded, roles));
        log.info("Dashboard: {} saved Highlights ({}, {} words each, {}s wait)", actor.getId(), enabled ? "on" : "off", max, cooldown);
        return get(guild);
    }
}
