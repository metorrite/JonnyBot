package com.younglings.bot.internal;

import com.younglings.bot.feed.FeedKind;
import com.younglings.bot.feed.FeedRepository;
import com.younglings.bot.feed.FeedService;
import com.younglings.bot.feed.FeedSubscription;
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
 * The YouTube and Reddit feeds a server follows, for the website. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET feeds}</li>
 *   <li>{@code PUT feeds} {@code {id?, kind, source, flair, channelId, template, enabled}} (no id adds a feed, an id changes it)</li>
 *   <li>{@code DELETE feeds/{id}}</li>
 * </ul>
 */
@BService
public class FeedAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(FeedAdminApi.class);

    private final FeedRepository repository;

    public FeedAdminApi(FeedRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("feeds")) return null;
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
        DataArray feeds = DataArray.empty();
        for (FeedSubscription sub : repository.forGuild(guild.getIdLong())) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, sub.channelId());
            String problem = channel == null ? "This channel no longer exists." : FeedService.problemWith(channel);
            feeds.add(DataObject.empty().put("id", Long.toString(sub.id())).put("kind", sub.kind().name()).put("source", sub.source())
                    .put("flair", sub.flair()).put("channelId", Long.toString(sub.channelId())).put("channelName", channel == null ? null : channel.getName())
                    .put("template", sub.template()).put("enabled", sub.enabled()).put("problem", problem));
        }
        DataObject defaults = DataObject.empty();
        for (FeedKind kind : FeedKind.values()) defaults.put(kind.name(), kind.defaultTemplate());
        return DataObject.empty().put("feeds", feeds).put("maxPerKind", FeedSubscription.MAX_PER_KIND_PER_GUILD)
                .put("maxTemplate", FeedSubscription.MAX_TEMPLATE_LENGTH).put("maxFlair", FeedSubscription.MAX_FLAIR_LENGTH).put("defaultTemplates", defaults);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        long id = 0;
        FeedSubscription before = null;
        String rawId = body.isNull("id") ? "" : body.getString("id", "").strip();
        if (!rawId.isEmpty()) {
            if (!rawId.matches("\\d{1,18}")) throw new ApiError(400, "That isn't a valid feed.");
            id = Long.parseLong(rawId);
            before = repository.find(guild.getIdLong(), id).orElseThrow(() -> new ApiError(404, "That feed no longer exists."));
        }
        FeedKind kind = before != null ? before.kind() : FeedKind.parse(body.isNull("kind") ? "" : body.getString("kind", ""));
        if (kind == null) throw new ApiError(400, "Choose YouTube or Reddit.");

        String source = FeedSubscription.normalizeSource(kind, body.isNull("source") ? "" : body.getString("source", ""));
        if (source == null) {
            problems.add(kind == FeedKind.YOUTUBE
                    ? "Paste the channel's ID or its /channel/UC... link (the 24-character ID starting UC). A @handle link doesn't contain it."
                    : "Enter a subreddit name, like runescape or r/runescape.");
        }
        String flair = kind == FeedKind.REDDIT && !body.isNull("flair") ? body.getString("flair", "").strip() : "";
        if (flair.length() > FeedSubscription.MAX_FLAIR_LENGTH) problems.add("The flair can be at most " + FeedSubscription.MAX_FLAIR_LENGTH + " characters.");
        String template = body.isNull("template") ? "" : body.getString("template", "").strip();
        if (template.length() > FeedSubscription.MAX_TEMPLATE_LENGTH) problems.add("The message can be at most " + FeedSubscription.MAX_TEMPLATE_LENGTH + " characters.");

        String rawChannel = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        GuildMessageChannel channel = null;
        if (!rawChannel.matches("\\d{1,20}")) {
            problems.add("Choose the channel to post in.");
        } else {
            channel = guild.getChannelById(GuildMessageChannel.class, Long.parseLong(rawChannel));
            if (channel == null) problems.add("That channel doesn't exist in this server.");
            else {
                String permission = FeedService.problemWith(channel);
                if (permission != null) problems.add(permission);
            }
        }
        boolean enabled = body.getBoolean("enabled", true);
        if (before == null) {
            long count = repository.forGuild(guild.getIdLong()).stream().filter(s -> s.kind() == kind).count();
            if (count >= FeedSubscription.MAX_PER_KIND_PER_GUILD) {
                problems.add("A server can follow at most " + FeedSubscription.MAX_PER_KIND_PER_GUILD + " " + kind.label() + " feeds.");
            }
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That feed can't be saved yet.", problems);

        FeedSubscription sub = new FeedSubscription(id, guild.getIdLong(), kind, source, flair, channel.getIdLong(), template, enabled);
        // following something else (or a different flair) starts over, so the old feed's posts are never mixed in
        boolean restart = before != null && (!before.source().equals(source) || !before.flair().equals(flair));
        repository.save(sub, restart);
        log.info("Dashboard: {} {} the {} feed {} in #{}", actor.getId(), before == null ? "added" : "changed", kind.label(), source, channel.getName());
        return list(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,18}")) throw new ApiError(400, "That isn't a valid feed.");
        if (!repository.delete(guild.getIdLong(), Long.parseLong(rawId.strip()))) throw new ApiError(404, "That feed no longer exists.");
        log.info("Dashboard: {} stopped following feed {}", actor.getId(), rawId.strip());
        return list(guild);
    }
}
