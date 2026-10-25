package com.younglings.bot.internal;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordJobHandler;
import com.younglings.bot.discord.jobs.DiscordJobs;
import com.younglings.bot.embedder.EmbedderRepository;
import com.younglings.bot.embedder.EmbedderRepository.Stored;
import com.younglings.bot.embedder.EmbedderService;
import com.younglings.bot.embedder.EmbedderService.Author;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/**
 * The Discord side of the Message embedder ({@link EmbedderAdminApi}), run by the outbox (T-456) so the page never waits on Discord:
 * <ul>
 *   <li>{@code embedder.post}: posts a stored draft (a clone is a new draft posted the same way) and marks it posted. Not idempotent.</li>
 *   <li>{@code embedder.edit}: puts the stored message's current text on the posted message. Idempotent.</li>
 *   <li>{@code embedder.delete}: deletes a posted message whose entry is already gone. Idempotent.</li>
 * </ul>
 * The website checked the channel and the admin's own permissions when it queued the job; this checks only what may have changed since.
 */
@BService
public class EmbedderJobs implements DiscordJobHandler {
    static final String POST = "embedder.post";
    static final String EDIT = "embedder.edit";
    static final String DELETE = "embedder.delete";

    private final EmbedderRepository repository;
    private final EmbedderService service;

    public EmbedderJobs(EmbedderRepository repository, EmbedderService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public String kind() {
        return POST;
    }

    @Override
    public List<String> kinds() {
        return List.of(POST, EDIT, DELETE);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return !POST.equals(kind);
    }

    static DataObject postPayload(long entryId, long channelId, Author author) {
        return DataObject.empty().put("entryId", Long.toString(entryId)).put("channelId", Long.toString(channelId)).put("authorName", author.name()).put("authorAvatar", author.avatarUrl());
    }

    static DataObject editPayload(long entryId, Author author) {
        return DataObject.empty().put("entryId", Long.toString(entryId)).put("authorName", author.name()).put("authorAvatar", author.avatarUrl());
    }

    static DataObject deletePayload(long channelId, long messageId) {
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("messageId", Long.toString(messageId));
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        return switch (job.kind()) {
            case POST -> post(guild, p);
            case EDIT -> edit(guild, p);
            case DELETE -> {
                service.delete(guild, p.getLong("channelId"), p.getLong("messageId"));
                yield DataObject.empty().put("deleted", true);
            }
            default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
        };
    }

    private DataObject post(Guild guild, DataObject p) {
        Stored stored = entry(guild, p);
        if (stored.posted()) throw new DiscordJobs.Refused("That message was already posted. Save changes to edit it, or clone it.");
        long channelId = p.getLong("channelId");
        long messageId = service.post(guild, author(p), WelcomeAdminApi.parse(guild.getIdLong(), DataObject.fromJson(stored.bodyJson())), channelId);
        repository.markPosted(guild.getIdLong(), stored.id(), channelId, messageId);
        return DataObject.empty().put("entryId", Long.toString(stored.id())).put("channelId", Long.toString(channelId)).put("messageId", Long.toString(messageId))
                .put("messageUrl", "https://discord.com/channels/" + guild.getId() + "/" + channelId + "/" + messageId);
    }

    private DataObject edit(Guild guild, DataObject p) {
        Stored stored = entry(guild, p);
        if (!stored.posted()) throw new DiscordJobs.Refused("That message isn't posted any more, so there is nothing to edit.");
        service.edit(guild, author(p), WelcomeAdminApi.parse(guild.getIdLong(), DataObject.fromJson(stored.bodyJson())), stored.channelId(), stored.messageId());
        return DataObject.empty().put("entryId", Long.toString(stored.id())).put("messageId", Long.toString(stored.messageId()));
    }

    private Stored entry(Guild guild, DataObject p) {
        Stored stored = repository.get(guild.getIdLong(), p.getLong("entryId"));
        if (stored == null) throw new DiscordJobs.Refused("That message was deleted on the website before the bot got to it.");
        return stored;
    }

    private static Author author(DataObject p) {
        return new Author(p.getString("authorName", ""), p.isNull("authorAvatar") ? null : p.getString("authorAvatar", null));
    }
}
