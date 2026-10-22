package com.younglings.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.io.IOException;

/** {@code GET site-switches}: whether the owner has opened the JonnyBot website, the hub and the dashboard (the website gates read it; T-090). */
@BService
public class DirectorySiteSwitchRoutes implements DirectoryRouteModule {
    private final SiteSwitchAdminApi siteSwitches;

    public DirectorySiteSwitchRoutes(SiteSwitchAdminApi siteSwitches) {
        this.siteSwitches = siteSwitches;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("site-switches") || request.parts().length != 1) return false;
        if (request.requireGet()) request.send(200, siteSwitches.states());
        return true;
    }
}
