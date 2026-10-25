package com.younglings.bot.internal;

import com.younglings.bot.clansite.SiteRoleGuard;
import com.younglings.bot.clansite.SiteRoles;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.premium.Premium;
import com.younglings.bot.premium.PremiumFeatures;
import com.younglings.bot.upload.ImageUploadService;
import com.younglings.bot.upload.UploadedImageRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * A clan website's picture library (T-408, docs/BUILDER-V3.md 5.4): the pictures the site editor uploads for galleries, picture fields and
 * section backgrounds. Each is served by the website's existing {@code /api/uploads/<token>} address like the logo and banner.
 * <ul>
 *   <li>{@code GET site-pictures}: the library, with each picture's size, how many there are and the plan's limit.</li>
 *   <li>{@code POST site-pictures} {@code {data: base64}}: adds a picture (2 MB, PNG/JPEG/GIF/WebP from its bytes), refused once the site
 *   holds its plan's number ({@link PremiumFeatures#SITE_PICTURES}: 10 free, 200 premium).</li>
 *   <li>{@code DELETE site-pictures/<token>}: removes one of this server's pictures.</li>
 * </ul>
 * Anyone who may edit the site may use them (site roles, T-368), so a site editor without dashboard access can upload too. A plan that ends
 * keeps every picture (10.4); only new uploads meet the lower limit.
 */
@BService
public class SitePictureAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SitePictureAdminApi.class);
    static final String PATH = "site-pictures";

    private final ImageUploadService images;
    private final Premium premium;
    private final SiteRoleGuard siteRoles;

    public SitePictureAdminApi(ImageUploadService images, Premium premium, SiteRoleGuard siteRoles) {
        this.images = images;
        this.premium = premium;
        this.siteRoles = siteRoles;
    }

    private static boolean ours(String[] parts) {
        return parts.length >= 1 && parts.length <= 2 && parts[0].equals(PATH);
    }

    @Override
    public boolean admits(Guild guild, Member actor, String method, String[] parts) {
        return ours(parts) && siteRoles.allows(guild, actor, SiteRoles.Action.EDIT);
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        return route(SiteCaller.of(guild, actor), method, parts, body);
    }

    /** The routes for any caller, a bot-less site's included ({@link BotlessAdminApi}). */
    DataObject route(SiteCaller c, String method, String[] parts, Supplier<DataObject> body) {
        if (!ours(parts)) return null;
        if (!c.may(siteRoles, SiteRoles.Action.EDIT))
            throw new ApiError(403, "Only people who may edit this website can change its pictures.");
        long guildId = c.guildId();
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            boolean removed = images.removeFromLibrary(guildId, parts[1]);
            if (!removed) throw new ApiError(404, Text.of(guildId, "dashboard.not_found"));
            log.info("Site pictures: {} removed a picture from guild {}", c.userId(), guildId);
            return library(guildId).put("removed", true);
        }
        if (method.equals("GET")) return library(guildId);
        AdminRouteExtension.requireMethod(method, "POST");
        return upload(guildId, c.userId(), body.get());
    }

    long limit(long guildId) {
        return premium.limit(guildId, PremiumFeatures.SITE_PICTURES).orElse(0L);
    }

    private DataObject upload(long guildId, long actorId, DataObject body) {
        byte[] data;
        try {
            data = Base64.getDecoder().decode(body.getString("data", ""));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, Text.of(guildId, "dashboard.that_file_couldn_t_be_read"));
        }
        long limit = limit(guildId);
        UploadedImageRepository.Saved saved;
        try {
            saved = images.addToLibrary(guildId, data, limit).orElseThrow(() -> new ApiError(409, fullMessage(guildId, limit)));
        } catch (ImageUploadService.InvalidImageException e) {
            throw new ApiError(400, e.getMessage());
        }
        log.info("Site pictures: {} added a picture to guild {} ({} bytes)", actorId, guildId, data.length);
        return library(guildId).put("token", saved.token()).put("version", saved.version());
    }

    /** Plain words for a full library: how to make room, and whether a bigger plan would help. */
    String fullMessage(long guildId, long limit) {
        boolean premiumPlan = premium.allows(guildId, PremiumFeatures.SITE_LIMITS);
        long premiumLimit = PremiumFeatures.limit(PremiumFeatures.TIER_PREMIUM, PremiumFeatures.SITE_PICTURES).orElse(limit);
        return premiumPlan || premiumLimit <= limit
                ? "This website already has " + limit + " pictures, the most it can hold. Delete one to add another."
                : "This website already has " + limit + " pictures, the most a free site can hold. Delete one, or go Premium for up to " + premiumLimit + ".";
    }

    private DataObject library(long guildId) {
        List<UploadedImageRepository.Listed> pictures = images.library(guildId);
        DataArray list = DataArray.empty();
        long total = 0;
        for (UploadedImageRepository.Listed p : pictures) {
            total += p.bytes();
            list.add(DataObject.empty().put("token", p.token()).put("version", p.version()).put("contentType", p.contentType()).put("bytes", p.bytes()));
        }
        return DataObject.empty().put("pictures", list).put("used", pictures.size()).put("limit", limit(guildId)).put("bytes", total)
                .put("maxBytes", ImageUploadService.MAX_BYTES);
    }
}
