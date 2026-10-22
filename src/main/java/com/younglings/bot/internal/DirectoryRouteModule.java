package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;

/**
 * A feature's own part of the public directory ({@code /internal/directory/}). {@link DirectoryApi} has checked the shared secret and
 * answers the clan core (clans, events, leaderboards) itself; everything else is a module. Implement this on a {@code @BService} and its
 * routes and clan-page section are picked up with no edit to {@code DirectoryApi}, so features added side by side never touch the same
 * lines. Like the rest of the directory, a module only ever returns what a clan chose to share: never a member's name or a Discord id.
 */
@InterfacedService(acceptMultiple = true)
public interface DirectoryRouteModule {
    /**
     * Answers the request and returns true, or returns false without sending anything when {@link DirectoryRequest#parts()} isn't this
     * module's. The first module that returns true wins, so modules keep to their own first path segment.
     */
    default boolean route(DirectoryRequest request) throws IOException {
        return false;
    }

    /** Adds this module's section to a clan's full public page ({@code clans/{slug}}, and the clan inside {@code sites/{slug}}). */
    default void addToClan(ClanProfile profile, DataObject json) {
    }
}
