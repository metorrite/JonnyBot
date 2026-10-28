package com.runeclans.bot;

import io.github.freya022.botcommands.api.core.service.annotations.InterfacedService;

/**
 * Marks a JDA listener ({@code ListenerAdapter}, annotated {@code @BService}) that {@link Bot} registers with JDA by itself. A
 * {@code @BService} alone is never registered with JDA, which is why every older listener is named in {@code Bot.createJDA} by hand;
 * a new one only has to implement this interface, so adding a feature never means editing {@code Bot} (and features added side by side
 * never touch the same lines).
 */
@InterfacedService(acceptMultiple = true)
public interface BotListener {
}
