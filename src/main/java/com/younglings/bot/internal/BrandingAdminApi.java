package com.younglings.bot.internal;

import com.younglings.bot.branding.BrandingImages;
import com.younglings.bot.branding.BrandingRepository;
import com.younglings.bot.branding.BrandingService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.premium.Premium;
import com.younglings.bot.premium.PremiumFeatures;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.SelfMember;
import net.dv8tion.jda.api.managers.SelfMemberManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * How JonnyBot appears in one server: the name its messages use for the server (a display name, else the clan name, else the Discord server
 * name), the bot's own nickname there, and, for servers on the premium list, its server avatar, banner and bio. The nickname and avatar live in
 * Discord and are read and set live; the bio and whether a banner was uploaded are remembered here because Discord won't say. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class BrandingAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(BrandingAdminApi.class);
    /** Discord's limit for a nickname. */
    static final int MAX_NICKNAME = 32;

    private final BrandingRepository repository;
    private final BrandingService branding;
    private final Premium premium;

    public BrandingAdminApi(BrandingRepository repository, BrandingService branding, Premium premium) {
        this.repository = repository;
        this.branding = branding;
        this.premium = premium;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("branding")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        SelfMember self = guild.getSelfMember();
        BrandingRepository.Profile profile = repository.profile(guild.getIdLong());
        String custom = profile.displayName() == null || profile.displayName().isBlank() ? null : profile.displayName();
        return DataObject.empty()
                .put("displayName", custom)
                .put("clanName", branding.clanName(guild.getIdLong()))
                .put("serverName", guild.getName())
                .put("effectiveName", branding.displayName(guild))
                .put("nickname", self.getNickname())
                .put("botName", self.getUser().getName())
                .put("canChangeNickname", self.hasPermission(Permission.NICKNAME_CHANGE))
                .put("premium", premium.allows(guild.getIdLong(), PremiumFeatures.BRANDING_PROFILE))
                .put("avatarUrl", self.getAvatarUrl())
                .put("bannerSet", profile.bannerSet())
                .put("bio", profile.bio())
                .put("maxBio", SelfMember.MAX_BIO_LENGTH)
                .put("maxDisplayName", BrandingService.MAX_DISPLAY_NAME)
                .put("maxNickname", MAX_NICKNAME);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();

        boolean setName = body.hasKey("displayName");
        String displayName = null;
        if (setName) {
            displayName = body.isNull("displayName") ? "" : body.getString("displayName", "").strip();
            if (displayName.length() > BrandingService.MAX_DISPLAY_NAME) problems.add("Keep the display name to " + BrandingService.MAX_DISPLAY_NAME + " characters or fewer.");
            if (displayName.contains("@") || displayName.contains("\n")) problems.add("The display name can't contain @ or a new line.");
        }

        SelfMember self = guild.getSelfMember();
        boolean setNickname = body.hasKey("nickname");
        String nickname = null;
        if (setNickname) {
            nickname = body.isNull("nickname") ? "" : body.getString("nickname", "").strip();
            if (nickname.length() > MAX_NICKNAME) problems.add("A nickname can be at most " + MAX_NICKNAME + " characters.");
            boolean changed = !nickname.equals(self.getNickname() == null ? "" : self.getNickname());
            if (changed && !self.hasPermission(Permission.NICKNAME_CHANGE)) problems.add("JonnyBot needs the Change Nickname permission to rename itself here. Give its role that permission in Server Settings, then Roles.");
        }

        // The profile (avatar, banner, bio) is for servers on the premium list.
        boolean setBio = body.hasKey("bio");
        boolean setAvatar = hasPicture(body, "avatar");
        boolean setBanner = hasPicture(body, "banner");
        boolean removeAvatar = body.getBoolean("removeAvatar", false);
        boolean removeBanner = body.getBoolean("removeBanner", false);
        boolean touchesProfile = setBio || setAvatar || setBanner || removeAvatar || removeBanner;
        if (touchesProfile && !premium.allows(guild.getIdLong(), PremiumFeatures.BRANDING_PROFILE)) {
            throw new ApiError(403, "A server avatar, banner and bio are for premium servers.");
        }
        String bio = null;
        if (setBio) {
            bio = body.isNull("bio") ? "" : body.getString("bio", "").strip();
            if (bio.length() > SelfMember.MAX_BIO_LENGTH) problems.add("The bio can be at most " + SelfMember.MAX_BIO_LENGTH + " characters.");
        }
        Icon avatar = null;
        Icon banner = null;
        try {
            if (setAvatar) avatar = Icon.from(BrandingImages.decode(body.getString("avatar", ""), "avatar"));
            if (setBanner) banner = Icon.from(BrandingImages.decode(body.getString("banner", ""), "banner"));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That can't be saved yet.", problems);

        if (setName) repository.saveDisplayName(guild.getIdLong(), displayName.isEmpty() ? null : displayName);
        if (setNickname) {
            String next = nickname.isEmpty() ? null : nickname;
            if (!Objects.equals(next, self.getNickname())) {
                try {
                    guild.modifyNickname(self, next).reason("Changed from the JonnyBot dashboard").complete();
                } catch (RuntimeException e) {
                    log.warn("Couldn't rename JonnyBot in guild {}: {}", guild.getId(), e.getMessage());
                    throw new ApiError(502, "Discord didn't accept that nickname.", List.of(e.getMessage() == null ? "Try again in a moment." : e.getMessage()));
                }
            }
        }
        if (touchesProfile) applyProfile(guild, self, setBio, bio, avatar, removeAvatar, banner, removeBanner);
        log.info("Dashboard: {} saved the bot's appearance (name {}, nickname {}, profile {})", actor.getId(), setName, setNickname, touchesProfile);
        return get(guild);
    }

    private void applyProfile(Guild guild, SelfMember self, boolean setBio, String bio, Icon avatar, boolean removeAvatar, Icon banner, boolean removeBanner) {
        SelfMemberManager manager = self.getManager();
        if (avatar != null) manager.setAvatar(avatar);
        else if (removeAvatar) manager.reset(SelfMemberManager.AVATAR);
        if (banner != null) manager.setBanner(banner);
        else if (removeBanner) manager.reset(SelfMemberManager.BANNER);
        if (setBio) manager.setBio(bio.isEmpty() ? null : bio);
        try {
            manager.complete();
        } catch (RuntimeException e) {
            log.warn("Couldn't change JonnyBot's profile in guild {}: {}", guild.getId(), e.getMessage());
            throw new ApiError(502, "Discord didn't accept that.", List.of(e.getMessage() == null ? "Try again in a moment." : e.getMessage()));
        }
        // Only remembered once Discord has accepted it.
        if (setBio) repository.saveBio(guild.getIdLong(), bio.isEmpty() ? null : bio);
        if (banner != null) repository.saveBannerSet(guild.getIdLong(), true);
        else if (removeBanner) repository.saveBannerSet(guild.getIdLong(), false);
    }

    private static boolean hasPicture(DataObject body, String key) {
        return body.hasKey(key) && !body.isNull(key) && !body.getString(key, "").isBlank();
    }
}
