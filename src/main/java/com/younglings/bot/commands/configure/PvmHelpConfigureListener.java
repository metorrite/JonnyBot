package com.younglings.bot.commands.configure;

import com.younglings.bot.commands.ticket.HelpOnboarding;
import com.younglings.bot.commands.ticket.HelpRules;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The PvM Help section of /configure: the two helper roles, the helper guidelines, the member and guest ping timers, guest pings (off for
 * now) and the earlier-attempts rule for Master and above. The website's PvM Help page edits the same settings.
 * <p>
 * Its components use the {@code pvmhelp_cfg_} prefix, apart from /configure's {@code configure_} ones, so the main listener ignores them.
 * Gated by Discord's Administrator permission, like the rest of /configure.
 */
@BService
public class PvmHelpConfigureListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PvmHelpConfigureListener.class);

    /** The button on /configure's main panel that opens this section. */
    public static final String OPEN_ID = "pvmhelp_cfg_main:_";

    private static final String BACK_ID = "configure_back:_";
    private static final String HELPER_ROLE_ID = "pvmhelp_cfg_role_helper:_";
    private static final String HELPER_PLUS_ROLE_ID = "pvmhelp_cfg_role_plus:_";
    private static final String POST_CHANNEL_ID = "pvmhelp_cfg_post:_";

    private final TicketRepository repository;
    private final HelpOnboarding onboarding;

    public PvmHelpConfigureListener(TicketRepository repository, HelpOnboarding onboarding) {
        this.repository = repository;
        this.onboarding = onboarding;
    }

    // ---------- the panel ----------

    Container buildPanel(Guild guild) {
        HelpSettings s = repository.getHelpSettings(guild.getIdLong());

        StringBuilder text = new StringBuilder("### PvM Help\n");
        text.append("**PVM Helper role:** ").append(role(s.helperRoleId())).append("\n");
        text.append("**PVM Helper+ role:** ").append(role(s.helperPlusRoleId())).append(" *(given by hand)*\n");
        text.append("**Member tickets:** ").append(s.memberPingOnOpen() ? "ping helpers when opened" : "no ping when opened").append(", ")
                .append(hours(s.memberEscalationHours())).append("\n");
        text.append("**Guest tickets:** ");
        if (!s.guestPingsEnabled()) text.append("🔴 **no pings** *(switched off)*\n");
        else text.append(s.guestPingOnOpen() ? "ping helpers when opened" : "no ping when opened").append(", ").append(hours(s.guestEscalationHours())).append("\n");
        text.append("**Guests asking for Master and above:** ");
        text.append(s.guestHighTierNeedsAttempts() ? "must describe earlier attempts (" + s.highTierLabels() + ")" : "no extra requirement").append("\n");
        text.append("**Guidelines:** ").append(s.guidelines() == null ? "built-in draft" : "edited").append(" · ")
                .append(s.postedChannelId() == null ? "not posted yet" : "posted in <#" + s.postedChannelId() + ">");

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(text.toString()));
        children.add(TextDisplay.of("-# A guest is anyone who isn't a clan member, whether or not they've linked a RuneScape name. Hours are how long an unanswered " +
                "ticket waits before the next role up is pinged. Which panels these rules apply to is set per panel on the website."));

        children.add(TextDisplay.of("**PVM Helper role** — handed out when someone agrees to the guidelines."));
        children.add(ActionRow.of(roleMenu(guild, HELPER_ROLE_ID, s.helperRoleId())));
        children.add(TextDisplay.of("**PVM Helper+ role** — given by hand; can be pinged for Master and Grandmaster."));
        children.add(ActionRow.of(roleMenu(guild, HELPER_PLUS_ROLE_ID, s.helperPlusRoleId())));

        children.add(ActionRow.of(
                Button.primary("pvmhelp_cfg_guidelines:_", "Edit Guidelines"),
                Button.secondary("pvmhelp_cfg_member:_", "Member Pings"),
                Button.secondary("pvmhelp_cfg_guest:_", "Guest Pings"),
                Button.secondary("pvmhelp_cfg_attempts:_", "Earlier Attempts")));

        children.add(TextDisplay.of("**Post the guidelines** — pick a channel to post (or update) the message where members agree and take the role."));
        EntitySelectMenu.Builder channels = EntitySelectMenu.create(POST_CHANNEL_ID, EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT)
                .setPlaceholder("Choose a channel to post in")
                .setRequiredRange(1, 1);
        children.add(ActionRow.of(channels.build()));

        children.add(ActionRow.of(Button.primary(BACK_ID, "Back")));
        children.add(Containers.autoCloseNote());
        return Containers.card(Containers.PRIMARY, children);
    }

    private static EntitySelectMenu roleMenu(Guild guild, String id, Long current) {
        EntitySelectMenu.Builder menu = EntitySelectMenu.create(id, EntitySelectMenu.SelectTarget.ROLE)
                .setPlaceholder("Select a role (optional)")
                .setRequiredRange(0, 1);
        if (current != null && guild.getRoleById(current) != null) menu.setDefaultValues(EntitySelectMenu.DefaultValue.role(current));
        return menu.build();
    }

    private static String role(Long id) {
        return id == null ? "*not set*" : "<@&" + id + ">";
    }

    private static String hours(Integer hours) {
        return hours == null ? "never escalate" : "escalate after " + hours + "h";
    }

    // ---------- buttons ----------

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("pvmhelp_cfg_")) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) return;

        try {
            if (!requireAdmin(event, member)) return;
            HelpSettings s = repository.getHelpSettings(guild.getIdLong());
            switch (id) {
                case OPEN_ID -> event.editComponents(List.of(buildPanel(guild))).useComponentsV2(true).queue();
                case "pvmhelp_cfg_guidelines:_" -> event.replyModal(Modal.create("pvmhelp_cfg_guidelines_modal:_", "Helper Guidelines")
                        .addComponents(Label.of("Guidelines (clear it to go back to the built-in draft)",
                                TextInput.create("guidelines", TextInputStyle.PARAGRAPH).setValue(truncate(s.guidelinesOrDefault(), 4000)).setRequired(false).setMaxLength(HelpSettings.MAX_GUIDELINES).build()))
                        .build()).queue();
                case "pvmhelp_cfg_member:_" -> event.replyModal(Modal.create("pvmhelp_cfg_member_modal:_", "Member Pings")
                        .addComponents(
                                Label.of("Ping helpers when a member's ticket opens", Checkbox.of("ping_on_open", s.memberPingOnOpen())),
                                Label.of("Hours before the next role up is pinged", hoursInput(s.memberEscalationHours())))
                        .build()).queue();
                case "pvmhelp_cfg_guest:_" -> event.replyModal(Modal.create("pvmhelp_cfg_guest_modal:_", "Guest Pings")
                        .addComponents(
                                Label.of("Guest tickets ping helpers at all", Checkbox.of("enabled", s.guestPingsEnabled())),
                                Label.of("…and ping when the ticket opens", Checkbox.of("ping_on_open", s.guestPingOnOpen())),
                                Label.of("Hours before the next role up is pinged", hoursInput(s.guestEscalationHours())))
                        .build()).queue();
                case "pvmhelp_cfg_attempts:_" -> event.replyModal(Modal.create("pvmhelp_cfg_attempts_modal:_", "Earlier Attempts")
                        .addComponents(
                                Label.of("Guests asking for these tiers must describe earlier attempts", Checkbox.of("required", s.guestHighTierNeedsAttempts())),
                                Label.of("Tier names that count (comma separated)", TextInput.create("labels", TextInputStyle.SHORT).setValue(s.highTierLabels()).setRequired(false).setMaxLength(200).build()))
                        .build()).queue();
                default -> { }
            }
        } catch (Exception e) {
            log.error("Unhandled exception in PvM Help configure button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static TextInput hoursInput(Integer current) {
        TextInput.Builder builder = TextInput.create("hours", TextInputStyle.SHORT)
                .setPlaceholder("Leave blank to never escalate (1 to " + HelpRules.MAX_HOURS + ")")
                .setRequired(false)
                .setMaxLength(4);
        if (current != null) builder.setValue(String.valueOf(current));
        return builder.build();
    }

    // ---------- forms ----------

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith("pvmhelp_cfg_")) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) return;

        try {
            if (!requireAdmin(event, member)) return;
            HelpSettings s = repository.getHelpSettings(guild.getIdLong());
            HelpSettings updated;
            try {
                updated = switch (id) {
                    case "pvmhelp_cfg_guidelines_modal:_" -> {
                        String text = event.getValue("guidelines").getAsString().strip();
                        yield withGuidelines(s, text.isEmpty() || text.equals(HelpSettings.DEFAULT_GUIDELINES) ? null : text);
                    }
                    case "pvmhelp_cfg_member_modal:_" -> new HelpSettings(s.guildId(), s.helperRoleId(), s.helperPlusRoleId(), s.guidelines(),
                            event.getValue("ping_on_open").getAsBoolean(), parseHours(event.getValue("hours").getAsString()),
                            s.guestPingsEnabled(), s.guestPingOnOpen(), s.guestEscalationHours(), s.guestHighTierNeedsAttempts(), s.highTierLabels(),
                            s.postedChannelId(), s.postedMessageId());
                    case "pvmhelp_cfg_guest_modal:_" -> new HelpSettings(s.guildId(), s.helperRoleId(), s.helperPlusRoleId(), s.guidelines(),
                            s.memberPingOnOpen(), s.memberEscalationHours(),
                            event.getValue("enabled").getAsBoolean(), event.getValue("ping_on_open").getAsBoolean(), parseHours(event.getValue("hours").getAsString()),
                            s.guestHighTierNeedsAttempts(), s.highTierLabels(), s.postedChannelId(), s.postedMessageId());
                    case "pvmhelp_cfg_attempts_modal:_" -> new HelpSettings(s.guildId(), s.helperRoleId(), s.helperPlusRoleId(), s.guidelines(),
                            s.memberPingOnOpen(), s.memberEscalationHours(), s.guestPingsEnabled(), s.guestPingOnOpen(), s.guestEscalationHours(),
                            event.getValue("required").getAsBoolean(), event.getValue("labels").getAsString().strip(), s.postedChannelId(), s.postedMessageId());
                    default -> null;
                };
            } catch (NumberFormatException e) {
                Containers.replyEphemeral(event, Containers.WARNING, "The hours must be a whole number, or left blank to never escalate.");
                return;
            }
            if (updated == null) return;

            List<String> problems = HelpRules.validate(updated);
            if (!problems.isEmpty()) {
                Containers.replyEphemeral(event, Containers.WARNING, problems.toArray(String[]::new));
                return;
            }
            repository.saveHelpSettings(updated);
            if (id.equals("pvmhelp_cfg_guidelines_modal:_")) onboarding.refreshPosted(guild);
            event.editComponents(List.of(buildPanel(guild))).useComponentsV2(true).queue();
        } catch (Exception e) {
            log.error("Unhandled exception in PvM Help configure form '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private static HelpSettings withGuidelines(HelpSettings s, String guidelines) {
        return new HelpSettings(s.guildId(), s.helperRoleId(), s.helperPlusRoleId(), guidelines, s.memberPingOnOpen(), s.memberEscalationHours(),
                s.guestPingsEnabled(), s.guestPingOnOpen(), s.guestEscalationHours(), s.guestHighTierNeedsAttempts(), s.highTierLabels(),
                s.postedChannelId(), s.postedMessageId());
    }

    /** Blank means never; anything else must be a whole number (its range is checked with the rest of the settings). */
    static Integer parseHours(String raw) {
        String text = raw.strip();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    // ---------- pickers ----------

    @Override
    public void onEntitySelectInteraction(EntitySelectInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("pvmhelp_cfg_")) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        if (guild == null || member == null) return;

        try {
            if (!requireAdmin(event, member)) return;
            HelpSettings s = repository.getHelpSettings(guild.getIdLong());

            if (id.equals(HELPER_ROLE_ID) || id.equals(HELPER_PLUS_ROLE_ID)) {
                Long picked = event.getMentions().getRoles().stream().map(r -> r.getIdLong()).findFirst().orElse(null);
                boolean helper = id.equals(HELPER_ROLE_ID);
                repository.saveHelpSettings(new HelpSettings(s.guildId(), helper ? picked : s.helperRoleId(), helper ? s.helperPlusRoleId() : picked, s.guidelines(),
                        s.memberPingOnOpen(), s.memberEscalationHours(), s.guestPingsEnabled(), s.guestPingOnOpen(), s.guestEscalationHours(),
                        s.guestHighTierNeedsAttempts(), s.highTierLabels(), s.postedChannelId(), s.postedMessageId()));
                event.editComponents(List.of(buildPanel(guild))).useComponentsV2(true).queue();
            } else if (id.equals(POST_CHANNEL_ID)) {
                postGuidelines(event, guild, s);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in PvM Help configure picker '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private void postGuidelines(EntitySelectInteractionEvent event, Guild guild, HelpSettings s) {
        if (s.helperRoleId() == null || guild.getRoleById(s.helperRoleId()) == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "Choose the PVM Helper role first. The button on that message hands it out.");
            return;
        }
        GuildMessageChannel channel = event.getMentions().getChannels(GuildMessageChannel.class).stream().findFirst().orElse(null);
        if (channel == null || !channel.canTalk()) {
            Containers.replyEphemeral(event, Containers.WARNING, "I can't post in that channel. I need permission to view it and send messages there.");
            return;
        }

        event.deferEdit().queue();
        onboarding.post(guild, channel).whenComplete((done, error) -> {
            if (error != null) log.warn("Posting the helper guidelines to {} failed", channel.getId(), error);
            event.getHook().editOriginalComponents(List.of(buildPanel(guild))).useComponentsV2(true).queue();
            event.getHook().sendMessageComponents(Containers.toast(error == null ? Containers.SUCCESS : Containers.DANGER,
                            error == null ? "✅ The guidelines are posted in " + channel.getAsMention() + "." : "I couldn't post there. Check my permissions in that channel."))
                    .useComponentsV2(true).setEphemeral(true).queue();
        });
    }

    // ---------- helpers ----------

    private static boolean requireAdmin(net.dv8tion.jda.api.interactions.callbacks.IReplyCallback event, Member member) {
        if (member.hasPermission(Permission.ADMINISTRATOR)) return true;
        Containers.replyEphemeral(event, Containers.WARNING, "You need the Administrator permission to configure this bot.");
        return false;
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }
}
