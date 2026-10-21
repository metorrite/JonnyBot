package com.younglings.bot.rsnlink.bridge;

import com.younglings.bot.BotListener;
import com.younglings.bot.rsnlink.LinkService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two buttons on the DM fallback's code message: "Not me" denies that pairing; "Stop these messages" also opts the account out of code
 * DMs for 90 days. Only the person the DM was sent to can use them (the service checks the clicker against the pairing's target).
 */
@BService
public class RsnLinkDmButtons extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RsnLinkDmButtons.class);

    private final RsnLinkModule module;

    public RsnLinkDmButtons(RsnLinkModule module) {
        this.module = module;
    }

    @Override
    public void onButtonInteraction(@NonNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean notMe = id.startsWith(JdaLinkDmSender.NOT_ME);
        boolean stop = id.startsWith(JdaLinkDmSender.STOP);
        if (!notMe && !stop) return;
        LinkService service = module.service();
        if (service == null) {
            event.reply("Linking isn't available right now.").setEphemeral(true).queue();
            return;
        }
        String pairingId = id.substring((notMe ? JdaLinkDmSender.NOT_ME : JdaLinkDmSender.STOP).length());
        try {
            String answer;
            if (notMe) {
                answer = service.dmFlow().notMe(pairingId, event.getUser().getId())
                        ? "Thanks. That request is cancelled and nothing was linked."
                        : "That request has already ended. Nothing was linked.";
            } else {
                service.dmFlow().stop(pairingId, event.getUser().getId());
                answer = "Done. You won't get link codes by DM for 90 days.";
            }
            event.editComponents().queue(ok -> event.getHook().sendMessage(answer).queue(), err -> event.getHook().sendMessage(answer).queue());
        } catch (RuntimeException e) {
            log.warn("RS Link DM button failed ({})", e.getClass().getSimpleName());
            event.reply("Something went wrong. Try again in a minute.").setEphemeral(true).queue();
        }
    }
}
