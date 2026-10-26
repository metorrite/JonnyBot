package com.younglings.bot.internal;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordOutbox;
import com.younglings.bot.embedder.EmbedderRepository;
import com.younglings.bot.embedder.EmbedderRepository.Stored;
import com.younglings.bot.embedder.EmbedderService;
import com.younglings.bot.embedder.EmbedderService.Author;
import com.younglings.bot.i18n.Text;
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
 * the asker may use the dashboard here and audit-logs every write. Everything that reaches Discord is checked here and then queued as a
 * Discord job ({@link EmbedderJobs}, T-456): the answer comes at once with {@code job} ({@code {id, state}}) for the page to follow, and the
 * entry shows as posted once the job has posted it.
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
    private final DiscordOutbox outbox;

    public EmbedderAdminApi(EmbedderRepository repository, EmbedderService service, DiscordOutbox outbox) {
        this.repository = repository;
        this.service = service;
        this.outbox = outbox;
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
            throw new ApiError(400, Text.of(guild, "dashboard.this_server_already_has_saved_messages", EmbedderService.MAX_MESSAGES_PER_GUILD));
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
        DiscordJob job = null;
        if (apply) {
            WelcomeConfig before = config(guild, stored.bodyJson());
            WelcomeConfig after = config(guild, json);
            if (EmbedderService.changesKind(before.messageType(), after.messageType())) {
                throw new ApiError(400, "A posted message can't change between a Container and the other kinds. Clone it into a new message instead.");
            }
            checkedChannel(guild, actor, stored.channelId());
        }
        repository.saveBody(guild.getIdLong(), id, name, json);
        if (apply) {
            // the job reads the entry when it runs, so a later save that replaces this waiting one still puts the newest text on Discord
            job = outbox.enqueue(EmbedderJobs.EDIT, guild.getIdLong(), actor.getIdLong(), "embedder-edit:" + guild.getIdLong() + ":" + id,
                    EmbedderJobs.editPayload(id, Author.of(actor)));
            log.info("Dashboard: {} queued an edit of embedder message {} in place (message {}, job {})", actor.getId(), id, stored.messageId(), job.id());
        }
        return withJob(one(guild, id), job);
    }

    DataObject post(Guild guild, Member actor, long id, DataObject body) {
        Stored stored = require(guild, id);
        if (stored.posted()) throw new ApiError(400, Text.of(guild, "dashboard.that_message_is_already_posted_save"));
        long channelId = channelId(body);
        checked(guild, stored.bodyJson(), true);
        checkedChannel(guild, actor, channelId);
        // pressing Post twice before the first is done replaces the waiting job instead of posting two messages
        DiscordJob job = outbox.enqueue(EmbedderJobs.POST, guild.getIdLong(), actor.getIdLong(), "embedder-post:" + guild.getIdLong() + ":" + id,
                EmbedderJobs.postPayload(id, channelId, Author.of(actor)));
        log.info("Dashboard: {} queued embedder message {} for channel {} (job {})", actor.getId(), id, channelId, job.id());
        return withJob(one(guild, id), job);
    }

    /**
     * Keeps a copy as its own entry and queues posting it in one channel. One channel per call, so a clone can never flood a server. If Discord
     * refuses the post, the copy stays as a draft that can be posted again.
     */
    DataObject clone(Guild guild, Member actor, long id, DataObject body) {
        Stored stored = require(guild, id);
        if (repository.count(guild.getIdLong()) >= EmbedderService.MAX_MESSAGES_PER_GUILD) {
            throw new ApiError(400, Text.of(guild, "dashboard.this_server_already_has_saved_messages", EmbedderService.MAX_MESSAGES_PER_GUILD));
        }
        long channelId = channelId(body);
        checked(guild, stored.bodyJson(), true);
        checkedChannel(guild, actor, channelId);
        String copyName = stored.name().length() > MAX_NAME - 7 ? stored.name().substring(0, MAX_NAME - 7) : stored.name();
        long copy = repository.create(guild.getIdLong(), copyName + " (copy)", stored.bodyJson(), null, null, actor.getUser().getIdLong());
        DiscordJob job = outbox.enqueue(EmbedderJobs.POST, guild.getIdLong(), actor.getIdLong(), "embedder-post:" + guild.getIdLong() + ":" + copy,
                EmbedderJobs.postPayload(copy, channelId, Author.of(actor)));
        log.info("Dashboard: {} cloned embedder message {} as {} and queued it for channel {} (job {})", actor.getId(), id, copy, channelId, job.id());
        return withJob(one(guild, copy), job);
    }

    DataObject delete(Guild guild, Member actor, long id) {
        Stored stored = require(guild, id);
        DiscordJob job = null;
        if (stored.posted()) {
            boolean channelThere;
            try {
                channelThere = service.mayDeleteIn(guild, actor, stored.channelId());
            } catch (IllegalStateException e) {
                throw new ApiError(400, e.getMessage());
            }
            if (channelThere) {
                job = outbox.enqueue(EmbedderJobs.DELETE, guild.getIdLong(), actor.getIdLong(), null, EmbedderJobs.deletePayload(stored.channelId(), stored.messageId()));
            }
        }
        repository.delete(guild.getIdLong(), id);
        log.info("Dashboard: {} deleted embedder message {} (was posted: {}{})", actor.getId(), id, stored.posted(), job == null ? "" : ", job " + job.id());
        return withJob(DataObject.empty().put("deleted", true), job);
    }

    private void checkedChannel(Guild guild, Member actor, long channelId) {
        try {
            service.checkedChannel(guild, actor, channelId);
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    /** The answer, with the queued Discord job (T-456) for the page to follow when there is one. */
    private static DataObject withJob(DataObject answer, DiscordJob job) {
        return job == null ? answer : answer.put("job", job.ticket());
    }

    // ---------- reading the request ----------

    private Stored require(Guild guild, long id) {
        Stored stored = repository.get(guild.getIdLong(), id);
        if (stored == null) throw new ApiError(404, Text.of(guild, "dashboard.that_message_doesn_t_exist"));
        return stored;
    }

    private static long parseId(String raw) {
        if (!raw.matches("\\d{1,18}")) throw new ApiError(404, Text.en("dashboard.that_message_doesn_t_exist"));
        return Long.parseLong(raw);
    }

    private static long channelId(DataObject body) {
        String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.en("dashboard.choose_a_channel"));
        return Long.parseLong(raw);
    }

    private static String name(DataObject body) {
        String name = body.getString("name", "").strip();
        if (name.isEmpty()) throw new ApiError(400, Text.en("dashboard.give_the_message_a_name_so"));
        if (name.length() > MAX_NAME) throw new ApiError(400, Text.en("dashboard.the_name_can_be_at_most", MAX_NAME));
        return name;
    }

    /**
     * The message part of the request, normalised to the stored shape. A draft may be unfinished, so only a message that is about to go to
     * Discord ({@code strict}) must pass the editor's checks; a draft still has to parse.
     */
    private String bodyJson(Guild guild, DataObject body, boolean strict) {
        if (body.isNull("message")) throw new ApiError(400, Text.of(guild, "dashboard.there_no_message_to_save"));
        WelcomeConfig config = WelcomeAdminApi.parse(guild.getIdLong(), body.getObject("message"));
        if (strict) problems(guild, config);
        String json = WelcomeAdminApi.toJson(guild, config).put("channelId", null).put("channelName", null).put("enabled", false).put("alsoDm", false).toString();
        if (json.length() > MAX_BODY_JSON) throw new ApiError(400, Text.of(guild, "dashboard.that_message_is_too_big_to"));
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
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_message_can_t_be_sent"), problems);
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
