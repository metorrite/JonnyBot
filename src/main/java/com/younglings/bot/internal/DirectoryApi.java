package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfile.Focus;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import com.younglings.bot.clanprofile.ProfileRules;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * The public directory of clans, under {@code /internal/directory/}: not tied to any one server, so it is checked for the shared secret
 * but needs no acting user. It only ever returns what a clan chose to share: clan-level facts, never a member's name or any Discord
 * identity. A profile appears in the list only while it is {@code LISTED} (public, verified, not suspended); one that is unlisted can be
 * fetched by its address but is never listed.
 * <ul>
 *   <li>{@code GET clans}: every listed clan</li>
 *   <li>{@code GET clans/{slug}}: one clan, listed or unlisted</li>
 *   <li>{@code POST clans/{slug}/report} {@code {reason, reporter?}}: report a listing for the bot's owner to review</li>
 * </ul>
 */
@BService
public class DirectoryApi {
    private static final Logger log = LoggerFactory.getLogger(DirectoryApi.class);
    private static final String PREFIX = "/internal/directory/clans";
    private static final long LIST_TTL_MILLIS = 30_000;
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;
    private static final int MAX_REPORTS_PER_CLAN_PER_DAY = 20;

    private final ClanProfileRepository repository;
    private final ClanProfileService service;
    private final TtlCache cache = new TtlCache();

    public DirectoryApi(ClanProfileRepository repository, ClanProfileService service) {
        this.repository = repository;
        this.service = service;
    }

    /** Called after the shared secret has been checked. */
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        if (!path.startsWith(PREFIX)) {
            InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
            return;
        }
        String rest = path.substring(PREFIX.length());
        if (rest.isEmpty() || rest.equals("/")) {
            if (!method.equals("GET")) {
                InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }
            InternalApiServer.sendJson(exchange, 200, cache.get("list", LIST_TTL_MILLIS, this::buildList));
            return;
        }
        String[] parts = rest.substring(1).split("/");
        String slug = parts[0];
        if (parts.length == 1) {
            if (!method.equals("GET")) {
                InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
                return;
            }
            Optional<ClanProfile> profile = repository.bySlug(slug).filter(p -> service.publicView(p.guildId(), true).isPresent());
            if (profile.isEmpty()) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "No such clan"));
                return;
            }
            InternalApiServer.sendJson(exchange, 200, publicJson(profile.get(), true));
            return;
        }
        if (parts.length == 2 && parts[1].equals("report")) {
            report(exchange, method, slug);
            return;
        }
        InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
    }

    private DataObject buildList() {
        DataArray clans = DataArray.empty();
        for (ClanProfile p : repository.shared()) {
            if (service.publicView(p.guildId(), false).isEmpty()) continue;
            clans.add(publicJson(p, false));
        }
        return DataObject.empty().put("clans", clans);
    }

    private DataObject publicJson(ClanProfile p, boolean full) {
        DataArray focus = DataArray.empty();
        for (Focus f : Focus.values()) if (p.focus().contains(f)) focus.add(DataObject.empty().put("key", f.name()).put("label", f.label()));
        String clan = service.clanNameOf(p.guildId());
        boolean listed = ProfileRules.listing(p, clan) == ProfileRules.Listing.LISTED;
        DataObject json = DataObject.empty().put("slug", p.slug()).put("name", p.displayName()).put("tagline", p.tagline()).put("logoUrl", p.logoUrl())
                .put("colour", p.colour()).put("focus", focus).put("recruiting", DataObject.empty().put("key", p.recruiting().name()).put("label", p.recruiting().label()))
                .put("clanName", listed ? p.verifiedClanName() : null).put("verified", listed).put("listed", listed)
                .put("memberCount", clan == null ? null : service.memberCount(p.guildId()));
        if (full) {
            json.put("description", p.description()).put("requirements", p.requirements()).put("applyUrl", p.applyUrl()).put("timezone", p.timezone())
                    .put("updatedAt", p.updatedAt() == null ? null : p.updatedAt().toString());
        }
        return json;
    }

    private void report(HttpExchange exchange, String method, String slug) throws IOException {
        if (!method.equals("POST")) {
            InternalApiServer.sendJson(exchange, 405, DataObject.empty().put("error", "Method not allowed"));
            return;
        }
        Optional<ClanProfile> profile = repository.bySlug(slug).filter(p -> service.publicView(p.guildId(), true).isPresent());
        if (profile.isEmpty()) {
            InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "No such clan"));
            return;
        }
        DataObject body;
        try (InputStream in = exchange.getRequestBody()) {
            body = DataObject.fromJson(in);
        } catch (RuntimeException e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", "That report couldn't be read."));
            return;
        }
        String reason = body.getString("reason", "").strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", "Say what is wrong, in " + MIN_REASON + " to " + MAX_REASON + " characters."));
            return;
        }
        String reporter = body.isNull("reporter") ? null : body.getString("reporter", "").strip();
        if (reporter != null && reporter.length() > 100) reporter = reporter.substring(0, 100);
        boolean kept = repository.addReport(profile.get().guildId(), reason, reporter == null || reporter.isEmpty() ? null : reporter, MAX_REPORTS_PER_CLAN_PER_DAY);
        if (!kept) {
            InternalApiServer.sendJson(exchange, 429, DataObject.empty().put("error", "This clan has been reported a lot today already. It will be looked at."));
            return;
        }
        log.info("A report was made about clan listing {}", slug);
        InternalApiServer.sendJson(exchange, 202, DataObject.empty().put("ok", true));
    }
}
