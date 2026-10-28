package com.runeclans.bot.testsupport;

import com.runeclans.bot.hubsites.HubSites;
import com.runeclans.bot.hubsites.SiteChannelMappings;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;

/** Channel mappings with {@code SITES_BACKEND=bot}: every store keeps its own table, as before T-505. */
public final class SiteMappings {
    private SiteMappings() {}

    public static SiteChannelMappings off(ConnectionSupplier db) {
        return new SiteChannelMappings(db, HubSites.off());
    }
}
