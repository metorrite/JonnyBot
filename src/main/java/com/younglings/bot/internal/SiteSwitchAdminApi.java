package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.notice.BotOwners;
import com.younglings.bot.access.GateSource;
import com.younglings.bot.siteaccess.SiteSwitch;
import com.younglings.bot.siteaccess.SiteSwitchRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * The live site-access switches (JonnyBot website, clans hub, bot dashboard): open or close each gate from the website's Super admin area.
 * Reading and changing them is for the bot's owner only. The website's gates read the same state through {@link #states()} (served by the
 * directory API), and its environment variables stay the hard override. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker and audit-logs every change.
 */
@BService
public class SiteSwitchAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SiteSwitchAdminApi.class);

    private final SiteSwitchRepository repository;
    private final BotOwners owners;
    private final GateSource gates;

    public SiteSwitchAdminApi(SiteSwitchRepository repository, BotOwners owners, GateSource gates) {
        this.repository = repository;
        this.owners = owners;
        this.gates = gates;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("site-switches")) return null;
        requireOwner(guild, actor);
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list();
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return set(actor, parts[1], body.get());
        }
        return null;
    }

    /** Every switch, for the Super admin page. */
    DataObject list() {
        Map<String, Boolean> stored = repository.all();
        DataArray switches = DataArray.empty();
        for (SiteSwitch s : SiteSwitch.values()) {
            switches.add(DataObject.empty().put("key", s.key()).put("label", s.label()).put("description", s.description())
                    .put("enabled", stored.getOrDefault(s.key(), false)));
        }
        return DataObject.empty().put("switches", switches);
    }

    DataObject set(Member actor, String key, DataObject body) {
        SiteSwitch s = SiteSwitch.ofKey(key).orElseThrow(() -> new ApiError(404, Text.of(actor.getGuild(), "dashboard.that_is_not_a_switch")));
        HubAccessEdits.refuseWhenInHub(gates.fromHub());
        if (!body.hasKey("enabled")) throw new ApiError(400, Text.of(actor.getGuild(), "dashboard.say_whether_the_switch_is_on"));
        boolean enabled = body.getBoolean("enabled");
        repository.set(s.key(), enabled, actor.getIdLong());
        log.info("Dashboard: {} turned the {} switch {}", actor.getId(), s.key(), enabled ? "on" : "off");
        return list();
    }

    /** What the website's gates read: key to open. A switch never set is closed. From the hub with RUNECLANS_HUB_ACCESS ({@link GateSource}). */
    public DataObject states() {
        Map<String, Boolean> stored = gates.switches();
        DataObject states = DataObject.empty();
        for (SiteSwitch s : SiteSwitch.values()) states.put(s.key(), stored.getOrDefault(s.key(), false));
        return DataObject.empty().put("switches", states);
    }

    private void requireOwner(Guild guild, Member actor) {
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_change_3"));
    }
}
