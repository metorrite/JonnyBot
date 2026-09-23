package com.younglings.bot.discord;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.awt.Color;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared Components V2 building blocks, so every command/listener renders replies the same way
 * instead of each hand-rolling its own {@code Container}/color palette. Built while converting the
 * whole bot off classic embeds — see the individual command packages for how these get used.
 * <p>
 * Two kinds of helper live here: plain {@link Container} factories ({@link #toast}, {@link #card})
 * for building a component tree yourself, and {@code replyX}/{@code editX} convenience methods that
 * also send/edit it. The convenience methods are typed against {@link IReplyCallback} /
 * {@link IMessageEditCallback} rather than a concrete event class, since every interaction event in
 * this bot (slash command, button, modal) implements one or both — one helper works everywhere.
 */
public final class Containers {
    private Containers() {}

    // A small shared palette instead of every listener defining its own — pick by what the message
    // is communicating, not by which subsystem it's in.
    public static final Color PRIMARY = Color.CYAN;
    public static final Color INFO = Color.BLUE;
    public static final Color SUCCESS = new Color(0x57F287);
    public static final Color WARNING = new Color(0xFEE75C);
    public static final Color DANGER = new Color(0xED4245);

    /** A container of one {@link TextDisplay} per line — the everyday replacement for a bare {@code event.reply("some string")}. */
    public static Container toast(Color accent, String... lines) {
        List<ContainerChildComponent> children = new ArrayList<>(lines.length);
        for (String line : lines) children.add(TextDisplay.of(line));
        return Container.of(children).withAccentColor(accent);
    }

    /** A container built from arbitrary children (text, separators, action rows, sections, ...). */
    public static Container card(Color accent, List<? extends ContainerChildComponent> children) {
        return Container.of(children).withAccentColor(accent);
    }

    public static Container card(Color accent, ContainerChildComponent... children) {
        return Container.of(List.of(children)).withAccentColor(accent);
    }

    // --- Reply (works from a slash command, button, or modal) ---

    public static void reply(IReplyCallback event, Color accent, boolean ephemeral, String... lines) {
        event.replyComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .setEphemeral(ephemeral)
                .queue();
    }

    public static void replyEphemeral(IReplyCallback event, Color accent, String... lines) {
        reply(event, accent, true, lines);
    }

    /** Ephemeral reply that self-deletes after {@code after} — the "toast" pattern used for short-lived confirmations. */
    public static void replyThenDelete(IReplyCallback event, Color accent, Duration after, String... lines) {
        event.replyComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .setEphemeral(true)
                .delay(after)
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    public static void replyThenDelete(IReplyCallback event, Color accent, String... lines) {
        replyThenDelete(event, accent, Duration.ofSeconds(5), lines);
    }

    // --- Edit (button/modal only — nothing to edit from a fresh slash command) ---

    public static void edit(IMessageEditCallback event, Color accent, String... lines) {
        event.editComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .queue();
    }

    /** Edits the originating message to a toast, then deletes it entirely after {@code after}. */
    public static void editThenDelete(IMessageEditCallback event, Color accent, Duration after, String... lines) {
        event.editComponents(List.of(toast(accent, lines)))
                .useComponentsV2(true)
                .delay(after)
                .flatMap(InteractionHook::deleteOriginal)
                .queue();
    }

    public static void editThenDelete(IMessageEditCallback event, Color accent, String... lines) {
        editThenDelete(event, accent, Duration.ofSeconds(5), lines);
    }

    // --- Error fallback, shared by every listener's catch block ---

    public static void replyError(IReplyCallback event) {
        try {
            if (!event.isAcknowledged()) {
                reply(event, DANGER, true, "An unexpected error occurred. Please try again or contact an admin.");
            }
        } catch (Exception ignored) {}
    }
}
