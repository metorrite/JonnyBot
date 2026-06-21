package com.younglings.bot.commands.coffer;

import com.younglings.bot.coffer.CofferRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles button interactions for the coffer transfer 2-part verification flow.
 * Only active when CofferService.REQUIRE_TRANSFER_VERIFICATION is true.
 */
@BService
public class CofferInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CofferInteractionListener.class);

    private static final String ACCEPT_PREFIX = "coffer_transfer_accept:";
    private static final String REJECT_PREFIX = "coffer_transfer_reject:";

    private final CofferRepository repository;

    public CofferInteractionListener(CofferRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!CofferService.REQUIRE_TRANSFER_VERIFICATION) return;

        String id = event.getComponentId();
        if (id.startsWith(ACCEPT_PREFIX)) {
            handleAccept(event, id.substring(ACCEPT_PREFIX.length()));
        } else if (id.startsWith(REJECT_PREFIX)) {
            handleReject(event, id.substring(REJECT_PREFIX.length()));
        }
    }

    private void handleAccept(ButtonInteractionEvent event, String transferIdStr) {
        long transferId = parseTransferId(event, transferIdStr);
        if (transferId < 0) return;

        CofferTransfer transfer = repository.getPendingTransfer(transferId);
        if (transfer == null) {
            event.reply("This transfer no longer exists or has already been resolved.").setEphemeral(true).queue();
            return;
        }
        if (transfer.toDiscordId() != event.getUser().getIdLong()) {
            event.reply("Only the intended recipient can accept this transfer.").setEphemeral(true).queue();
            return;
        }

        repository.acceptTransfer(transferId);
        event.reply("✅ Transfer of **" + GpAmountParser.format(transfer.amount()) + "** accepted.").queue();
    }

    private void handleReject(ButtonInteractionEvent event, String transferIdStr) {
        long transferId = parseTransferId(event, transferIdStr);
        if (transferId < 0) return;

        CofferTransfer transfer = repository.getPendingTransfer(transferId);
        if (transfer == null) {
            event.reply("This transfer no longer exists or has already been resolved.").setEphemeral(true).queue();
            return;
        }
        if (transfer.toDiscordId() != event.getUser().getIdLong()) {
            event.reply("Only the intended recipient can reject this transfer.").setEphemeral(true).queue();
            return;
        }

        repository.rejectTransfer(transferId);
        event.reply("❌ Transfer of **" + GpAmountParser.format(transfer.amount()) + "** rejected.").queue();
    }

    private long parseTransferId(ButtonInteractionEvent event, String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            event.reply("Invalid transfer ID.").setEphemeral(true).queue();
            return -1;
        }
    }
}
