package com.younglings.bot.internal;

import com.younglings.bot.branding.BrandingRepository;
import com.younglings.bot.branding.BrandingService;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.premium.PremiumGuildRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * How JonnyBot appears in one server: the name its messages use for the server (a display name, else the clan name, else the Discord server
 * name) and the bot's own nickname there. The nickname lives in Discord, so it is read and set live rather than stored. Avatar, banner and bio
 * belong to servers on the premium list; this reports whether the server is on it so the page can say so. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class BrandingAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(BrandingAdminApi.class);
    /** Discord's limit for a nickname. */
    static final int MAX_NICKNAME = 32;

    private final BrandingRepository repository;
    private final BrandingService branding;
    private final PremiumGuildRepository premium;

    public BrandingAdminApi(BrandingRepository repository, BrandingService branding, PremiumGuildRepository premium) {
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
        Member self = guild.getSelfMember();
        String custom = branding.customName(guild.getIdLong());
        return DataObject.empty()
                .put("displayName", custom)
                .put("clanName", branding.clanName(guild.getIdLong()))
                .put("serverName", guild.getName())
                .put("effectiveName", branding.displayName(guild))
                .put("nickname", self.getNickname())
                .put("botName", self.getUser().getName())
                .put("canChangeNickname", self.hasPermission(Permission.NICKNAME_CHANGE))
                .put("premium", premium.isPremium(guild.getIdLong()))
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

        Member self = guild.getSelfMember();
        boolean setNickname = body.hasKey("nickname");
        String nickname = null;
        if (setNickname) {
            nickname = body.isNull("nickname") ? "" : body.getString("nickname", "").strip();
            if (nickname.length() > MAX_NICKNAME) problems.add("A nickname can be at most " + MAX_NICKNAME + " characters.");
            boolean changed = !nickname.equals(self.getNickname() == null ? "" : self.getNickname());
            if (changed && !self.hasPermission(Permission.NICKNAME_CHANGE)) problems.add("JonnyBot needs the Change Nickname permission to rename itself here. Give its role that permission in Server Settings, then Roles.");
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That can't be saved yet.", problems);

        if (setName) repository.saveDisplayName(guild.getIdLong(), displayName.isEmpty() ? null : displayName);
        if (setNickname) {
            String next = nickname.isEmpty() ? null : nickname;
            String current = self.getNickname();
            if (!java.util.Objects.equals(next, current)) {
                try {
                    guild.modifyNickname(self, next).reason("Changed from the JonnyBot dashboard").complete();
                } catch (RuntimeException e) {
                    log.warn("Couldn't rename JonnyBot in guild {}: {}", guild.getId(), e.getMessage());
                    throw new ApiError(502, "Discord didn't accept that nickname.", List.of(e.getMessage() == null ? "Try again in a moment." : e.getMessage()));
                }
            }
        }
        log.info("Dashboard: {} saved the bot's appearance (name {}, nickname {})", actor.getId(), setName, setNickname);
        return get(guild);
    }
}
