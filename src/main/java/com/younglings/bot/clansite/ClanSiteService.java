package com.younglings.bot.clansite;

import com.younglings.bot.access.GateSource;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.Objects;
import java.util.Optional;

/**
 * Which clan websites the public may see. A site shows only while it is switched on, published and not taken down, and (while the
 * builder is in beta) only for the home server, a server on the beta list (the same list that lets admins into JonnyBot's website early), or a
 * bot-less site (set up without the bot by someone on the site builder beta list, {@link BotlessSiteRepository}).
 * Whether the clan profile itself may be shown is the caller's check (the directory's "viewable": unlisted or listed, not suspended).
 */
@BService
public class ClanSiteService {
    private final ClanSiteRepository sites;
    private final GateSource gates;
    private final BotConfig config;
    private final BotlessSiteRepository botless;

    public ClanSiteService(ClanSiteRepository sites, GateSource gates, BotConfig config, BotlessSiteRepository botless) {
        this.sites = sites;
        this.gates = gates;
        this.config = config;
        this.botless = botless;
    }

    /** The server's site, or an empty (off) one when it has never used the builder. */
    public ClanSite of(long guildId) {
        return sites.byGuild(guildId).orElseGet(() -> ClanSite.empty(guildId));
    }

    /** The site the public may see for this server, if any. */
    public Optional<ClanSite> visible(long guildId) {
        return sites.byGuild(guildId).filter(site -> servable(site, inBeta(guildId)));
    }

    /** The content blocks of a server's site, by page then position. */
    public java.util.List<ClanSiteBlock> blocks(long guildId) {
        return sites.blocks(guildId);
    }

    /** Whether a server may use the builder while it is in beta: the home server, one on the beta list, or a bot-less site. */
    public boolean inBeta(long guildId) {
        return Objects.equals(config.getGuildId(), guildId) || gates.betaGuilds().stream().anyMatch(b -> b.guildId() == guildId) || botless.contains(guildId);
    }

    /** Whether this server's site runs without the bot ({@link BotlessSiteRepository}). */
    public boolean botless(long guildId) {
        return botless.contains(guildId);
    }

    /** A site shows when it is live (on, published, not taken down) and its server is allowed into the beta. */
    static boolean servable(ClanSite site, boolean inBeta) {
        return site != null && site.live() && inBeta;
    }
}
