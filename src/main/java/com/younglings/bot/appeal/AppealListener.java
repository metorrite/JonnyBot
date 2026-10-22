package com.younglings.bot.appeal;

import com.younglings.bot.BotListener;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletionException;

/**
 * Approve and Reject on an appeal's staff ticket (T-033). Approve lifts the ban or timeout as the staff member who pressed it, so Discord's and the
 * server's moderation rules decide whether they may; Reject asks for the reason the person will see. The dashboard's Appeals page does the same.
 */
@BService
public class AppealListener extends ListenerAdapter implements BotListener {
    private static final String REJECT_MODAL = "appeal_reject_modal:";
    private static final String NOTE = "appeal_note";

    private final AppealService service;

    public AppealListener(AppealService service) {
        this.service = service;
    }

    private static Long idAfter(String id, String prefix) {
        try {
            return id.startsWith(prefix) ? Long.parseLong(id.substring(prefix.length())) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        if (event.getGuild() == null || event.getMember() == null) return;
        String id = event.getComponentId();
        Long approve = idAfter(id, AppealService.APPROVE);
        Long reject = idAfter(id, AppealService.REJECT);
        if (approve != null) {
            event.deferReply(true).queue();
            try {
                service.approve(event.getGuild(), event.getMember(), approve, null).whenComplete((done, error) -> {
                    String text = error == null ? "Approved: lifted, and the appeal is closed." : messageOf(error);
                    event.getHook().sendMessageComponents(java.util.List.of(Containers.toast(error == null ? Containers.SUCCESS : Containers.WARNING, text))).useComponentsV2(true).setEphemeral(true).queue();
                });
            } catch (AppealService.AppealProblem e) {
                event.getHook().sendMessageComponents(java.util.List.of(Containers.toast(Containers.WARNING, e.getMessage()))).useComponentsV2(true).setEphemeral(true).queue();
            }
        } else if (reject != null) {
            TextInput note = TextInput.create(NOTE, TextInputStyle.PARAGRAPH).setRequired(true).setRequiredRange(1, AppealRules.MAX_NOTE)
                    .setPlaceholder("They see this on the appeal page.").build();
            event.replyModal(Modal.create(REJECT_MODAL + reject, "Reject the appeal").addComponents(Label.of("Why", note)).build()).queue();
        }
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        Long reject = idAfter(event.getModalId(), REJECT_MODAL);
        if (reject == null || event.getGuild() == null || event.getMember() == null) return;
        var value = event.getValue(NOTE);
        try {
            service.reject(event.getGuild(), event.getMember(), reject, value == null ? "" : value.getAsString());
            Containers.replyEphemeral(event, Containers.SUCCESS, "Turned down. The appeal is closed and they can see why.");
        } catch (AppealService.AppealProblem e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
        }
    }

    static String messageOf(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof AppealService.AppealProblem p ? p.getMessage() : "That didn't work: " + cause.getMessage();
    }
}
