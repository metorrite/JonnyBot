package com.younglings.bot.giveaway;

import java.time.Instant;
import java.util.List;

/**
 * A giveaway: a message with an Enter button. People enter (and leave again) with the button; when the time is up the bot draws the
 * winners from everyone who is still entered and still in the server. An optional role is required to enter.
 */
public record Giveaway(long id, long guildId, long channelId, Long messageId, String prize, long hostId, int winnerCount, Instant endsAt,
                       Long requiredRoleId, Status status, List<Long> winners) {
    public static final int MAX_WINNERS = 20;
    public static final int MAX_PRIZE = 200;

    public enum Status { OPEN, ENDED }

    public boolean open() {
        return status == Status.OPEN;
    }
}
