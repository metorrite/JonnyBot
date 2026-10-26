package com.younglings.bot.internal;

import com.younglings.bot.discord.jobs.DiscordJob;
import com.younglings.bot.discord.jobs.DiscordJobHandler;
import com.younglings.bot.discord.jobs.DiscordJobs;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.lockdown.LockdownService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/**
 * The Discord side of the dashboard's Lockdown page ({@link LockdownAdminApi}), run by the outbox (T-456): changing a channel's permission
 * overwrites takes a Discord request per role, and a lockdown of the whole set one per channel, so the page no longer waits for them.
 * {@link LockdownService} keeps its own rules (a lock that fails half way is put back). Locking is not idempotent (a restart half way leaves it
 * unclear what is locked, so the job ends saying so); unlocking puts saved overwrites back, which is safe to repeat.
 */
@BService
public class LockdownJobs implements DiscordJobHandler {
    static final String LOCK = "lockdown.lock";
    static final String UNLOCK = "lockdown.unlock";
    static final String START = "lockdown.start";
    static final String END = "lockdown.end";

    private final LockdownService service;

    public LockdownJobs(LockdownService service) {
        this.service = service;
    }

    @Override
    public String kind() {
        return LOCK;
    }

    @Override
    public List<String> kinds() {
        return List.of(LOCK, UNLOCK, START, END);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return UNLOCK.equals(kind) || END.equals(kind);
    }

    static DataObject lockPayload(long channelId, String reason) {
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("reason", reason);
    }

    static DataObject unlockPayload(long channelId) {
        return DataObject.empty().put("channelId", Long.toString(channelId));
    }

    static DataObject startPayload(String reason) {
        return DataObject.empty().put("reason", reason);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        return switch (job.kind()) {
            case LOCK -> {
                StandardGuildMessageChannel channel = channel(guild, p);
                LockdownService.Outcome outcome = service.lock(guild, channel, job.actorId(), p.getString("reason", ""), false);
                yield outcome(outcome, channel, true);
            }
            case UNLOCK -> {
                StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, p.getLong("channelId"));
                if (channel == null) {
                    service.forget(guild.getIdLong(), p.getLong("channelId"));
                    yield DataObject.empty().put("outcome", "gone");
                }
                yield outcome(service.unlock(guild, channel, job.actorId()), channel, false);
            }
            case START -> {
                int[] r = service.start(guild, job.actorId(), p.getString("reason", ""));
                if (r[2] > 0) throw new DiscordJobs.Refused(Text.of(guild, "dashboard.channels_were_locked_but_couldn_t", r[0], r[2]));
                yield DataObject.empty().put("locked", r[0]).put("already", r[1]);
            }
            case END -> {
                int[] r = service.end(guild, job.actorId());
                if (r[1] > 0) throw new DiscordJobs.Refused(Text.of(guild, "dashboard.channels_were_unlocked_but_couldn_t", r[0], r[1]));
                yield DataObject.empty().put("unlocked", r[0]);
            }
            default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
        };
    }

    private static StandardGuildMessageChannel channel(Guild guild, DataObject p) {
        StandardGuildMessageChannel channel = guild.getChannelById(StandardGuildMessageChannel.class, p.getLong("channelId"));
        if (channel == null) throw new DiscordJobs.Refused("That channel no longer exists.");
        return channel;
    }

    /** Done, or why not. "Already locked" / "not locked" mean someone got there first: the channel is as asked, so the job is done. */
    static DataObject outcome(LockdownService.Outcome outcome, StandardGuildMessageChannel channel, boolean locking) {
        Guild guild = channel.getGuild();
        return switch (outcome) {
            case DONE, ALREADY, NOT_LOCKED -> DataObject.empty().put("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT));
            case NO_PERMISSION -> throw new DiscordJobs.Refused(Text.of(guild, "dashboard.jonnybot_needs_to_see_send_messages", channel.getName()));
            case FAILED -> throw new DiscordJobs.Refused(Text.of(guild, locking ? "dashboard.lockdown_refused_put_back" : "dashboard.lockdown_refused_left_locked", channel.getName()));
        };
    }
}
