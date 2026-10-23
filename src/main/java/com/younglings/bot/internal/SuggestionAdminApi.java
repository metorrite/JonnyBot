package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.suggestion.Suggestion;
import com.younglings.bot.suggestion.SuggestionRepository;
import com.younglings.bot.suggestion.SuggestionService;
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
 * A server's suggestions for the website: the channel they go to, the recent ones with their votes, a decision with an optional
 * note, and deleting one. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard
 * in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET suggestions}</li>
 *   <li>{@code PUT suggestions/settings} {@code {channelId}}</li>
 *   <li>{@code POST suggestions/{id}/decide} {@code {status, note}}</li>
 *   <li>{@code DELETE suggestions/{id}}</li>
 * </ul>
 */
@BService
public class SuggestionAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SuggestionAdminApi.class);

    private final SuggestionRepository repository;
    private final SuggestionService service;

    public SuggestionAdminApi(SuggestionRepository repository, SuggestionService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("suggestions")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        if (parts.length == 3 && parts[2].equals("decide")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return decide(guild, actor, parts[1], body.get());
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray suggestions = DataArray.empty();
        for (Suggestion s : repository.recent(guild.getIdLong(), 50)) {
            int[] counts = repository.counts(s.id());
            suggestions.add(DataObject.empty().put("id", Long.toString(s.id())).put("authorId", Long.toString(s.authorId()))
                    .put("authorName", name(guild, s.authorId())).put("content", s.content()).put("status", s.status().name())
                    .put("note", s.note()).put("decidedBy", s.decidedBy() == null ? null : Long.toString(s.decidedBy()))
                    .put("ups", counts[0]).put("downs", counts[1]).put("createdAt", s.createdAt().toString()));
        }
        var channel = repository.channel(guild.getIdLong());
        return DataObject.empty().put("channelId", channel.isPresent() ? Long.toString(channel.getAsLong()) : null).put("suggestions", suggestions)
                .put("maxNote", Suggestion.MAX_NOTE);
    }

    private static String name(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        return member == null ? null : member.getEffectiveName();
    }

    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        Long channelId = null;
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!raw.isEmpty()) {
            if (!raw.matches("\\d{1,20}")) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("The bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_suggestions_channel_can_t_be"), problems);
        repository.setChannel(guild.getIdLong(), channelId);
        log.info("Dashboard: {} set the suggestions channel to {}", actor.getId(), channelId);
        return list(guild);
    }

    DataObject decide(Guild guild, Member actor, String rawId, DataObject body) {
        Suggestion s = find(guild, rawId);
        Suggestion.Status status;
        try {
            status = Suggestion.Status.valueOf(body.getString("status", "").strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guild, "dashboard.choose_a_decision_pending_approved_denied"));
        }
        String note = body.isNull("note") ? "" : body.getString("note", "").strip();
        if (note.length() > Suggestion.MAX_NOTE) throw new ApiError(400, Text.of(guild, "dashboard.keep_the_response_to_characters_or", Suggestion.MAX_NOTE));
        service.decide(guild, s, status, note, actor.getIdLong());
        log.info("Dashboard: {} marked suggestion {} as {}", actor.getId(), s.id(), status);
        return list(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        Suggestion s = find(guild, rawId);
        service.remove(guild, s);
        log.info("Dashboard: {} deleted suggestion {}", actor.getId(), s.id());
        return list(guild);
    }

    private Suggestion find(Guild guild, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_suggestion"));
        return repository.get(guild.getIdLong(), Long.parseLong(rawId.strip())).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_suggestion_doesn_t_exist")));
    }
}
