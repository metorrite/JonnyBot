package com.runeclans.bot.hubsites;

import com.runeclans.bot.BotListener;
import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.runeclans.HubFeedListener;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import com.runeclans.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Requests in Discord (T-734, runeclans-hub BUILD-A-BOT section 10). The hub is the one store; a card is a copy:
 * <ul>
 *   <li>{@code request.created} / {@code request.changed} from the hub's feed (sent only to the bot running {@code requests.discord} for the
 *   site) become a {@code request.card} outbox job, newest wins ({@link RequestCardJobs}).</li>
 *   <li>{@code community.member.role} becomes a {@code community.role} job.</li>
 *   <li>A button press goes to the hub with the rev the card showed ({@code If-Match}); the hub decides whether the presser may handle
 *   requests and answers the request as it is now, which the card then shows. Refusals are told only to the presser.</li>
 * </ul>
 */
@BService
public class RequestCards extends ListenerAdapter implements BotListener, HubFeedListener {
    private static final Logger log = LoggerFactory.getLogger(RequestCards.class);
    static final String CREATED = "request.created";
    static final String CHANGED = "request.changed";
    static final String MEMBER_ROLE = "community.member.role";
    private static final EnumSet<Message.MentionType> NO_PINGS = EnumSet.noneOf(Message.MentionType.class);

    private final DiscordOutbox outbox;
    private final RuneClansHubClient hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("request-cards");

    /** Jobs not yet in the outbox, by their dedupe key (newest wins). */
    private final Map<String, Job> waiting = new ConcurrentHashMap<>();

    public RequestCards(DiscordOutbox outbox, RuneClansHubClient hub) {
        this.outbox = outbox;
        this.hub = hub;
        lane.scheduleWithFixedDelay(() -> {
            if (!waiting.isEmpty()) enqueueWaiting();
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onHubFeed(String kind, DataObject data) {
        Job job = job(kind, data);
        if (job == null) return;
        waiting.put(job.dedupe(), job);
        lane.execute(this::enqueueWaiting);
    }

    /**
     * Hands the waiting jobs to the outbox. One the outbox couldn't take (its database briefly down) stays waiting, newest per request, and
     * is tried again every few seconds: the feed's cursor has already moved past it. Lost only if the bot stops first; a site admin's
     * "Send the cards again" puts such cards right.
     */
    void enqueueWaiting() {
        for (Job job : List.copyOf(waiting.values())) {
            try {
                outbox.enqueue(job.kind(), job.guildId(), 0L, job.dedupe(), job.payload());
                waiting.remove(job.dedupe(), job);
            } catch (RuntimeException e) {
                log.warn("A request card couldn't be queued yet ({}); trying again shortly", e.getClass().getSimpleName());
                return;
            }
        }
    }

    /** An outbox job for one hub feed entry. */
    record Job(String kind, long guildId, String dedupe, DataObject payload) {}

    /** The outbox job for a feed entry of ours, or null for anything else (or anything malformed). */
    static Job job(String kind, DataObject data) {
        if (data == null || !(kind.equals(CREATED) || kind.equals(CHANGED) || kind.equals(MEMBER_ROLE))) return null;
        DataObject inner = data.optObject("data").orElse(null);
        if (inner == null) return null;
        String guild = inner.getString("guildId", null);
        if (guild == null || !guild.matches("\\d{15,20}")) return null;
        long guildId = Long.parseLong(guild);
        if (kind.equals(MEMBER_ROLE)) {
            String role = inner.getString("roleId", null);
            String member = inner.getString("discordId", null);
            String community = inner.getString("communityId", "");
            if (role == null || member == null || !role.matches("\\d{15,20}") || !member.matches("\\d{15,20}")) return null;
            DataObject payload = DataObject.empty().put("roleId", role).put("discordId", member).put("give", inner.getBoolean("give", false));
            return new Job(RequestCardJobs.ROLE, guildId, "community-role:" + community + ":" + member, payload);
        }
        DataObject request = inner.optObject("request").orElse(null);
        if (request == null || RequestCardLogic.parse(RequestCardLogic.buttonId("new", request.getString("id", ""), 0)) == null) return null;
        String channel = inner.getString("channelId", null);
        DataObject payload = DataObject.empty().put("request", request);
        if (channel != null && channel.matches("\\d{15,20}")) payload.put("channelId", channel);
        return new Job(RequestCardJobs.CARD, guildId, "request-card:" + request.getString("id"), payload);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        RequestCardLogic.Press press = RequestCardLogic.parse(event.getComponentId());
        if (press == null) return;
        Member member = event.getMember();
        if (member == null || event.getGuild() == null) return;
        String guildId = event.getGuild().getId();
        String discordId = member.getId();
        boolean administrator = member.hasPermission(Permission.ADMINISTRATOR);
        boolean owner = member.isOwner();
        event.deferEdit().queue(hook -> lane.execute(() -> show(event, hook, RequestCardLogic.press(press, guildId, discordId, administrator, owner, hub::call))),
                err -> log.info("A request card press couldn't be acknowledged ({})", err.getClass().getSimpleName()));
    }

    /** Shows the hub's answer: the card as it is now, and a private note to the presser when there is one. */
    private void show(ButtonInteractionEvent event, InteractionHook hook, RequestCardLogic.Outcome outcome) {
        if (outcome.card() != null) {
            RequestCardLogic.Card card = RequestCardLogic.card(outcome.card(), (key, args) -> Text.of(event, key, args));
            hook.editOriginalComponents(List.of(RequestCardJobs.view(card))).useComponentsV2(true).setAllowedMentions(NO_PINGS)
                    .queue(ok -> { }, err -> log.info("A request card couldn't be updated ({})", err.getClass().getSimpleName()));
        }
        String note = outcome.noteKey() != null ? Text.of(event, outcome.noteKey()) : outcome.hubMessage();
        if (note != null) {
            hook.sendMessageComponents(List.of(Containers.toast(outcome.card() != null ? Containers.INFO : Containers.WARNING, note))).useComponentsV2(true)
                    .setEphemeral(true).setAllowedMentions(NO_PINGS).queue(ok -> { }, err -> { });
        }
    }
}
