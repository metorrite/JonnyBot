package com.runeclans.bot.discord;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.StatusChangeEvent;
import net.dv8tion.jda.api.hooks.IEventManager;
import net.dv8tion.jda.internal.JDAImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The api role's Discord connection (T-670): REST only, no gateway, and it reports ready the way BotCommands expects. No network. */
class RestOnlyJdaTest {
    /** Records the events JDA hands to the event manager. */
    private static final class Recording implements IEventManager {
        final List<GenericEvent> events = new ArrayList<>();

        @Override
        public void register(Object listener) {}

        @Override
        public void unregister(Object listener) {}

        @Override
        public void handle(GenericEvent event) {
            events.add(event);
        }

        @Override
        public List<Object> getRegisteredListeners() {
            return List.of();
        }
    }

    @Test
    void reportsConnectedThroughTheEventManagerWithoutAGateway() {
        Recording events = new Recording();
        JDAImpl jda = RestOnlyJda.build("x".repeat(24), events);
        try {
            RestOnlyJda.start(jda);
            List<JDA.Status> statuses = events.events.stream().filter(StatusChangeEvent.class::isInstance).map(e -> ((StatusChangeEvent) e).getNewStatus()).toList();
            assertEquals(List.of(JDA.Status.CONNECTING_TO_WEBSOCKET, JDA.Status.CONNECTED), statuses,
                    "BotCommands injects the JDA once it reaches CONNECTING_TO_WEBSOCKET");
            assertEquals(JDA.Status.CONNECTED, jda.getStatus());
            assertNull(jda.getShardManager());
            assertTrue(jda.getGuilds().isEmpty(), "no gateway, so no server cache");
            assertNull(Shards.guild(jda, 1L), "every server lookup answers null");
        } finally {
            jda.shutdownNow();
        }
    }
}
