package com.runeclans.bot.suggestion;

import java.time.Instant;

/**
 * A member's suggestion, posted in the server's suggestions channel with up and down vote buttons. Staff decide it from the
 * dashboard, which closes voting and shows the outcome (and an optional note) on the message.
 */
public record Suggestion(long id, long guildId, long authorId, String content, Long channelId, Long messageId, Status status, String note,
                         Long decidedBy, Instant createdAt) {
    public static final int MAX_CONTENT = 1500;
    public static final int MAX_NOTE = 500;

    public enum Status {
        PENDING("Pending"), APPROVED("Approved"), DENIED("Denied"), IMPLEMENTED("Implemented");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public boolean open() {
        return status == Status.PENDING;
    }
}
