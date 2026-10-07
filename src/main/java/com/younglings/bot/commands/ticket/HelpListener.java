package com.younglings.bot.commands.ticket;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The two buttons on the helper guidelines message: agreeing hands out the PVM Helper role, and stepping down takes it back. Only the
 * PVM Helper role is touched here; PVM Helper+ is given and removed by hand.
 */
@BService
public class HelpListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(HelpListener.class);

    private final TicketRepository repository;

    public HelpListener(TicketRepository repository) {
        this.repository = repository;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.equals(HelpOnboarding.ACCEPT_ID) && !id.equals(HelpOnboarding.LEAVE_ID)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;

            HelpSettings settings = repository.getHelpSettings(guild.getIdLong());
            Role role = settings.helperRoleId() == null ? null : guild.getRoleById(settings.helperRoleId());
            if (role == null) {
                Containers.replyEphemeral(event, Containers.WARNING, "The PVM Helper role isn't set up yet. Ask an admin to choose it in /configure.");
                return;
            }

            boolean has = member.getRoles().contains(role);
            if (id.equals(HelpOnboarding.ACCEPT_ID)) accept(event, guild, member, role, has);
            else leave(event, guild, member, role, has);
        } catch (Exception e) {
            log.error("Unhandled exception in helper guidelines button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void accept(ButtonInteractionEvent event, Guild guild, Member member, Role role, boolean has) {
        if (has) {
            Containers.replyEphemeral(event, Containers.INFO, "You're already a PVM Helper. Thank you for helping out.");
            return;
        }
        if (!guild.getSelfMember().canInteract(role)) {
            Containers.replyEphemeral(event, Containers.WARNING, "I can't hand out that role yet. An admin needs to move my role above it.");
            return;
        }
        event.deferReply(true).queue();
        guild.addRoleToMember(member, role).reason("Accepted the PVM Helper guidelines").queue(
                done -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS,
                        "✅ You're now a PVM Helper. You can see every ticket and join the ones you can help with."))).useComponentsV2(true).queue(),
                error -> {
                    log.warn("Couldn't give {} the PVM Helper role", member.getId(), error);
                    event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER, "I couldn't give you the role. Ask an admin to check my permissions."))).useComponentsV2(true).queue();
                });
    }

    private void leave(ButtonInteractionEvent event, Guild guild, Member member, Role role, boolean has) {
        if (!has) {
            Containers.replyEphemeral(event, Containers.INFO, "You don't have the PVM Helper role.");
            return;
        }
        if (!guild.getSelfMember().canInteract(role)) {
            Containers.replyEphemeral(event, Containers.WARNING, "I can't change that role yet. An admin needs to move my role above it.");
            return;
        }
        event.deferReply(true).queue();
        guild.removeRoleFromMember(member, role).reason("Stepped down as a PVM Helper").queue(
                done -> event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.SUCCESS, "You're no longer a PVM Helper. Thank you for what you did."))).useComponentsV2(true).queue(),
                error -> {
                    log.warn("Couldn't take the PVM Helper role from {}", member.getId(), error);
                    event.getHook().editOriginalComponents(List.of(Containers.toast(Containers.DANGER, "I couldn't remove the role. Ask an admin to check my permissions."))).useComponentsV2(true).queue();
                });
    }
}
