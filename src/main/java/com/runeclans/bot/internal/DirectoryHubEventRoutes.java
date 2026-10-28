package com.runeclans.bot.internal;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;

/**
 * The events hub's engine events ({@link HubEventsApi}):
 * <ul>
 *   <li>{@code GET hub-events?viewer=} and {@code GET hub-events/{id}?viewer=}: the listing and one event (T-216; RuneClans' board, own place and
 *       manage link, T-335/T-336)</li>
 *   <li>{@code GET hub-events/mine?viewer=}: the events the viewer plays in, in every server, with their own place in each (RuneClans' My events, T-337)</li>
 *   <li>{@code POST hub-events/{id}/join} {@code {userId, via?}}: join one as the signed-in person (the website says who; T-217), optionally through
 *       a co-hosting server from the event's {@code joinWith} list (T-341)</li>
 *   <li>{@code POST hub-events/{id}/leave} {@code {userId}}: leave one while it takes sign-ups (T-337)</li>
 *   <li>{@code POST hub-events/{id}/report} {@code {reason, reporter?}}: report a listed event for the bot's owner to review (T-218)</li>
 * </ul>
 */
@BService
public class DirectoryHubEventRoutes implements DirectoryRouteModule {
    private final HubEventsApi hubEvents;

    public DirectoryHubEventRoutes(HubEventsApi hubEvents) {
        this.hubEvents = hubEvents;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        if (!request.head().equals("hub-events")) return false;
        String[] parts = request.parts();
        if (parts.length == 3 && parts[2].equals("join")) {
            if (!request.requirePost()) return true;
            DataObject body = request.body("That request couldn't be read.");
            if (body == null) return true;
            String via = body.hasKey("via") && !body.isNull("via") ? body.getString("via", "") : null;
            HubEventsApi.Answer answer = hubEvents.join(request.jda(), parts[1], body.getString("userId", ""), via);
            request.send(answer.status(), answer.body());
        } else if (parts.length == 3 && parts[2].equals("leave")) {
            if (!request.requirePost()) return true;
            DataObject body = request.body("That request couldn't be read.");
            if (body == null) return true;
            HubEventsApi.Answer answer = hubEvents.leave(request.jda(), parts[1], body.getString("userId", ""));
            request.send(answer.status(), answer.body());
        } else if (parts.length == 3 && parts[2].equals("report")) {
            if (!request.requirePost()) return true;
            DataObject body = request.body("That report couldn't be read.");
            if (body == null) return true;
            HubEventsApi.Answer answer = hubEvents.report(request.jda(), parts[1], body);
            request.send(answer.status(), answer.body());
        } else {
            if (!request.requireGet()) return true;
            String viewer = request.query("viewer");
            DataObject answer = parts.length == 2 && parts[1].equals("mine") ? hubEvents.mine(request.jda(), viewer) : parts.length == 1 ? hubEvents.list(request.jda(), viewer) : parts.length == 2 ? hubEvents.one(request.jda(), parts[1], viewer) : null;
            if (answer == null) request.sendError(404, "No such event");
            else request.send(200, answer);
        }
        return true;
    }
}
