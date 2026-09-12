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
import net.dv8tion.jda.api.requests.restaction.RoleAction;
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
 * Discord role assignments. Entirely stateless — no database, no in-memory cache. Every role this
 * feature touches is looked up by its exact (case-insensitive) name via {@link TeamformingCatalog},
 * created on first use if missing, and every interaction reads a member's roles live at the time
 * of that interaction. That means a bot restart loses nothing (there's nothing cached to lose),
 * and roles changed by an admin outside the panel are reflected immediately the next time someone
 * interacts — Discord's own role assignments <i>are</i> the source of truth here.
 * <p>
 * The one thing that can't be made "live": Discord does not support per-viewer default/pre-checked
 * values on a shared, persistent message's select menu — everyone always sees every dropdown as
 * empty when they open it, regardless of what they already hold. Building the dropdowns as
 * "submit = my complete desired state, remove anything unchecked" would silently strip roles a
 * member already had just because they didn't know to re-check them. So the dropdowns are
 * add-only ({@link #applySelection}) — selecting a tag can only grant it, never revoke one you
 * didn't select — and removal happens through a separate, personalized ephemeral menu
 * ({@link #getHeldSectionRoleNames}/{@link #removeRoles}) that's built fresh from the member's
 * actual current roles each time it's opened, so it's always accurate.
 */
@BService
public class TeamformingService {
    private static final Logger log = LoggerFactory.getLogger(TeamformingService.class);

    private static final Color PANEL_ACCENT_COLOR = new Color(0xB3, 0x00, 0x00); // dark red, matches the clan logo
    private static final String LOGO_RESOURCE = "images/clan_logo.png";
    private static final String LOGO_FILENAME = "clan_logo.png";

    public static final String TOGGLE_PREFIX = "teamforming_toggle:";
    public static final String SELECT_PREFIX = "teamforming_select:";
    public static final String MANAGE_TAGS_BUTTON_ID = "teamforming_manage_tags";
    public static final String REMOVE_SELECT_ID = "teamforming_remove_submit";

    // --- Panel building ---

    /** Builds the full teamforming panel as a single Components V2 message. */
    public MessageCreateData buildPanelMessage() {
        List<ContainerChildComponent> children = new ArrayList<>();

        children.add(buildHeader());
        children.add(ActionRow.of(buildTopButtons()));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        for (int i = 0; i < TeamformingCatalog.SECTIONS.size(); i++) {
            TeamformingSection section = TeamformingCatalog.SECTIONS.get(i);
            children.add(TextDisplay.of("**" + section.emoji() + " " + section.title() + "**\n" + section.prompt()));
            children.add(ActionRow.of(buildSelectMenu(section)));
            if (i < TeamformingCatalog.SECTIONS.size() - 1) {
                children.add(Separator.createDivider(Separator.Spacing.SMALL));
            }
        }

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(
                "-# Selecting a tag adds it. Use 🗑️ Remove Tags above to remove any tag you're holding."));

        Container container = Container.of(children).withAccentColor(PANEL_ACCENT_COLOR);

        return new MessageCreateBuilder()
                .useComponentsV2()
                .setComponents(container)
                .build();
    }

    /**
     * Header block: clan logo thumbnail (if the resource is present) next to the title/blurb.
     * <p>
     * Note: Discord's {@link Section} component is fixed as "content on the left, accessory on
     * the right" — there's no supported way to put the thumbnail on the left instead.
     */
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

    private List<Button> buildTopButtons() {
        List<Button> buttons = new ArrayList<>();
        for (TeamformingToggle toggle : TeamformingCatalog.TOGGLES) {
            buttons.add(Button.secondary(TOGGLE_PREFIX + toggle.roleName(), toggle.emoji() + " " + toggle.label()));
        }
        buttons.add(Button.danger(MANAGE_TAGS_BUTTON_ID, "🗑️ Remove Tags"));
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

    /**
     * Finds an existing role by exact (case-insensitive) name, creating it (with its catalog
     * color) if none exists. If it already exists but its color doesn't match the catalog, its
     * color is updated to match — so re-running {@code /teamforming post} after a color change in
     * the catalog fixes already-created roles too.
     */
    public Role ensureRole(Guild guild, String roleName) {
        Color catalogColor = TeamformingCatalog.colorForRoleName(roleName);

        List<Role> existing = guild.getRolesByName(roleName, true);
        if (!existing.isEmpty()) {
            Role role = existing.getFirst();
            if (catalogColor != null) {
                int currentRgb = role.getColors().getPrimaryRaw() & 0xFFFFFF;
                int catalogRgb = catalogColor.getRGB() & 0xFFFFFF;
                if (currentRgb != catalogRgb) {
                    role.getManager().setColor(catalogColor).complete();
                }
            }
            return role;
        }

        RoleAction action = guild.createRole().setName(roleName).setMentionable(true);
        if (catalogColor != null) {
            action = action.setColor(catalogColor);
        }

        Role created = action.complete();
        log.info("Created teamforming role '{}' in guild {}", roleName, guild.getIdLong());
        return created;
    }

    /**
     * Ensures every role in the teamforming catalog exists (and is colored correctly) in the
     * guild, creating any that are missing. Meant to be run once from {@code /teamforming post},
     * so every dropdown/button works immediately once the panel is live.
     *
     * @return the names of roles that were newly created (empty if they all already existed)
     */
    public List<String> ensureAllRolesExist(Guild guild) {
        List<String> created = new ArrayList<>();
        for (String roleName : TeamformingCatalog.allRoleNames()) {
            boolean existed = !guild.getRolesByName(roleName, true).isEmpty();
            try {
                ensureRole(guild, roleName);
                if (!existed) created.add(roleName);
            } catch (Exception e) {
                log.warn("Failed to create/color teamforming role '{}' in guild {}", roleName, guild.getIdLong(), e);
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
     * Grants roles for a dropdown submission. Add-only by design (see class doc): a role in this
     * section that the member already has but didn't (re-)select is left untouched.
     *
     * @return the role names newly granted (empty if they already had everything they selected)
     */
    public List<String> applySelection(Guild guild, Member member, List<String> selectedRoleNames) {
        Set<String> memberRoleNames = new HashSet<>();
        for (Role role : member.getRoles()) memberRoleNames.add(role.getName());

        List<Role> toAdd = new ArrayList<>();
        List<String> added = new ArrayList<>();

        for (String roleName : selectedRoleNames) {
            if (memberRoleNames.contains(roleName)) continue;
            toAdd.add(ensureRole(guild, roleName));
            added.add(roleName);
        }

        if (!toAdd.isEmpty()) {
            guild.modifyMemberRoles(member, toAdd, List.of()).complete();
        }

        return added;
    }

    /**
     * The teamforming tags this member currently holds, freshly read from their live role list —
     * used to build a personalized "remove tags" menu that's always accurate, since it's built
     * from what they actually have rather than any cached/assumed state.
     */
    public List<String> getHeldSectionRoleNames(Member member) {
        Set<String> catalogRoleNames = TeamformingCatalog.allSectionRoleNames();
        List<String> held = new ArrayList<>();
        for (Role role : member.getRoles()) {
            if (catalogRoleNames.contains(role.getName())) held.add(role.getName());
        }
        return held;
    }

    /** Removes exactly the given roles (by name) from the member. */
    public List<String> removeRoles(Guild guild, Member member, List<String> roleNames) {
        List<Role> toRemove = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (String roleName : roleNames) {
            guild.getRolesByName(roleName, true).stream().findFirst().ifPresent(role -> {
                toRemove.add(role);
                removed.add(roleName);
            });
        }

        if (!toRemove.isEmpty()) {
            guild.modifyMemberRoles(member, List.of(), toRemove).complete();
        }

        return removed;
    }
}
