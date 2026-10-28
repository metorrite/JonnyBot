package com.runeclans.bot.plugin;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;

import java.util.List;

/**
 * Told about the events the plugin reported that were newly stored (never a repeat), so other parts of the bot can use them. The one place
 * plugin data leaves the ingest: anything that wants it (events, points, later a live feed) is reached from here, not from the API route.
 * An implementation must never throw: a problem in a consumer can't be allowed to fail the plugin's upload.
 */
@InterfacedService(acceptMultiple = true)
public interface PluginEventSink {
    void accepted(long userId, String rsn, List<PluginPayload.Event> events);
}
