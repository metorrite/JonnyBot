package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.giveaway.Giveaway;
import com.runeclans.bot.giveaway.GiveawayRepository;
import com.runeclans.bot.giveaway.GiveawayService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.List;

/**
 * The Discord side of the dashboard's giveaways ({@link GiveawayAdminApi}), run by the outbox (T-456) so the page never waits on Discord:
 * <ul>
 *   <li>{@code giveaway.start}: creates the giveaway and posts its message (nothing is kept if Discord refuses). Not idempotent.</li>
 *   <li>{@code giveaway.end} / {@code giveaway.reroll}: draws (checking who is still in the server) and announces the winners. Not idempotent.</li>
 *   <li>{@code giveaway.delete}: deletes the message of a giveaway that is already gone from the list. Idempotent.</li>
 * </ul>
 */
@BService
public class GiveawayJobs implements DiscordJobHandler {
    static final String START = "giveaway.start";
    static final String END = "giveaway.end";
    static final String REROLL = "giveaway.reroll";
    static final String DELETE = "giveaway.delete";

    private final GiveawayRepository repository;
    private final GiveawayService service;

    public GiveawayJobs(GiveawayRepository repository, GiveawayService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public String kind() {
        return START;
    }

    @Override
    public List<String> kinds() {
        return List.of(START, END, REROLL, DELETE);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return DELETE.equals(kind);
    }

    /** Everything a start needs, checked by the website first. {@code endsAt} is fixed when it is asked for, not when the bot gets to it. */
    static DataObject startPayload(long channelId, String prize, int winners, Instant endsAt, Long requiredRoleId, Long winnerRoleId, boolean dmWinners, boolean reactionEntry) {
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("prize", prize).put("winners", winners).put("endsAt", endsAt.toString())
                .put("requiredRoleId", requiredRoleId == null ? null : Long.toString(requiredRoleId))
                .put("winnerRoleId", winnerRoleId == null ? null : Long.toString(winnerRoleId)).put("dmWinners", dmWinners).put("reactionEntry", reactionEntry);
    }

    static DataObject giveawayPayload(long giveawayId) {
        return DataObject.empty().put("giveawayId", Long.toString(giveawayId));
    }

    static DataObject deletePayload(long channelId, long messageId) {
        return DataObject.empty().put("channelId", Long.toString(channelId)).put("messageId", Long.toString(messageId));
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        try {
            return switch (job.kind()) {
                case START -> {
                    Giveaway g = service.start(guild, p.getLong("channelId"), p.getString("prize"), job.actorId(), p.getInt("winners"), Instant.parse(p.getString("endsAt")),
                            optionalId(p, "requiredRoleId"), optionalId(p, "winnerRoleId"), p.getBoolean("dmWinners", false), p.getBoolean("reactionEntry", false));
                    yield DataObject.empty().put("giveawayId", Long.toString(g.id())).put("messageId", g.messageId() == null ? null : Long.toString(g.messageId()));
                }
                case END -> winners(service.end(guild, giveaway(guild, p)));
                case REROLL -> winners(service.reroll(guild, giveaway(guild, p)));
                case DELETE -> {
                    GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, p.getLong("channelId"));
                    if (channel != null) deleteMessage(channel, p.getLong("messageId"));
                    yield DataObject.empty().put("deleted", true);
                }
                default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
            };
        } catch (GiveawayService.GiveawayException e) {
            throw new DiscordJobs.Refused(e.getMessage());
        }
    }

    private Giveaway giveaway(Guild guild, DataObject p) {
        return repository.get(guild.getIdLong(), p.getLong("giveawayId")).orElseThrow(() -> new DiscordJobs.Refused("That giveaway was deleted before the bot got to it."));
    }

    private static DataObject winners(Giveaway g) {
        return DataObject.empty().put("giveawayId", Long.toString(g.id())).put("winners", g.winners().size());
    }

    private static void deleteMessage(GuildMessageChannel channel, long messageId) {
        try {
            DiscordJobs.send(channel.deleteMessageById(messageId));
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
        }
    }

    private static Long optionalId(DataObject p, String key) {
        return p.isNull(key) ? null : Long.valueOf(p.getString(key));
    }
}
