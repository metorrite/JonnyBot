package com.younglings.bot.rsnlink.bridge;

import com.younglings.bot.BotListener;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.runeclans.RuneClansHubClient;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;

/**
 * The buttons on a code DM sent for runeclans-hub ({@link HubDmRelay}, T-166): "Not me" and "Stop these messages" go to the hub's
 * first-party {@code /v1/link/dm/not-me} and {@code /v1/link/dm/stop} with the bot's API key and the clicker's id; the hub checks the clicker
 * is the person the DM went to. The hub is called off the gateway thread, after Discord has been told the click arrived.
 */
@BService
public class HubDmButtons extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(HubDmButtons.class);

    private final RuneClansHubClient hub;
    private final ScheduledExecutorService lane = BotScheduler.lane("rsnlink-hub-dm");

    public HubDmButtons(RuneClansHubClient hub) {
        this.hub = hub;
    }

    @Override
    public void onButtonInteraction(@NonNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean notMe = id.startsWith(HubDmRelay.NOT_ME);
        boolean stop = id.startsWith(HubDmRelay.STOP);
        if (!notMe && !stop) return;
        if (!hub.enabled()) {
            event.reply(Text.of(event, "rsnlink.linking_isn_t_available_right_now")).setEphemeral(true).queue();
            return;
        }
        String pairingId = id.substring((notMe ? HubDmRelay.NOT_ME : HubDmRelay.STOP).length());
        String clicker = event.getUser().getId();
        String failed = Text.of(event, "rsnlink.something_went_wrong_try_again_in");
        event.deferEdit().queue(ok -> lane.execute(() -> {
            String answer = press(hub, notMe, pairingId, clicker);
            if (answer == null) {
                event.getHook().sendMessage(failed).setEphemeral(true).queue();
                return;
            }
            event.getHook().editOriginalComponents().queue(done -> event.getHook().sendMessage(answer).queue(), err -> event.getHook().sendMessage(answer).queue());
        }), err -> log.info("RuneLink hub DM button couldn't be acknowledged ({})", err.getClass().getSimpleName()));
    }

    /** Passes one press to the hub; the words to show, or null when the hub didn't take it. */
    static String press(RuneClansHubClient hub, boolean notMe, String pairingId, String clickerId) {
        DataObject body = DataObject.empty().put("pairingId", pairingId).put("discordId", clickerId);
        RuneClansHubClient.Answer a = hub.call("POST", notMe ? "/v1/link/dm/not-me" : "/v1/link/dm/stop", body.toString());
        if (a == null || !a.ok() || a.json() == null) {
            log.info("RuneLink hub DM button: the hub didn't take it ({})", a == null ? "unreachable" : a.status());
            return null;
        }
        if (!notMe) return "Done. You won't get link codes by DM for 90 days.";
        return a.json().getBoolean("denied", false)
                ? "Thanks. That request is cancelled and nothing was linked."
                : "That request has already ended. Nothing was linked.";
    }
}
