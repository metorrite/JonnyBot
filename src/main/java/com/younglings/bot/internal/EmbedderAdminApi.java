package com.younglings.bot.internal;

import com.younglings.bot.embedder.EmbedderRepository;
import com.younglings.bot.embedder.EmbedderRepository.Stored;
import com.younglings.bot.embedder.EmbedderService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeValidator;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Supplier;

/**
 * The website's Message embedder: messages composed with the shared message editor that can be kept as drafts, posted, edited in place
 * after posting, deleted and cloned into other channels. Nothing here runs by itself: every post, edit, delete and clone is one admin
 * action, judged against that admin's own permissions in the channel. Reached through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard here and audit-logs every write.
 * <ul>
 *     <li>{@code GET embedder/messages}: every stored message, newest first</li>
 *     <li>{@code POST embedder/messages}: save a new draft ({@code name}, {@code message})</li>
 *     <li>{@code GET|PUT embedder/messages/{id}}: read, or save changes ({@code apply} also updates the posted message in Discord)</li>
 *     <li>{@code POST embedder/messages/{id}/post}: post a draft in {@code channelId}</li>
 *     <li>{@code POST embedder/messages/{id}/clone}: post a copy in {@code channelId}</li>
 *     <li>{@code POST embedder/messages/{id}/delete}: delete the Discord message (if posted) and the entry</li>
 * </ul>
 */
@BService
public class EmbedderAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(EmbedderAdminApi.class);
    private static final int MAX_NAME = 80;
    private static final int MAX_BODY_JSON = 60_000;

    private final EmbedderRepository repository;
    private final EmbedderService service;

    public EmbedderAdminApi(EmbedderRepository repository, EmbedderService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || !parts[0].equals("embedder") || !parts[1].equals("messages")) return null;

        if (parts.length == 2) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return createDraft(guild, actor, body.get());
        }
        long id = parseId(parts[2]);
        if (parts.length == 3) {
            if (method.equals("GET")) return one(guild, id);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, id, body.get());
        }
        if (parts.length == 4) {
            AdminRouteExtension.requireMethod(method, "POST");
            return switch (parts[3]) {
                case "post" -> post(guild, actor, id, body.get());
                case "clone" -> clone(guild, actor, id, body.get());
                case "delete" -> delete(guild, actor, id);
                default -> null;
            };
        }
        return null;
    }

    // ---------- reading ----------

    DataObject list(Guild guild) {
        DataArray messages = DataArray.empty();
        for (Stored stored : repository.list(guild.getIdLong())) messages.add(toJson(guild, stored));
        return DataObject.empty().put("messages", messages).put("limit", EmbedderService.MAX_MESSAGES_PER_GUILD);
    }

    DataObject one(Guild guild, long id) {
        return toJson(guild, require(guild, id));
    }

    // ---------- writing ----------

    DataObject createDraft(Guild guild, Member actor, DataObject body) {
        if (repository.count(guild.getIdLong()) >= EmbedderService.MAX_MESSAGES_PER_GUILD) {
            throw new ApiError(400, "This server already has " + EmbedderService.MAX_MESSAGES_PER_GUILD + " saved messages. Delete some first.");
        }
        String name = name(body);
        String json = bodyJson(guild, body, false);
        long id = repository.create(guild.getIdLong(), name, json, null, null, actor.getUser().getIdLong());
        log.info("Dashboard: {} saved embedder draft {} '{}'", actor.getId(), id, name);
        return one(guild, id);
    }

    DataObject save(Guild guild, Member actor, long id, DataObject body) {
        Stored stored = require(guild, id);
        String name = name(body);
        boolean apply = body.getBoolean("apply", false) && stored.posted();
        String json = bodyJson(guild, body, apply);
        if (apply) {
            WelcomeConfig before = config(guild, stored.bodyJson());
            WelcomeConfig after = config(guild, json);
            try {
                service.edit(guild, actor, before, after, stored.channelId(), stored.messageId());
            } catch (IllegalStateException e) {
                throw new ApiError(400, e.getMessage());
            }
            log.info("Dashboard: {} edited embedder message {} in place (message {})", actor.getId(), id, stored.messageId());
        }
        repository.saveBody(guild.getIdLong(), id, name, json);
        return one(guild, id);
    }

    DataObject post(Guild guild, Member actor, long id, DataObject body) {
        Stored stored = require(guild, id);
        if (stored.posted()) throw new ApiError(400, "That message is already posted. Save with the update option to change it, or clone it to post it again.");
        long channelId = channelId(body);
        WelcomeConfig config = checked(guild, stored.bodyJson(), true);
        long messageId;
        try {
            messageId = service.post(guild, actor, config, channelId);
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
        repository.markPosted(guild.getIdLong(), id, channelId, messageId);
        log.info("Dashboard: {} posted embedder message {} in channel {}", actor.getId(), id, channelId);
        return one(guild, id);
    }

    /** Posts a copy in one channel and keeps it as its own entry. One channel per call, so a clone can never flood a server. */
    DataObject clone(Guild guild, Member actor, long id, DataObject body) {
        Stored stored = require(guild, id);
        if (repository.count(guild.getIdLong()) >= EmbedderService.MAX_MESSAGES_PER_GUILD) {
            throw new ApiError(400, "This server already has " + EmbedderService.MAX_MESSAGES_PER_GUILD + " saved messages. Delete some first.");
        }
        long channelId = channelId(body);
        WelcomeConfig config = checked(guild, stored.bodyJson(), true);
        long messageId;
        try {
            messageId = service.post(guild, actor, config, channelId);
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
        String copyName = stored.name().length() > MAX_NAME - 7 ? stored.name().substring(0, MAX_NAME - 7) : stored.name();
        long copy = repository.create(guild.getIdLong(), copyName + " (copy)", stored.bodyJson(), channelId, messageId, actor.getUser().getIdLong());
        log.info("Dashboard: {} cloned embedder message {} into channel {} as {}", actor.getId(), id, channelId, copy);
        return one(guild, copy);
    }

    DataObject delete(Guild guild, Member actor, long id) {
        Stored stored = require(guild, id);
        if (stored.posted()) {
            try {
                service.delete(guild, actor, stored.channelId(), stored.messageId());
            } catch (IllegalStateException e) {
                throw new ApiError(400, e.getMessage());
            }
        }
        repository.delete(guild.getIdLong(), id);
        log.info("Dashboard: {} deleted embedder message {} (was posted: {})", actor.getId(), id, stored.posted());
        return DataObject.empty().put("deleted", true);
    }

    // ---------- reading the request ----------

    private Stored require(Guild guild, long id) {
        Stored stored = repository.get(guild.getIdLong(), id);
        if (stored == null) throw new ApiError(404, "That message doesn't exist.");
        return stored;
    }

    private static long parseId(String raw) {
        if (!raw.matches("\\d{1,18}")) throw new ApiError(404, "That message doesn't exist.");
        return Long.parseLong(raw);
    }

    private static long channelId(DataObject body) {
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "Choose a channel.");
        return Long.parseLong(raw);
    }

    private static String name(DataObject body) {
        String name = body.getString("name", "").strip();
        if (name.isEmpty()) throw new ApiError(400, "Give the message a name so you can find it again.");
        if (name.length() > MAX_NAME) throw new ApiError(400, "The name can be at most " + MAX_NAME + " characters.");
        return name;
    }

    /**
     * The message part of the request, normalised to the stored shape. A draft may be unfinished, so only a message that is about to go to
     * Discord ({@code strict}) must pass the editor's checks; a draft still has to parse.
     */
    private String bodyJson(Guild guild, DataObject body, boolean strict) {
        if (body.isNull("message")) throw new ApiError(400, "There's no message to save.");
        WelcomeConfig config = WelcomeAdminApi.parse(guild.getIdLong(), body.getObject("message"));
        if (strict) problems(guild, config);
        String json = WelcomeAdminApi.toJson(guild, config).put("channelId", null).put("channelName", null).put("enabled", false).put("alsoDm", false).toString();
        if (json.length() > MAX_BODY_JSON) throw new ApiError(400, "That message is too big to store.");
        return json;
    }

    private WelcomeConfig config(Guild guild, String json) {
        return WelcomeAdminApi.parse(guild.getIdLong(), DataObject.fromJson(json));
    }

    /** The stored message as a config, refused with the editor's reasons if it isn't ready to send. */
    private WelcomeConfig checked(Guild guild, String json, boolean strict) {
        WelcomeConfig config = config(guild, json);
        if (strict) problems(guild, config);
        return config;
    }

    private static void problems(Guild guild, WelcomeConfig config) {
        List<String> problems = WelcomeValidator.validate(config);
        if (!problems.isEmpty()) throw new ApiError(400, "That message can't be sent yet.", problems);
    }

    private static DataObject toJson(Guild guild, Stored stored) {
        String channelName = null;
        if (stored.channelId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, stored.channelId());
            channelName = channel == null ? null : channel.getName();
        }
        return DataObject.empty()
                .put("id", Long.toString(stored.id()))
                .put("name", stored.name())
                .put("status", stored.posted() ? "POSTED" : "DRAFT")
                .put("channelId", stored.channelId() == null ? null : Long.toString(stored.channelId()))
                .put("channelName", channelName)
                .put("messageId", stored.messageId() == null ? null : Long.toString(stored.messageId()))
                .put("messageUrl", stored.posted() && stored.channelId() != null
                        ? "https://discord.com/channels/" + guild.getId() + "/" + stored.channelId() + "/" + stored.messageId() : null)
                .put("updatedAt", stored.updatedAt() == null ? null : stored.updatedAt().toInstant().toString())
                .put("postedAt", stored.postedAt() == null ? null : stored.postedAt().toInstant().toString())
                .put("message", DataObject.fromJson(stored.bodyJson()));
    }
}
