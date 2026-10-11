package com.younglings.bot.rolepanel;

import com.younglings.bot.BotListener;
import com.younglings.bot.rolepanel.RolePanel.RoleButton;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The buttons on a role panel. Pressing one takes or drops its role for the person who pressed it, and only they see the answer.
 * The panel and role are looked up from the database each time, so a button left on an old message can never hand out a role the
 * panel no longer lists, and a role that has since become unsafe is refused. Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class RolePanelListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(RolePanelListener.class);

    private final RolePanelRepository repository;

    public RolePanelListener(RolePanelRepository repository) {
        this.repository = repository;
    }

    /** What pressing a button does: roles to add and roles to remove, and what to tell the person. */
    record Change(List<Long> add, List<Long> remove, String message) {}

    /** Pure: decides the change, so the rules are testable without Discord. {@code held} is the roles the member has now. */
    static Change decide(RolePanel panel, RoleButton pressed, java.util.Set<Long> held, String roleName) {
        boolean has = held.contains(pressed.roleId());
        if (has) return new Change(List.of(), List.of(pressed.roleId()), "Removed **" + roleName + "**.");
        List<Long> remove = new ArrayList<>();
        if (panel.mode() == RolePanel.Mode.UNIQUE) {
            for (RoleButton other : panel.buttons()) if (other.roleId() != pressed.roleId() && held.contains(other.roleId())) remove.add(other.roleId());
        }
        return new Change(List.of(pressed.roleId()), remove, remove.isEmpty() ? "You now have **" + roleName + "**." : "You now have **" + roleName + "** instead.");
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(RolePanelMessage.ID_PREFIX)) return;

        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;
            String[] parts = id.substring(RolePanelMessage.ID_PREFIX.length()).split(":");
            if (parts.length != 2) return;
            long panelId = Long.parseLong(parts[0]);
            long roleId = Long.parseLong(parts[1]);

            Optional<RolePanel> found = repository.get(guild.getIdLong(), panelId);
            Optional<RoleButton> button = found.flatMap(p -> p.buttons().stream().filter(b -> b.roleId() == roleId).findFirst());
            if (found.isEmpty() || button.isEmpty()) {
                event.reply("That button isn't part of a role panel any more.").setEphemeral(true).queue();
                return;
            }
            Role role = guild.getRoleById(roleId);
            if (role == null || !RolePanelService.canGive(guild, role)) {
                event.reply("JonnyBot can't hand out that role right now. Tell a server admin.").setEphemeral(true).queue();
                return;
            }

            RolePanel panel = found.get();
            Change change = decide(panel, button.get(), new java.util.HashSet<>(member.getRoles().stream().map(Role::getIdLong).toList()), role.getName());
            List<Role> add = change.add().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();
            List<Role> remove = change.remove().stream().map(guild::getRoleById).filter(r -> r != null && RolePanelService.canGive(guild, r)).toList();

            event.deferReply(true).queue(hook -> guild.modifyMemberRoles(member, add, remove).reason("Role panel: " + panel.name()).queue(
                    done -> hook.editOriginal(change.message()).queue(),
                    error -> {
                        log.warn("Role panel {}: couldn't change the roles of {}", panelId, member.getId(), error);
                        hook.editOriginal("Something went wrong changing your roles. Tell a server admin.").queue();
                    }));
        } catch (Exception e) {
            log.error("Unhandled exception in role panel button '{}'", id, e);
        }
    }
}
