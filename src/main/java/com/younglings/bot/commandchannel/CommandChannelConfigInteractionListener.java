package com.younglings.bot.commandchannel;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.DiscordLinks;
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
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IMessageEditCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code /configure} → Command Channels: the panel for {@link CommandChannelService}. Three screens —
 * the list of groups, one group (its notice, channels, and enable/delete), and that group's
 * who-it-applies-to rules — all {@code configure_cmdchan}-prefixed and gated by Administrator, same as
 * the Tracking panel (see {@code TrackingConfigInteractionListener}), and every screen's Back button
 * sits bottom-left in blurple like every other panel here.
 */
@BService
public class CommandChannelConfigInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(CommandChannelConfigInteractionListener.class);

    private static final String PREFIX = "configure_cmdchan";
    private static final String NO_ADMIN = "You need the Administrator permission to configure this bot.";

    private final CommandChannelService service;

    public CommandChannelConfigInteractionListener(CommandChannelService service) {
        this.service = service;
    }

    // --- Routing ---

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith(PREFIX)) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, NO_ADMIN);
                return;
            }

            long guildId = guild.getIdLong();
            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "configure_cmdchan_main" -> {
                    service.ensureDefaultGroups(guildId);
                    edit(event, buildMainPanel(guild));
                }
                case "configure_cmdchan_group" -> showGroup(event, guild, Long.parseLong(parts[1]));
                case "configure_cmdchan_new" -> event.replyModal(buildNewGroupModal()).queue();
                case "configure_cmdchan_toggle" -> {
                    long groupId = Long.parseLong(parts[1]);
                    service.getGroup(guildId, groupId).ifPresent(g -> service.setEnabled(guildId, groupId, !g.enabled()));
                    showGroup(event, guild, groupId);
                }
                case "configure_cmdchan_msg" -> {
                    CommandChannelGroup group = service.getGroup(guildId, Long.parseLong(parts[1])).orElse(null);
                    if (group == null) edit(event, buildMainPanel(guild));
                    else event.replyModal(buildMessageModal(group)).queue();
                }
                case "configure_cmdchan_link" -> event.replyModal(buildLinkModal(Long.parseLong(parts[1]))).queue();
                case "configure_cmdchan_rules" -> showRules(event, guild, Long.parseLong(parts[1]));
                case "configure_cmdchan_delete" -> {
                    CommandChannelGroup group = service.getGroup(guildId, Long.parseLong(parts[1])).orElse(null);
                    if (group == null || CommandChannelService.isProtected(group)) edit(event, buildMainPanel(guild));
                    else edit(event, buildDeleteConfirmPanel(group));
                }
                case "configure_cmdchan_delete_go" -> {
                    long groupId = Long.parseLong(parts[1]);
                    service.getGroup(guildId, groupId).filter(g -> !CommandChannelService.isProtected(g))
                            .ifPresent(g -> service.deleteGroup(guildId, groupId));
                    edit(event, buildMainPanel(guild));
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in command-channel button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith(PREFIX)) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, NO_ADMIN);
                return;
            }

            String[] parts = id.split(":", 2);
            if (parts[0].equals("configure_cmdchan_removech")) {
                long groupId = Long.parseLong(parts[1]);
                service.removeChannel(guild.getIdLong(), groupId, Long.parseLong(event.getValues().getFirst()));
                showGroup(event, guild, groupId);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in command-channel select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith(PREFIX)) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, NO_ADMIN);
                return;
            }

            long guildId = guild.getIdLong();
            String[] parts = id.split(":", 2);
            long groupId = Long.parseLong(parts[1]);
            // An empty selection is meaningful for the role pickers (they're optional — clearing one is
            // how you remove that rule), so it's read per-case rather than rejected up front.
            List<IMentionable> values = event.getValues();
            Long firstId = values.isEmpty() ? null : values.getFirst().getIdLong();
            Set<Long> allIds = values.stream().map(IMentionable::getIdLong).collect(Collectors.toUnmodifiableSet());

            switch (parts[0]) {
                case "configure_cmdchan_addch" -> {
                    if (firstId == null) return;
                    addChannelAndRender(event, guild, groupId, firstId);
                }
                case "configure_cmdchan_below" -> {
                    service.setApplyBelowRole(guildId, groupId, firstId);
                    showRules(event, guild, groupId);
                }
                case "configure_cmdchan_exempt_from" -> {
                    service.setExemptFromRole(guildId, groupId, firstId);
                    showRules(event, guild, groupId);
                }
                case "configure_cmdchan_apply_roles" -> {
                    service.setApplyRoles(guildId, groupId, allIds);
                    showRules(event, guild, groupId);
                }
                case "configure_cmdchan_exempt_roles" -> {
                    service.setExemptRoles(guildId, groupId, allIds);
                    showRules(event, guild, groupId);
                }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in command-channel entity select interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith(PREFIX)) return;

        try {
            if (!member.hasPermission(Permission.ADMINISTRATOR)) {
                Containers.replyEphemeral(event, Containers.WARNING, NO_ADMIN);
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "configure_cmdchan_new_modal" -> handleNewGroup(event, guild);
                case "configure_cmdchan_msg_modal" -> handleMessageEdit(event, guild, Long.parseLong(parts[1]));
                case "configure_cmdchan_link_modal" -> handleLinkAdd(event, guild, Long.parseLong(parts[1]));
            }
        } catch (Exception e) {
            log.error("Unhandled exception in command-channel modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    // --- Actions ---

    private <E extends IMessageEditCallback & IReplyCallback> void addChannelAndRender(E event, Guild guild, long groupId, long channelId) {
        CommandChannelService.AddChannelResult result = service.addChannel(guild.getIdLong(), groupId, channelId);
        String problem = switch (result.status()) {
            case IN_OTHER_GROUP -> "That channel is already in the **" + result.otherGroupName()
                    + "** group — remove it there first (a channel can only be in one group).";
            case GROUP_FULL -> "A group can hold up to " + CommandChannelService.MAX_CHANNELS_PER_GROUP + " channels.";
            case NO_SUCH_GROUP -> "That group no longer exists.";
            case ADDED, ALREADY_IN_THIS_GROUP -> null;
        };
        if (problem != null) {
            // Both the select and the link modal can land here; either way a plain ephemeral reply leaves the panel as it was.
            Containers.replyEphemeral(event, Containers.WARNING, problem);
            return;
        }
        showGroup(event, guild, groupId);
    }

    private void handleNewGroup(ModalInteractionEvent event, Guild guild) {
        long guildId = guild.getIdLong();
        String name = event.getValue("name").getAsString().trim();
        if (name.isEmpty() || name.length() > CommandChannelService.MAX_NAME_LENGTH) {
            Containers.replyEphemeral(event, Containers.WARNING, "A group name must be 1–" + CommandChannelService.MAX_NAME_LENGTH + " characters.");
            return;
        }
        if (service.getGroups(guildId).size() >= CommandChannelService.MAX_GROUPS) {
            Containers.replyEphemeral(event, Containers.WARNING, "That's the maximum of " + CommandChannelService.MAX_GROUPS + " groups.");
            return;
        }

        long groupId = service.createGroup(guildId, name);
        if (groupId < 0) {
            Containers.replyEphemeral(event, Containers.WARNING, "There's already a group called **" + name + "**.");
            return;
        }
        showGroup(event, guild, groupId);
    }

    private void handleMessageEdit(ModalInteractionEvent event, Guild guild, long groupId) {
        long guildId = guild.getIdLong();
        CommandChannelGroup group = service.getGroup(guildId, groupId).orElse(null);
        if (group == null) {
            edit(event, buildMainPanel(guild));
            return;
        }
        if (CommandChannelService.isDefaultGroup(group)) {
            Containers.replyEphemeral(event, Containers.WARNING, "The Default group always uses the built-in message — edit the **Custom** group's instead.");
            return;
        }

        service.setCustomMessage(guildId, groupId, event.getValue("message").getAsString());
        showGroup(event, guild, groupId);
    }

    private void handleLinkAdd(ModalInteractionEvent event, Guild guild, long groupId) {
        Long channelId = DiscordLinks.parseChannelId(event.getValue("channel_link").getAsString());
        if (channelId == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "Couldn't find a channel/thread ID in that — paste the full link, or just the ID by itself.");
            return;
        }
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (!(channel instanceof GuildMessageChannel)) {
            Containers.replyEphemeral(event, Containers.WARNING, "That doesn't look like a text channel or thread in this server. Double-check the link and try again.");
            return;
        }
        addChannelAndRender(event, guild, groupId, channelId);
    }

    // --- Screens ---

    private static void edit(IMessageEditCallback event, Container panel) {
        event.editComponents(List.of(panel)).useComponentsV2(true).queue();
    }

    private void showGroup(IMessageEditCallback event, Guild guild, long groupId) {
        CommandChannelGroup group = service.getGroup(guild.getIdLong(), groupId).orElse(null);
        edit(event, group != null ? buildGroupPanel(guild, group) : buildMainPanel(guild));
    }

    private void showRules(IMessageEditCallback event, Guild guild, long groupId) {
        CommandChannelGroup group = service.getGroup(guild.getIdLong(), groupId).orElse(null);
        edit(event, group != null ? buildRulesPanel(guild, group) : buildMainPanel(guild));
    }

    /** The list of groups — also where the other two screens' Back buttons lead. */
    Container buildMainPanel(Guild guild) {
        List<CommandChannelGroup> groups = service.getGroups(guild.getIdLong());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Command Only Channels\n" +
                "-# In a group's channels, regular messages are deleted and the sender gets a short notice — slash commands still work. " +
                "Each group has its own channels, notice, and rules about who it applies to. A channel can be in only one group."));
        children.add(TextDisplay.of("-# Discord only allows a private (ephemeral) reply to a slash command or button, never to a typed message — " +
                "so the notice is a normal message that mentions the sender and deletes itself after about " +
                CommandChannelListener.NOTICE_LIFETIME_SECONDS + " seconds."));

        List<Button> row = new ArrayList<>();
        for (CommandChannelGroup group : groups) {
            String label = (group.enabled() ? "" : "⏸ ") + group.name() + " (" + group.channelIds().size() + ")";
            row.add(Button.secondary("configure_cmdchan_group:" + group.id(), label));
            if (row.size() == 5) {
                children.add(ActionRow.of(new ArrayList<>(row)));
                row.clear();
            }
        }
        if (!row.isEmpty()) children.add(ActionRow.of(row));

        Button back = Button.primary("configure_back:_", "Back");
        children.add(groups.size() < CommandChannelService.MAX_GROUPS
                ? ActionRow.of(back, Button.success("configure_cmdchan_new:_", "New Group"))
                : ActionRow.of(back));
        children.add(Containers.autoCloseNote());

        return Containers.card(Containers.PRIMARY, children);
    }

    Container buildGroupPanel(Guild guild, CommandChannelGroup group) {
        boolean isDefault = CommandChannelService.isDefaultGroup(group);
        boolean isProtected = CommandChannelService.isProtected(group);
        String gid = String.valueOf(group.id());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + group.name() + "\n-# "
                + (group.enabled() ? "Enabled" : "**Disabled** — nothing is enforced")
                + " • " + group.channelIds().size() + " channel(s)"));

        String note = isDefault ? " *(fixed default)*" : group.customMessage() == null || group.customMessage().isBlank() ? " *(using the default)*" : "";
        children.add(TextDisplay.of("**Notice message**" + note + "\n> " + CommandChannelService.messageFor(group).replace("\n", "\n> ")));

        List<Button> actions = new ArrayList<>();
        if (!isDefault) actions.add(Button.secondary("configure_cmdchan_msg:" + gid, "Edit Message"));
        actions.add(Button.secondary("configure_cmdchan_rules:" + gid, "Who It Applies To"));
        actions.add(group.enabled()
                ? Button.secondary("configure_cmdchan_toggle:" + gid, "Disable")
                : Button.success("configure_cmdchan_toggle:" + gid, "Enable"));
        if (!isProtected) actions.add(Button.danger("configure_cmdchan_delete:" + gid, "Delete Group"));
        children.add(ActionRow.of(actions));

        if (group.channelIds().isEmpty()) {
            children.add(TextDisplay.of("**Channels** — *none yet, add one below*"));
        } else {
            StringBuilder list = new StringBuilder("**Channels**\n");
            for (long channelId : group.channelIds().stream().sorted().toList()) {
                list.append("• <#").append(channelId).append("> ").append(channelStatus(guild, channelId)).append('\n');
            }
            children.add(TextDisplay.of(list.toString().trim()));

            StringSelectMenu.Builder remove = StringSelectMenu.create("configure_cmdchan_removech:" + gid).setPlaceholder("Remove a channel");
            for (long channelId : group.channelIds().stream().sorted().toList()) {
                remove.addOption("Remove #" + channelName(guild, channelId), String.valueOf(channelId));
            }
            children.add(ActionRow.of(remove.build()));
        }

        children.add(ActionRow.of(
                EntitySelectMenu.create("configure_cmdchan_addch:" + gid, EntitySelectMenu.SelectTarget.CHANNEL)
                        .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS, ChannelType.VOICE,
                                ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD)
                        .setPlaceholder("Add a channel or thread")
                        .setRequiredRange(0, 1)
                        .build()));
        children.add(Containers.linkButtonRow("configure_cmdchan_link:" + gid));

        children.add(TextDisplay.of(String.join("\n", service.describeRules(group, guild))));
        children.add(ActionRow.of(Button.primary("configure_cmdchan_main:_", "Back")));

        return Containers.card(Containers.PRIMARY, children);
    }

    Container buildRulesPanel(Guild guild, CommandChannelGroup group) {
        String gid = String.valueOf(group.id());

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + group.name() + " — Who It Applies To"));
        children.add(TextDisplay.of("-# By default a group covers **everyone**. Settings 1 and 2 narrow it to certain people (matching *either* counts); " +
                "settings 3 and 4 carve out exceptions. Exemptions always win. Changes save immediately."));
        children.add(TextDisplay.of(String.join("\n", service.describeRules(group, guild))));

        children.add(TextDisplay.of("**1. Applies to roles below…** — anyone whose highest role is lower than the one you pick."));
        children.add(ActionRow.of(roleSelect("configure_cmdchan_below:" + gid, "Pick a role (optional)", 1, singleton(group.applyBelowRoleId()), guild)));

        children.add(TextDisplay.of("**2. Applies to specific roles…** — anyone holding any of these."));
        children.add(ActionRow.of(roleSelect("configure_cmdchan_apply_roles:" + gid, "Pick roles (optional)", 25, group.applyRoleIds(), guild)));

        children.add(TextDisplay.of("**3. Exempt roles at or above…** — anyone whose highest role is this or higher is never covered."));
        children.add(ActionRow.of(roleSelect("configure_cmdchan_exempt_from:" + gid, "Pick a role (optional)", 1, singleton(group.exemptFromRoleId()), guild)));

        children.add(TextDisplay.of("**4. Exempt specific roles…** — anyone holding any of these is never covered."));
        children.add(ActionRow.of(roleSelect("configure_cmdchan_exempt_roles:" + gid, "Pick roles (optional)", 25, group.exemptRoleIds(), guild)));

        children.add(ActionRow.of(Button.primary("configure_cmdchan_group:" + gid, "Back")));
        return Containers.card(Containers.PRIMARY, children);
    }

    private static Container buildDeleteConfirmPanel(CommandChannelGroup group) {
        return Containers.card(Containers.DANGER,
                TextDisplay.of("### Delete “" + group.name() + "”?"),
                TextDisplay.of("This removes the group, its " + group.channelIds().size()
                        + " channel(s), and its rules. Messages in those channels will no longer be deleted. This can't be undone."),
                ActionRow.of(
                        Button.danger("configure_cmdchan_delete_go:" + group.id(), "Yes, Delete"),
                        Button.secondary("configure_cmdchan_group:" + group.id(), "Cancel")));
    }

    private Modal buildNewGroupModal() {
        TextInput name = TextInput.create("name", TextInputStyle.SHORT)
                .setPlaceholder("e.g. Staff Commands")
                .setRequiredRange(1, CommandChannelService.MAX_NAME_LENGTH)
                .build();
        return Modal.create("configure_cmdchan_new_modal:_", "New Command-Only Group")
                .addComponents(Label.of("Group name", name))
                .build();
    }

    private Modal buildMessageModal(CommandChannelGroup group) {
        TextInput message = TextInput.create("message", TextInputStyle.PARAGRAPH)
                .setRequired(false)
                .setMaxLength(CommandChannelService.MAX_MESSAGE_LENGTH)
                .setPlaceholder(CommandChannelService.DEFAULT_MESSAGE)
                .setValue(group.customMessage() == null || group.customMessage().isBlank() ? null : group.customMessage())
                .build();
        return Modal.create("configure_cmdchan_msg_modal:" + group.id(), "Edit Notice Message")
                .addComponents(Label.of("Message (leave blank for the default)", message))
                .build();
    }

    private Modal buildLinkModal(long groupId) {
        TextInput link = TextInput.create("channel_link", TextInputStyle.SHORT)
                .setPlaceholder("Paste a thread/channel link, or just its ID")
                .setRequired(true)
                .build();
        return Modal.create("configure_cmdchan_link_modal:" + groupId, "Add Channel by Link")
                .addComponents(Label.of("Channel or Thread Link / ID", link))
                .build();
    }

    // --- Helpers ---

    private static Collection<Long> singleton(Long id) {
        return id == null ? Set.of() : Set.of(id);
    }

    /** A role picker that opens with the current selection already ticked — roles that no longer exist are left out, since Discord rejects a pre-selected role it can't find. */
    private static EntitySelectMenu roleSelect(String id, String placeholder, int max, Collection<Long> selected, Guild guild) {
        List<EntitySelectMenu.DefaultValue> defaults = selected.stream()
                .filter(roleId -> guild.getRoleById(roleId) != null)
                .sorted()
                .map(roleId -> EntitySelectMenu.DefaultValue.role(roleId.longValue()))
                .toList();
        return EntitySelectMenu.create(id, EntitySelectMenu.SelectTarget.ROLE)
                .setPlaceholder(placeholder)
                .setRequiredRange(0, max)
                .setDefaultValues(defaults)
                .build();
    }

    private static String channelName(Guild guild, long channelId) {
        GuildChannel channel = guild.getGuildChannelById(channelId);
        String name = channel != null ? channel.getName() : String.valueOf(channelId);
        return name.length() > 90 ? name.substring(0, 90) : name;
    }

    /** ✅ if the bot can actually enforce here, otherwise what's wrong — a rule on a channel the bot can't delete in does nothing, and that should be visible. */
    private static String channelStatus(Guild guild, long channelId) {
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) return "⚠️ can't see this channel (deleted, archived, or no access)";
        if (!guild.getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            return "⚠️ the bot is missing **Manage Messages** here, so nothing can be deleted";
        }
        return "✅";
    }
}
