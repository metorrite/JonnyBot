package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.runescape.RsnRenameRepository;
import com.younglings.bot.runescape.RsnRenameService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Confirm/Reject buttons for a possible rename {@link RsnRenameService} detected — these arrive on
 * both the admin alert channel message and the linked player's own DM (if one was sent), so unlike
 * every other listener in this package, this one deliberately never touches {@code event.getGuild()}
 * — a DM interaction has none, and nothing here needs one (the candidate row already carries its
 * guild ID for {@link RsnRenameService} to use internally).
 */
@BService
public class RsnRenameInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(RsnRenameInteractionListener.class);

    private final RsnRenameService renameService;

    public RsnRenameInteractionListener(RsnRenameService renameService) {
        this.renameService = renameService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean isConfirm = id.startsWith("rsnrename_confirm:");
        boolean isReject = id.startsWith("rsnrename_reject:");
        if (!isConfirm && !isReject) return;

        try {
            long candidateId = Long.parseLong(id.split(":", 2)[1]);

            RsnRenameRepository.RenameCandidate candidate = renameService.getCandidate(candidateId);
            if (candidate == null) {
                Containers.edit(event, Containers.WARNING, "This rename report no longer exists.");
                return;
            }
            if (!"PENDING".equals(candidate.status())) {
                Containers.edit(event, Containers.INFO, "Already handled — this was resolved as **" + candidate.status() + "**.");
                return;
            }

            boolean ok = isConfirm
                    ? renameService.confirm(candidateId, event.getUser().getIdLong())
                    : renameService.reject(candidateId, event.getUser().getIdLong());

            if (!ok) {
                Containers.edit(event, Containers.WARNING, "Couldn't process this — it may have just been handled elsewhere.");
                return;
            }

            Containers.edit(event, isConfirm ? Containers.SUCCESS : Containers.DANGER,
                    isConfirm
                            ? "Confirmed: **" + candidate.oldRsn() + "** → **" + candidate.newRsn() + "**. The linked account (if any) has been updated."
                            : "Rejected — **" + candidate.oldRsn() + "** and **" + candidate.newRsn() + "** will be treated as unrelated.");

        } catch (Exception e) {
            log.error("Unhandled exception in rsnrename button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }
}
