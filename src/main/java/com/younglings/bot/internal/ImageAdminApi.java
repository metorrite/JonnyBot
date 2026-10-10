package com.younglings.bot.internal;

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
        if (!ImageUploadService.isKnownSlot(scope, slot)) throw new ApiError(404, "Not found");
        byte[] data;
        try {
            data = Base64.getDecoder().decode(body.getString("data", ""));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "That file couldn't be read.");
        }

        try {
            UploadedImageRepository.Saved saved = images.save(guild.getIdLong(), scope, slot, data);
            log.info("Dashboard: {} uploaded a picture for {}/{} ({} bytes)", actor.getId(), scope, slot, data.length);
            return DataObject.empty().put("token", saved.token()).put("version", saved.version());
        } catch (ImageUploadService.InvalidImageException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    DataObject remove(Guild guild, Member actor, String scope, String slot) {
        if (!ImageUploadService.isKnownSlot(scope, slot)) throw new ApiError(404, "Not found");
        boolean removed = images.remove(guild.getIdLong(), scope, slot);
        if (removed) log.info("Dashboard: {} removed the picture for {}/{}", actor.getId(), scope, slot);
        return DataObject.empty().put("removed", removed);
    }
}
