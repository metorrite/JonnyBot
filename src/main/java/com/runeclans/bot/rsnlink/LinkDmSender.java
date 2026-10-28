package com.runeclans.bot.rsnlink;

import java.util.Optional;

/**
 * The only thing the link service needs from a Discord bot (LINK-SYSTEM.md section 2): send a DM and resolve a username. JonnyBot's
 * gateway connection implements it today; after the split it becomes a small bot-side endpoint and this interface stays the same.
 * Implementations never log the ids or usernames they are given.
 */
public interface LinkDmSender {
    /** The Discord id for an exact, unique username among the servers the bot shares, or empty (not found, or two different people). */
    Optional<String> resolveUsername(String username);

    /** Sends the code DM. Returns false when Discord refuses (no shared server, DMs closed, unknown user). Never throws for those. */
    boolean sendCode(String discordId, String rsnDisplay, String userCode, String pairingId, long expiresInMinutes);

    /** Used when no bot is attached (the service running alone, or in tests): nothing can be sent. */
    LinkDmSender NONE = new LinkDmSender() {
        @Override
        public Optional<String> resolveUsername(String username) {
            return Optional.empty();
        }

        @Override
        public boolean sendCode(String discordId, String rsnDisplay, String userCode, String pairingId, long expiresInMinutes) {
            return false;
        }
    };
}
