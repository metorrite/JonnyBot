package com.runeclans.bot.discord;

import com.runeclans.bot.config.BotRole;
import net.dv8tion.jda.api.JDA;

/**
 * Who may create or replace the application's emojis (T-702, docs/SCALING-STATE.md section 4.4). Emojis belong to the application, not to a
 * shard, so two shards or two processes syncing at once could each upload the same missing icon. Only the gateway shard 0 uploads; every
 * other shard and process only reads the list and uses what is there.
 */
public final class EmojiUploads {
    private EmojiUploads() {}

    /** Whether this connection uploads missing or changed application emojis: shard 0 of a gateway process (a stand-alone JDA is shard 0). */
    public static boolean mayUpload(JDA jda) {
        JDA.ShardInfo shard = jda.getShardInfo();
        return BotRole.current().gateway() && (shard == null || shard.getShardId() == 0);
    }
}
