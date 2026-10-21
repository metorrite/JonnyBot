package com.younglings.bot.clansite;

import com.younglings.bot.beta.BetaGuildRepository;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.Objects;
import java.util.Optional;

/**
 * Which clan websites the public may see. A site shows only while it is switched on, published and not taken down, and (while the
 * builder is in beta) only for the home server or a server on the beta list, the same list that lets admins into JonnyBot's website early.
 * Whether the clan profile itself may be shown is the caller's check (the directory's "viewable": unlisted or listed, not suspended).
 */
@BService
public class ClanSiteService {
    private final ClanSiteRepository sites;
    private final BetaGuildRepository beta;
    private final BotConfig config;

    public ClanSiteService(ClanSiteRepository sites, BetaGuildRepository beta, BotConfig config) {
        this.sites = sites;
        this.beta = beta;
        this.config = config;
    }

    /** The server's site, or an empty (off) one when it has never used the builder. */
    public ClanSite of(long guildId) {
        return sites.byGuild(guildId).orElseGet(() -> ClanSite.empty(guildId));
    }

    /** The site the public may see for this server, if any. */
    public Optional<ClanSite> visible(long guildId) {
        return sites.byGuild(guildId).filter(site -> servable(site, inBeta(guildId)));
    }

    /** Whether a server may use the builder while it is in beta: the home server, or one on the beta list. */
    public boolean inBeta(long guildId) {
        return Objects.equals(config.getGuildId(), guildId) || beta.all().stream().anyMatch(b -> b.guildId() == guildId);
    }

    /** A site shows when it is live (on, published, not taken down) and its server is allowed into the beta. */
    static boolean servable(ClanSite site, boolean inBeta) {
        return site != null && site.live() && inBeta;
    }
}
