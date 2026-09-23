package com.younglings.bot.embed;

/** One embed posted via {@code /embed post}, tracked so {@code /embed remove} can find it again. */
public record PostedEmbed(long postedEmbedId, long guildId, long channelId, long messageId, String embedType) {
}
