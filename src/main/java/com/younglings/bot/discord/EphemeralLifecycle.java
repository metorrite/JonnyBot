package com.younglings.bot.discord;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.ActionComponent;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorHandler;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cleans up after ephemeral messages so they don't pile up in someone's channel. Discord never removes
 * them on its own — they stay until the user clicks "Dismiss" — and only the interaction that created
 * one can delete it, so this keeps hold of the interaction hooks for that purpose.
 * <ul>
 *   <li><b>Panels</b> (an ephemeral message with buttons or menus on it) are removed after
 *       {@link #PANEL_IDLE_SECONDS} without being clicked. Every click on one restarts that clock.</li>
 *   <li><b>Everything else</b> (a confirmation, a notice, an error, a result) is removed
 *       {@link #MESSAGE_SECONDS} after it was sent.</li>
 * </ul>
 * One place handles every command and button in the bot: it looks at what each interaction actually
 * produced rather than relying on each handler to opt in. A moment after an interaction it fetches the
 * resulting message; if that's an ephemeral one, it's tracked. When its timer fires it looks again before
 * deleting, so a message that has since become a panel (or a panel that has since collapsed into a
 * confirmation) is treated as what it is now, and a message still showing Discord's "thinking" state is
 * left alone.
 */
@BService
public class EphemeralLifecycle extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(EphemeralLifecycle.class);

    /** How long a panel can sit unused before it removes itself. Shared with {@link Containers#autoCloseNote()} so the on-screen countdown matches. */
    public static final long PANEL_IDLE_SECONDS = 300;
    /** How long a non-panel ephemeral message lives. */
    public static final long MESSAGE_SECONDS = 60;
    // Long enough for a handler to have finished editing the message the interaction acknowledged.
    private static final long SETTLE_SECONDS = 2;
    // A message can be touched by several interactions; the newest one that can still delete it is tried first.
    private static final int HOOKS_KEPT = 3;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ephemeral-lifecycle");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Long, Tracked> tracked = new ConcurrentHashMap<>();

    private static final class Tracked {
        final Deque<InteractionHook> hooks = new ArrayDeque<>();
        volatile long lastActivityMillis;
        volatile ScheduledFuture<?> timer;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        observe(event.getHook());
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        observe(event.getHook());
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        observe(event.getHook());
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        observe(event.getHook());
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        observe(event.getHook());
    }

    private void observe(InteractionHook hook) {
        try {
            scheduler.schedule(() -> fetch(hook, message -> track(message, hook)), SETTLE_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("Couldn't schedule an ephemeral check", e);
        }
    }

    /** Fetches the message this hook's interaction ended up producing; silently does nothing if there is none (a modal was opened, it was already deleted, the hook expired, ...). */
    private static void fetch(InteractionHook hook, java.util.function.Consumer<Message> onMessage) {
        hook.retrieveOriginal().queue(onMessage, new ErrorHandler()
                .ignore(ErrorResponse.UNKNOWN_MESSAGE, ErrorResponse.UNKNOWN_WEBHOOK, ErrorResponse.UNKNOWN_INTERACTION,
                        ErrorResponse.INVALID_WEBHOOK_TOKEN)
                .handle(Throwable.class, e -> log.debug("Couldn't fetch an interaction's message", e)));
    }

    private void track(Message message, InteractionHook hook) {
        if (!message.isEphemeral()) return;

        Tracked entry = tracked.computeIfAbsent(message.getIdLong(), id -> new Tracked());
        synchronized (entry) {
            entry.hooks.addFirst(hook);
            while (entry.hooks.size() > HOOKS_KEPT) entry.hooks.removeLast();
            entry.lastActivityMillis = System.currentTimeMillis();
            schedule(message.getIdLong(), entry, ttlSeconds(message));
        }
    }

    private void schedule(long messageId, Tracked entry, long delaySeconds) {
        ScheduledFuture<?> previous = entry.timer;
        if (previous != null) previous.cancel(false);
        entry.timer = scheduler.schedule(() -> expire(messageId, entry), Math.max(1, delaySeconds), TimeUnit.SECONDS);
    }

    static boolean isPanel(Message message) {
        return !message.getComponentTree().findAll(ActionComponent.class).isEmpty();
    }

    private static long ttlSeconds(Message message) {
        return isPanel(message) ? PANEL_IDLE_SECONDS : MESSAGE_SECONDS;
    }

    private void expire(long messageId, Tracked entry) {
        List<InteractionHook> candidates;
        synchronized (entry) {
            candidates = new ArrayList<>(entry.hooks);
        }
        deleteWithFirstWorkingHook(messageId, entry, candidates, 0);
    }

    private void deleteWithFirstWorkingHook(long messageId, Tracked entry, List<InteractionHook> hooks, int index) {
        if (index >= hooks.size()) {
            tracked.remove(messageId, entry); // nothing can delete it any more (hooks expire after 15 minutes) — let it be
            return;
        }

        InteractionHook hook = hooks.get(index);
        hook.retrieveOriginal().queue(message -> {
            // Looked at again right before deleting: it may have become a panel, or finished loading.
            long idleMillis = System.currentTimeMillis() - entry.lastActivityMillis;
            long allowedMillis = ttlSeconds(message) * 1000;

            if (message.getFlags().contains(Message.MessageFlag.LOADING)) {
                synchronized (entry) { schedule(messageId, entry, MESSAGE_SECONDS); }
            } else if (idleMillis < allowedMillis) {
                synchronized (entry) { schedule(messageId, entry, (allowedMillis - idleMillis) / 1000 + 1); }
            } else {
                hook.deleteOriginal().queue(success -> tracked.remove(messageId, entry), new ErrorHandler()
                        .ignore(ErrorResponse.UNKNOWN_MESSAGE)
                        .handle(Throwable.class, e -> {
                            log.debug("Couldn't delete ephemeral message {}", messageId, e);
                            tracked.remove(messageId, entry);
                        }));
            }
        }, error -> deleteWithFirstWorkingHook(messageId, entry, hooks, index + 1));
    }
}
