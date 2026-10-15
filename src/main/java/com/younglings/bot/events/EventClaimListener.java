package com.younglings.bot.events;

import com.younglings.bot.BotListener;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.events.EventRepository.Event;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The Approve and Deny buttons under a player's proof in the event's review channel. Staff and the event's own organisers may use them; the decision
 * is the same {@link EventService#decideClaim} the dashboard uses, so it is counted once whichever is clicked first. Deny asks for the reason, which is
 * sent to the player. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class EventClaimListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(EventClaimListener.class);
    static final String APPROVE = "event_claim:approve:";
    static final String DENY = "event_claim:deny:";
    static final String DENY_MODAL = "event_claim_deny:";

    private final EventService service;
    private final AdminRoleFilter adminRoleFilter;
    private Executor worker = Executors.newFixedThreadPool(2, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "event-claim-decisions");
        thread.setDaemon(true);
        return thread;
    });

    public EventClaimListener(EventService service, AdminRoleFilter adminRoleFilter) {
        this.service = service;
        this.adminRoleFilter = adminRoleFilter;
    }

    /** For tests: do the work on the calling thread. */
    void useExecutor(Executor executor) {
        this.worker = executor;
    }

    /** The buttons to put under a submission. */
    public static ActionRow buttons(long eventId, long claimId) {
        return ActionRow.of(Button.success(APPROVE + eventId + ":" + claimId, "Approve"), Button.danger(DENY + eventId + ":" + claimId, "Deny"));
    }

    private record Ids(long eventId, long claimId) {}

    private static Ids idsOf(String componentId, String prefix) {
        String[] parts = componentId.substring(prefix.length()).split(":");
        if (parts.length != 2) return null;
        try {
            return new Ids(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean mayDecide(Guild guild, Member member, Event event) {
        boolean staff = member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
        return member != null && service.mayOrganise(event, member.getIdLong(), staff);
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean approve = id.startsWith(APPROVE);
        if (!approve && !id.startsWith(DENY)) return;
        Guild guild = event.getGuild();
        if (guild == null) return;
        Ids ids = idsOf(id, approve ? APPROVE : DENY);
        if (ids == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "That button is out of date.");
            return;
        }
        try {
            Event target = service.event(guild.getIdLong(), ids.eventId());
            if (!mayDecide(guild, event.getMember(), target)) {
                Containers.replyEphemeral(event, Containers.WARNING, "Only staff and this event's organisers can decide proof.");
                return;
            }
            if (!approve) {
                TextInput reason = TextInput.create("reason", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Why it was denied, so the player can fix it")
                        .setMaxLength(EventRules.MAX_NOTE)
                        .build();
                event.replyModal(Modal.create(DENY_MODAL + ids.eventId() + ":" + ids.claimId(), "Deny this proof")
                        .addComponents(Label.of("Reason", reason)).build()).queue();
                return;
            }
            event.deferEdit().queue();
            Member who = event.getMember();
            String content = event.getMessage().getContentRaw();
            worker.execute(() -> decide(guild, ids, true, "", who, content, event.getHook()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not handle an event proof button", e);
            Containers.replyEphemeral(event, Containers.WARNING, "Something went wrong. Try the dashboard.");
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(DENY_MODAL)) return;
        Guild guild = event.getGuild();
        if (guild == null) return;
        Ids ids = idsOf(id, DENY_MODAL);
        if (ids == null) return;
        try {
            Event target = service.event(guild.getIdLong(), ids.eventId());
            if (!mayDecide(guild, event.getMember(), target)) {
                Containers.replyEphemeral(event, Containers.WARNING, "Only staff and this event's organisers can decide proof.");
                return;
            }
            var reasonValue = event.getValue("reason");
            String reason = reasonValue == null ? "" : reasonValue.getAsString().strip();
            if (reason.isEmpty()) {
                Containers.replyEphemeral(event, Containers.WARNING, "Say why it was denied, so the player can fix it.");
                return;
            }
            event.deferEdit().queue();
            Member who = event.getMember();
            String content = event.getMessage() == null ? "" : event.getMessage().getContentRaw();
            worker.execute(() -> decide(guild, ids, false, reason, who, content, event.getHook()));
        } catch (EventProblem p) {
            Containers.replyEphemeral(event, Containers.WARNING, p.getMessage());
        } catch (RuntimeException e) {
            log.error("Could not deny an event proof", e);
            Containers.replyEphemeral(event, Containers.WARNING, "Something went wrong. Try the dashboard.");
        }
    }

    private void decide(Guild guild, Ids ids, boolean approve, String reason, Member who, String content, net.dv8tion.jda.api.interactions.InteractionHook hook) {
        String verdict;
        try {
            service.decideClaim(guild.getIdLong(), ids.eventId(), ids.claimId(), approve, reason, who.getIdLong());
            verdict = approve ? "✅ Approved by " + who.getAsMention() : "❌ Denied by " + who.getAsMention() + ": " + reason;
        } catch (EventProblem p) {
            // most often someone else decided it first (here or on the dashboard); say so and take the buttons away
            verdict = "⚠️ " + p.getMessage();
        } catch (RuntimeException e) {
            log.error("Could not decide event claim {}", ids.claimId(), e);
            hook.sendMessage("I couldn't save that just now. Try again, or use the dashboard.").setEphemeral(true).queue();
            return;
        }
        String text = content + "\n" + verdict;
        hook.editOriginal(text.length() > 1900 ? text.substring(0, 1900) : text).setComponents(List.of()).setAllowedMentions(List.of()).queue();
    }
}
