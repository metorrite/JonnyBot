package com.younglings.bot.presence;

import com.younglings.bot.BotListener;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.RawGatewayEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps everyone who is online in the member cache, so "who's online" (the clan sites' online lists) names them all.
 *
 * <p>The bot caches only members who are online ({@code MemberCachePolicy.ONLINE}) and doesn't download member lists at startup
 * ({@code createLight}: no chunking). Discord's server data at login carries who is online then, but a member who comes online later
 * arrives as a bare presence update, with no name or roles, and JDA can't cache a member from that: they stayed off the online list
 * until they happened to post or join a voice channel. This listener sees those presence updates and asks the gateway for the missing
 * members (with their presence, so the cache keeps them), a few seconds' worth at a time per server. Gateway member requests aren't REST
 * calls, so there is no REST rate limit to spend.
 */
@BService
public class PresenceMemberLoader extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(PresenceMemberLoader.class);
    /** How long a server's batch collects before it is asked for. */
    private static final long FLUSH_DELAY_MS = 3_000;

    private final PresenceBatch batch = new PresenceBatch();
    private final ScheduledExecutorService flusher = BotScheduler.lane("presence-members");

    @Override
    public void onRawGateway(RawGatewayEvent event) {
        if (!"PRESENCE_UPDATE".equals(event.getType())) return;
        try {
            DataObject d = event.getPayload();
            if (d.isNull("guild_id") || d.isNull("user")) return;
            OnlineStatus status = OnlineStatus.fromKey(d.getString("status", "offline"));
            if (status == OnlineStatus.OFFLINE || status == OnlineStatus.UNKNOWN) return;
            long guildId = d.getUnsignedLong("guild_id");
            long userId = d.getObject("user").getUnsignedLong("id");
            JDA jda = event.getJDA();
            Guild guild = jda.getGuildById(guildId);
            if (guild == null || guild.getMemberById(userId) != null) return;
            boolean schedule;
            synchronized (batch) {
                schedule = batch.add(guildId, userId, System.currentTimeMillis());
            }
            if (schedule) flusher.schedule(() -> flush(jda, guildId), FLUSH_DELAY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("Couldn't read a presence update", e);
        }
    }

    private void flush(JDA jda, long guildId) {
        List<List<Long>> requests;
        synchronized (batch) {
            requests = batch.drain(guildId, System.currentTimeMillis());
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) return;
        for (List<Long> ids : requests) {
            // includePresence: the member arrives with their status, so the ONLINE cache policy keeps them
            guild.retrieveMembersByIds(true, ids.stream().mapToLong(Long::longValue).toArray())
                    .onError(e -> log.debug("Couldn't load {} members who came online in {}", ids.size(), guildId, e));
        }
    }
}
