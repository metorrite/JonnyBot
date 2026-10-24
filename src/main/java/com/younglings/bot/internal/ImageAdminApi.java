package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.upload.ImageUploadService;
import com.younglings.bot.upload.UploadedImageRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;

/**
 * Uploading and removing the pictures a server puts in its messages. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard in this server and audit-logs every write. The picture arrives base64-encoded in JSON; what it
 * really is gets decided from its bytes, never from what the browser said.
 */
@BService
public class ImageAdminApi {
    private static final Logger log = LoggerFactory.getLogger(ImageAdminApi.class);

    private final ImageUploadService images;

    public ImageAdminApi(ImageUploadService images) {
        this.images = images;
    }

    DataObject upload(Guild guild, Member actor, String scope, String slot, DataObject body) {
        return upload(guild.getIdLong(), actor.getIdLong(), scope, slot, body);
    }

    /** {@link #upload(Guild, Member, String, String, DataObject)} by server id (a bot-less site's logo and banner, {@link BotlessAdminApi}). */
    DataObject upload(long guildId, long actorId, String scope, String slot, DataObject body) {
        if (!ImageUploadService.isKnownSlot(scope, slot)) throw new ApiError(404, Text.of(guildId, "dashboard.not_found"));
        byte[] data;
        try {
            data = Base64.getDecoder().decode(body.getString("data", ""));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guildId, "dashboard.that_file_couldn_t_be_read"));
        }

        try {
            UploadedImageRepository.Saved saved = images.save(guildId, scope, slot, data);
            log.info("Dashboard: {} uploaded a picture for {}/{} ({} bytes)", actorId, scope, slot, data.length);
            return DataObject.empty().put("token", saved.token()).put("version", saved.version());
        } catch (ImageUploadService.InvalidImageException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    DataObject remove(Guild guild, Member actor, String scope, String slot) {
        return remove(guild.getIdLong(), actor.getIdLong(), scope, slot);
    }

    DataObject remove(long guildId, long actorId, String scope, String slot) {
        if (!ImageUploadService.isKnownSlot(scope, slot)) throw new ApiError(404, Text.of(guildId, "dashboard.not_found"));
        boolean removed = images.remove(guildId, scope, slot);
        if (removed) log.info("Dashboard: {} removed the picture for {}/{}", actorId, scope, slot);
        return DataObject.empty().put("removed", removed);
    }
}
