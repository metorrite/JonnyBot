package com.runeclans.bot.internal;

import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.ProfileRules;
import com.runeclans.bot.clanprofile.SlugChangeRules;
import com.runeclans.bot.clanprofile.SlugChangeService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;
import java.util.function.Supplier;

/**
 * The site address change (D4) for the site admin, under {@code clan-site/address}: {@code GET} (the address, a pending request, the cooldown),
 * {@code POST} {@code {slug}} (ask, or change the pending request without restarting its hour), {@code POST lock-in} (change now) and
 * {@code POST cancel}. Reached through {@link ClanSiteAdminApi}, which has already checked the caller may change the site's settings, so it works
 * for site admins without dashboard access and for bot-less sites. The rules are {@link SlugChangeRules}.
 */
@BService
public class SiteAddressRoutes {
    private final SlugChangeService changes;
    private final ClanProfileService profiles;

    public SiteAddressRoutes(SlugChangeService changes, ClanProfileService profiles) {
        this.changes = changes;
        this.profiles = profiles;
    }

    /** {@code sub} is the part after {@code address} ("" , "lock-in" or "cancel"); null for a path that doesn't exist. */
    DataObject route(long guildId, String method, String sub, Supplier<DataObject> body) {
        switch (sub) {
            case "" -> {
                if (method.equals("POST")) fail(guildId, changes.request(guildId, body.get().getString("slug", "")));
                else AdminRouteExtension.requireMethod(method, "GET");
            }
            case "lock-in" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                fail(guildId, changes.lockIn(guildId));
            }
            case "cancel" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                changes.cancel(guildId);
            }
            default -> {
                return null;
            }
        }
        return status(guildId);
    }

    private static void fail(long guildId, SlugChangeService.Outcome outcome) {
        if (!outcome.ok()) throw new ApiError(400, Text.of(guildId, "dashboard.the_clan_profile_can_t_be"), List.of(outcome.problem()));
    }

    DataObject status(long guildId) {
        SlugChangeRules.Status status = changes.status(guildId);
        ClanProfile p = profiles.profileOf(guildId);
        DataObject pending = status.pendingSlug() == null ? null : DataObject.empty().put("slug", status.pendingSlug())
                .put("requestedAt", status.requestedAt().toString()).put("appliesAt", status.appliesAt().toString());
        return DataObject.empty().put("slug", p.slug()).put("pending", pending)
                .put("cooldownUntil", status.cooldownUntil() == null ? null : status.cooldownUntil().toString())
                .put("windowMinutes", SlugChangeRules.WINDOW.toMinutes()).put("cooldownDays", SlugChangeRules.COOLDOWN.toDays())
                .put("forwardDays", ProfileRules.SLUG_GRACE.toDays());
    }
}
