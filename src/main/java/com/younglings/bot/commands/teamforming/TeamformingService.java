package com.younglings.bot.commands.teamforming;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the teamforming panel message and resolves dropdown/button interactions to actual
 * Discord role assignments. Entirely stateless — no database involved. Every role this feature
 * touches is looked up by its exact (case-insensitive) name via {@link TeamformingCatalog}, and
 * created on first use if it doesn't exist yet.
 */
@BService
public class TeamformingService {
    private static final Logger log = LoggerFactory.getLogger(TeamformingService.class);

    private static final Color ACCENT_COLOR = new Color(0xB3, 0x00, 0x00); // dark red, matches the clan logo
    private static final String LOGO_RESOURCE = "images/clan_logo.png";
    private static final String LOGO_FILENAME = "clan_logo.png";

    public static final String TOGGLE_PREFIX = "teamforming_toggle:";
    public static final String SELECT_PREFIX = "teamforming_select:";

    /** What changed on a member's roles after a dropdown submission, for the confirmation reply. */
    public record SyncResult(List<String> added, List<String> removed) {}

    // --- Panel building ---

    /** Builds the full teamforming panel as a single Components V2 message. */
    public MessageCreateData buildPanelMessage() {
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(buildHeader());
        children.add(ActionRow.of(buildToggleButtons()));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        for (TeamformingSection section : TeamformingCatalog.SECTIONS) {
            children.add(TextDisplay.of("**" + section.emoji() + " " + section.title() + "**\n" + section.prompt()));
            children.add(ActionRow.of(buildSelectMenu(section)));
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("-# Roles update instantly based on your selections."));

        Container container = Container.of(children).withAccentColor(ACCENT_COLOR);

        return new MessageCreateBuilder()
                .useComponentsV2()
                .setComponents(container)
                .build();
    }

    /** Header block: clan logo thumbnail (if the resource is present) next to the title/blurb. */
    private ContainerChildComponent buildHeader() {
        TextDisplay headerText = TextDisplay.of(
                "### Younglings Teamforming\nClick the buttons and dropdowns below to assign yourself these boss event roles!");

        FileUpload logo = loadLogo();
        if (logo == null) {
            return headerText;
        }

        return Section.of(Thumbnail.fromFile(logo).withDescription("Clan logo"), headerText);
    }

    private FileUpload loadLogo() {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(LOGO_RESOURCE)) {
            if (stream == null) {
                log.warn("Teamforming logo resource '{}' not found on the classpath; posting panel without a thumbnail.",
                        LOGO_RESOURCE);
                return null;
            }
            return FileUpload.fromData(stream.readAllBytes(), LOGO_FILENAME);
        } catch (IOException e) {
            log.warn("Failed to load teamforming logo resource '{}'; posting panel without a thumbnail.",
                    LOGO_RESOURCE, e);
            return null;
        }
    }

    private List<Button> buildToggleButtons() {
        List<Button> buttons = new ArrayList<>();
        for (TeamformingToggle toggle : TeamformingCatalog.TOGGLES) {
            buttons.add(Button.secondary(TOGGLE_PREFIX + toggle.roleName(), toggle.emoji() + " " + toggle.label()));
        }
        return buttons;
    }

    private StringSelectMenu buildSelectMenu(TeamformingSection section) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(SELECT_PREFIX + section.key())
                .setPlaceholder(section.selectPlaceholder())
                .setRequiredRange(0, section.options().size());

        for (TeamformingOption option : section.options()) {
            builder.addOption(option.label(), option.roleName(), option.description());
        }

        return builder.build();
    }

    // --- Role management ---

    /** Finds an existing role by exact (case-insensitive) name, or creates it if none exists. */
    public Role ensureRole(Guild guild, String roleName) {
        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) return existing.getFirst();

        Role created = guild.createRole()
                .setName(roleName)
                .setMentionable(true)
                .complete();
        log.info("Created teamforming role '{}' in guild {}", roleName, guild.getIdLong());
        return created;
    }

    /**
     * Ensures every role in the teamforming catalog exists in the guild, creating any that are
     * missing. Meant to be run once from {@code /teamforming post}, so every dropdown/button works
     * immediately once the panel is live.
     *
     * @return the names of roles that were newly created (empty if they all already existed)
     */
    public List<String> ensureAllRolesExist(Guild guild) {
        List<String> created = new ArrayList<>();
        for (String roleName : TeamformingCatalog.allRoleNames()) {
            if (!guild.getRolesByName(roleName, true).isEmpty()) continue;
            try {
                guild.createRole().setName(roleName).setMentionable(true).complete();
                created.add(roleName);
                log.info("Created teamforming role '{}' in guild {}", roleName, guild.getIdLong());
            } catch (Exception e) {
                log.warn("Failed to create teamforming role '{}' in guild {}", roleName, guild.getIdLong(), e);
            }
        }
        return created;
    }

    /**
     * Deletes every role in the teamforming catalog that currently exists in the guild. Intended
     * for cleaning up a dev/test bot's server after testing (the dev bot targets the same live
     * Discord server, since there's no separate test server) — run this before switching back to
     * the production bot so the same role names can be created cleanly there.
     *
     * @return the names of roles that were actually found and deleted
     */
    public List<String> deleteAllCatalogRoles(Guild guild) {
        List<String> deleted = new ArrayList<>();
        for (String roleName : TeamformingCatalog.allRoleNames()) {
            for (Role role : guild.getRolesByName(roleName, true)) {
                try {
                    role.delete().complete();
                    deleted.add(roleName);
                    log.info("Deleted teamforming role '{}' from guild {}", roleName, guild.getIdLong());
                } catch (Exception e) {
                    log.warn("Failed to delete teamforming role '{}' from guild {}", roleName, guild.getIdLong(), e);
                }
            }
        }
        return deleted;
    }

    // --- Interaction handling ---

    /**
     * Toggles a single role on a member: removes it if they have it, adds it if they don't.
     *
     * @return {@code true} if the member now has the role, {@code false} if it was just removed
     */
    public boolean toggleRole(Guild guild, Member member, String roleName) {
        Role role = ensureRole(guild, roleName);
        boolean hadRole = member.getRoles().contains(role);

        if (hadRole) {
            guild.removeRoleFromMember(member, role).complete();
        } else {
            guild.addRoleToMember(member, role).complete();
        }

        return !hadRole;
    }

    /**
     * Syncs a member's roles for one section to exactly match their latest dropdown submission:
     * grants roles for newly selected options and revokes roles for this section's options that
     * are no longer selected. Roles outside this section are never touched.
     */
    public SyncResult syncSelection(Guild guild, Member member, TeamformingSection section, List<String> selectedRoleNames) {
        Set<String> selected = new HashSet<>(selectedRoleNames);

        Set<String> memberRoleNames = new HashSet<>();
        for (Role role : member.getRoles()) memberRoleNames.add(role.getName());

        List<Role> toAdd = new ArrayList<>();
        List<Role> toRemove = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (TeamformingOption option : section.options()) {
            String roleName = option.roleName();
            boolean isSelected = selected.contains(roleName);
            boolean hasRole = memberRoleNames.contains(roleName);

            if (isSelected && !hasRole) {
                toAdd.add(ensureRole(guild, roleName));
                added.add(roleName);
            } else if (!isSelected && hasRole) {
                guild.getRolesByName(roleName, true).stream().findFirst().ifPresent(role -> {
                    toRemove.add(role);
                    removed.add(roleName);
                });
            }
        }

        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            guild.modifyMemberRoles(member, toAdd, toRemove).complete();
        }

        return new SyncResult(added, removed);
    }
}
