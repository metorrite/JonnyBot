package com.younglings.bot.commands.configure;

import com.younglings.bot.configure.GuildSettings;
import com.younglings.bot.configure.GuildSettingsService;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Buttons/modal for {@link ConfigureCommand}'s panel. Only "Clan" and "Verification" sections exist
 * today — more sections (Coffer, Events, whatever) would each just be another button here and
 * another sub-panel, but nothing speculative is built ahead of an actual need.
 * <p>
 * Gated by Discord's native Administrator permission, same as {@link ConfigureCommand} — see that
 * class for why this doesn't use {@code AdminRoleFilter}.
 */
@BService
public class ConfigureInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(ConfigureInteractionListener.class);

    private final GuildSettingsService settingsService;

    public ConfigureInteractionListener(GuildSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            switch (id.split(":")[0]) {
                case "configure_clan" -> event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                case "configure_clan_edit" -> doClanEditPrompt(event);
                case "configure_verification" -> event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
                case "configure_back" -> event.editComponents(List.of(buildPanel())).useComponentsV2(true).queue();
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }
            if (id.equals("configure_clan_modal:_")) {
                handleClanModal(event, guild);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static final String NO_ROLE_VALUE = "none";

    /** The three Verification-panel role dropdowns — each applies immediately on selection (no separate Save), then re-renders the panel showing the new state. */
    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            Long selected = roleIdOrNull(event.getValues().getFirst());
            GuildSettings current = settingsService.getEffective(guild.getIdLong());

            switch (id.split(":")[0]) {
                case "configure_verified_clan_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), selected, current.verifiedNonClanRoleId(), current.unverifiedRoleId());
                case "configure_verified_nonclan_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), current.verifiedClanRoleId(), selected, current.unverifiedRoleId());
                case "configure_unverified_role" -> settingsService.updateVerificationRoleSettings(
                        guild.getIdLong(), current.verifiedClanRoleId(), current.verifiedNonClanRoleId(), selected);
                default -> {
                    return;
                }
            }

            event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in configure select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** The Rename Alert and Verification Review channel pickers — same immediate-apply pattern as the role dropdowns above, just for a channel instead of a role. */
    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("configure_")) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
                return;
            }

            Long selectedChannelId = event.getValues().isEmpty() ? null : event.getValues().getFirst().getIdLong();

            switch (id.split(":")[0]) {
                case "configure_rename_channel" -> {
                    settingsService.updateRenameAlertChannel(guild.getIdLong(), selectedChannelId);
                    event.editComponents(List.of(buildClanPanel(guild))).useComponentsV2(true).queue();
                }
                case "configure_verification_channel" -> {
                    settingsService.updateVerificationSettings(guild.getIdLong(), selectedChannelId);
                    event.editComponents(List.of(buildVerificationPanel(guild))).useComponentsV2(true).queue();
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in configure entity select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static Long roleIdOrNull(String value) {
        return NO_ROLE_VALUE.equals(value) ? null : Long.parseLong(value);
    }

    private void doClanEditPrompt(ButtonInteractionEvent event) {
        GuildSettings current = settingsService.getEffective(event.getGuild().getIdLong());

        TextInput clanNameInput = prefilled("clan_name", "Exact in-game clan name", current.clanName());
        TextInput adminRoleInput = prefilled("admin_role_id", "Role ID (right-click role > Copy Role ID)",
                current.adminRoleId() != null ? String.valueOf(current.adminRoleId()) : null);

        Modal modal = Modal.create("configure_clan_modal:_", "Clan Settings")
                .addComponents(
                        Label.of("Clan Name", clanNameInput),
                        Label.of("Admin Role ID", adminRoleInput))
                .build();
        event.replyModal(modal).queue();
    }

    private static TextInput prefilled(String id, String placeholder, String currentValue) {
        TextInput.Builder builder = TextInput.create(id, TextInputStyle.SHORT).setPlaceholder(placeholder).setRequired(false);
        if (currentValue != null) builder.setValue(currentValue);
        return builder.build();
    }

    /** Blank clears that field's override (falls back to {@code BotConfig} again); a role mention is accepted alongside a raw ID. */
    private void handleClanModal(ModalInteractionEvent event, Guild guild) {
        String clanName = blankToNull(event.getValue("clan_name").getAsString());
        Long adminRoleId = parseLongOrNull(blankToNull(event.getValue("admin_role_id").getAsString().replaceAll("[<@&>]", "")));

        settingsService.updateClanSettings(guild.getIdLong(), clanName, adminRoleId);
        Containers.replyEphemeral(event, Containers.SUCCESS, "Clan settings updated.");
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Long parseLongOrNull(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Container buildPanel() {
        return Containers.card(Containers.PRIMARY,
                TextDisplay.of("# Server Configuration"),
                TextDisplay.of("-# Administrator only"),
                ActionRow.of(
                        Button.secondary("configure_clan:_", "Clan"),
                        Button.secondary("configure_verification:_", "Verification")));
    }

    private Container buildClanPanel(Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clan Settings"));
        children.add(TextDisplay.of(
                "**Clan Name:** " + display(settings.clanName()) + "\n" +
                "**Admin Role:** " + (settings.adminRoleId() != null ? "<@&" + settings.adminRoleId() + ">" : "*not set*") + "\n" +
                "**Rename Alert Channel:** " + (settings.renameAlertChannelId() != null ? "<#" + settings.renameAlertChannelId() + ">" : "*not set*")));
        children.add(ActionRow.of(
                Button.primary("configure_clan_edit:_", "Edit"),
                Button.secondary("configure_back:_", "Back")));

        children.add(TextDisplay.of("**Rename Alert Channel** — where a possible in-game RSN change gets posted " +
                "for an admin to Confirm/Reject, detected automatically during **Sync Clan**."));
        children.add(buildChannelSelectRow("configure_rename_channel:_", "Select a channel (optional)", settings.renameAlertChannelId()));

        return Containers.card(Containers.PRIMARY, children);
    }

    private static String display(String value) {
        return value != null ? value : "*not set*";
    }

    private Container buildVerificationPanel(Guild guild) {
        GuildSettings settings = settingsService.getEffective(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Verification Settings"));
        children.add(TextDisplay.of("-# The makeover-mage appearance check is temporarily disabled — every `/rs` " +
                "link request is a plain admin call for now."));
        children.add(ActionRow.of(Button.secondary("configure_back:_", "Back")));

        children.add(TextDisplay.of("**Review Channel** — where a new `/rs` link request is posted for an admin to Approve/Reject."));
        children.add(buildChannelSelectRow("configure_verification_channel:_", "Select a channel (optional)", settings.verificationReviewChannelId()));

        children.add(TextDisplay.of("-# The three roles below are all optional — pick \"No Role Assignment\" to leave one empty."));
        children.add(TextDisplay.of("**Verified Role — Clan Member**\n-# Granted when an approved request's RSN is currently in the tracked clan roster."));
        children.add(buildRoleSelectRow(guild, "configure_verified_clan_role:_", "Select a role (optional)", settings.verifiedClanRoleId()));
        children.add(TextDisplay.of("**Verified Role — Not a Clan Member**\n-# Granted when an approved request's RSN *isn't* in the clan — usually a role something else (e.g. a join flow) already grants; this only fills it in if missing."));
        children.add(buildRoleSelectRow(guild, "configure_verified_nonclan_role:_", "Select a role (optional)", settings.verifiedNonClanRoleId()));
        children.add(TextDisplay.of("**Unverified Role**\n-# Removed from the member the moment their request is approved (either verified role above)."));
        children.add(buildRoleSelectRow(guild, "configure_unverified_role:_", "Select a role (optional)", settings.unverifiedRoleId()));

        return Containers.card(Containers.PRIMARY, children);
    }

    // Discord caps a select menu at 25 options; "No Role Assignment" takes one, leaving room for
    // the guild's first 24 roles (highest-position first, same order guild.getRoles() returns them
    // in) — a server with more than that is a rare edge case not worth a second menu for here.
    private static final int ROLE_SELECT_LIMIT = 24;

    private ActionRow buildRoleSelectRow(Guild guild, String customId, String placeholder, Long currentRoleId) {
        StringSelectMenu.Builder menu = StringSelectMenu.create(customId).setPlaceholder(placeholder);
        menu.addOption("No Role Assignment", NO_ROLE_VALUE);

        List<Role> roles = guild.getRoles().stream().filter(role -> !role.isPublicRole()).limit(ROLE_SELECT_LIMIT).toList();
        for (Role role : roles) {
            menu.addOption(role.getName(), role.getId());
        }

        boolean currentStillListed = currentRoleId != null && roles.stream().anyMatch(role -> role.getIdLong() == currentRoleId);
        menu.setDefaultValues(currentStillListed ? String.valueOf(currentRoleId) : NO_ROLE_VALUE);

        return ActionRow.of(menu.build());
    }

    /** A native Discord channel picker (text channels only) instead of typing/pasting an ID — {@code setRequiredRange(0, 1)} lets the admin clear a previously-picked channel back to "not set" by deselecting it. */
    private ActionRow buildChannelSelectRow(String customId, String placeholder, Long currentChannelId) {
        EntitySelectMenu.Builder menu = EntitySelectMenu.create(customId, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder(placeholder)
                .setRequiredRange(0, 1);
        if (currentChannelId != null) {
            menu.setDefaultValues(EntitySelectMenu.DefaultValue.channel(currentChannelId));
        }
        return ActionRow.of(menu.build());
    }
}
