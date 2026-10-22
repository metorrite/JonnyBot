package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.rolepanel.RolePanel;
import com.younglings.bot.rolepanel.RolePanel.RoleButton;
import com.younglings.bot.rolepanel.RolePanelMessage;
import com.younglings.bot.rolepanel.RolePanelRepository;
import com.younglings.bot.rolepanel.RolePanelService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's role panels for the website: list, read, create, change, delete, and post one in a channel. A save is checked whole
 * before anything is stored, so a refused save changes nothing and says why. Reached only through {@link TicketAdminApi}, which has
 * already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class RolePanelAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(RolePanelAdminApi.class);
    private static final Set<String> STYLES = Set.of("primary", "secondary", "success", "danger");

    private final RolePanelRepository repository;
    private final RolePanelService service;

    public RolePanelAdminApi(RolePanelRepository repository, RolePanelService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("rolepanels")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return save(guild, actor, null, body.get());
        }
        if (parts.length == 2) {
            if (method.equals("GET")) return get(guild, parts[1]);
            if (method.equals("PUT")) return save(guild, actor, parts[1], body.get());
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        if (parts.length == 3 && parts[2].equals("post")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return post(guild, actor, parts[1], body.get());
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray panels = DataArray.empty();
        for (RolePanel panel : repository.list(guild.getIdLong())) {
            GuildMessageChannel channel = panel.channelId() == null ? null : guild.getChannelById(GuildMessageChannel.class, panel.channelId());
            panels.add(DataObject.empty().put("id", Long.toString(panel.id())).put("name", panel.name()).put("title", panel.title()).put("mode", panel.mode().name())
                    .put("style", panel.style().name()).put("rule", panel.rule().name())
                    .put("buttonCount", panel.buttons().size()).put("posted", panel.posted())
                    .put("channelId", panel.channelId() == null ? null : Long.toString(panel.channelId())).put("channelName", channel == null ? null : channel.getName()));
        }
        return DataObject.empty().put("panels", panels).put("maxPanels", RolePanel.MAX_PANELS).put("maxButtons", RolePanel.MAX_BUTTONS)
                .put("limits", DataObject.empty().put("BUTTONS", RolePanel.Style.BUTTONS.maxItems()).put("DROPDOWN", RolePanel.Style.DROPDOWN.maxItems()).put("REACTIONS", RolePanel.Style.REACTIONS.maxItems()));
    }

    DataObject get(Guild guild, String rawId) {
        return json(find(guild, rawId));
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        Long id = rawId == null ? null : parseId(rawId);
        RolePanel existing = id == null ? null : repository.get(guild.getIdLong(), id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_panel_doesn_t_exist")));
        List<String> problems = new ArrayList<>();

        String name = body.getString("name", "").strip();
        String title = body.getString("title", "").strip();
        String description = body.getString("description", "").strip();
        if (name.isEmpty() || name.length() > 60) problems.add("Give the panel a name of 1 to 60 characters.");
        if (title.length() > 256) problems.add("The title can be at most 256 characters.");
        if (description.length() > 4000) problems.add("The description can be at most 4000 characters.");
        Integer color = null;
        if (!body.isNull("color") && body.hasKey("color")) {
            color = body.getInt("color", -1);
            if (color < 0 || color > 0xFFFFFF) {
                problems.add("That colour isn't valid.");
                color = null;
            }
        }
        RolePanel.Mode mode;
        try {
            mode = RolePanel.Mode.valueOf(body.getString("mode", "TOGGLE"));
        } catch (IllegalArgumentException e) {
            mode = RolePanel.Mode.TOGGLE;
            problems.add("Choose how members pick roles: any number, or only one.");
        }
        RolePanel.Style style;
        try {
            style = RolePanel.Style.valueOf(body.getString("style", "BUTTONS"));
        } catch (IllegalArgumentException e) {
            style = RolePanel.Style.BUTTONS;
            problems.add("Choose how members pick roles: buttons, a drop-down or reactions.");
        }
        RolePanel.Rule rule;
        try {
            rule = RolePanel.Rule.valueOf(body.getString("rule", "NORMAL"));
        } catch (IllegalArgumentException e) {
            rule = RolePanel.Rule.NORMAL;
            problems.add("Choose whether members can take and drop roles, only take them, or only drop them.");
        }
        if (!name.isEmpty() && repository.list(guild.getIdLong()).stream().anyMatch(p -> p.name().equalsIgnoreCase(name) && (id == null || !p.id().equals(id)))) {
            problems.add("Another panel is already called \"" + name + "\".");
        }
        if (id == null && repository.list(guild.getIdLong()).size() >= RolePanel.MAX_PANELS) problems.add("A server can have at most " + RolePanel.MAX_PANELS + " role panels.");

        List<RoleButton> buttons = new ArrayList<>();
        DataArray rawButtons = body.hasKey("buttons") ? body.getArray("buttons") : DataArray.empty();
        final int max = style.maxItems();
        String what = switch (style) {
            case BUTTONS -> "buttons";
            case DROPDOWN -> "options";
            case REACTIONS -> "reactions";
        };
        if (rawButtons.length() > max) problems.add("A " + style.name().toLowerCase() + " panel can have at most " + max + " " + what + " (Discord's limit).");
        Set<Long> seen = new HashSet<>();
        Set<String> seenEmoji = new HashSet<>();
        for (int i = 0; i < rawButtons.length() && i < max; i++) {
            DataObject b = rawButtons.getObject(i);
            String roleText = b.getString("roleId", "").strip();
            String where = (style == RolePanel.Style.BUTTONS ? "Button " : style == RolePanel.Style.DROPDOWN ? "Option " : "Reaction ") + (i + 1);
            if (!roleText.matches("\\d{1,20}")) {
                problems.add(where + ": choose a role.");
                continue;
            }
            long roleId = Long.parseLong(roleText);
            Role role = guild.getRoleById(roleId);
            if (role == null) problems.add(where + ": that role doesn't exist in this server.");
            else if (!RolePanelService.canGive(guild, role)) problems.add(where + ": @" + role.getName() + " can't be handed out (it is managed by an integration, sits above JonnyBot's role, or has powerful permissions).");
            if (!seen.add(roleId)) problems.add(where + ": that role is already on another button.");

            String label = b.getString("label", "").strip();
            if (label.length() > 80) problems.add(where + ": the label can be at most 80 characters.");
            String emoji = b.isNull("emoji") ? "" : b.getString("emoji", "").strip();
            if (!emoji.isEmpty() && RolePanelMessage.emoji(emoji) == null) problems.add(where + ": that emoji isn't one Discord can show.");
            if (style == RolePanel.Style.REACTIONS) {
                if (emoji.isEmpty()) problems.add(where + ": a reaction needs an emoji.");
                else if (!seenEmoji.add(emoji)) problems.add(where + ": that emoji is already used by another reaction.");
            }
            if (style == RolePanel.Style.DROPDOWN && label.length() > 100) problems.add(where + ": an option's label can be at most 100 characters.");
            String colour = b.getString("style", "primary").toLowerCase();
            if (!STYLES.contains(colour)) {
                problems.add(where + ": choose a colour for the button.");
                colour = "primary";
            }
            buttons.add(new RoleButton(roleId, label, emoji.isEmpty() ? null : emoji, colour));
        }

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_panel_can_t_be_saved"), problems);

        RolePanel saved = repository.save(new RolePanel(id, guild.getIdLong(), name, title, description, color, mode,
                existing == null ? null : existing.channelId(), existing == null ? null : existing.messageId(), buttons, style, rule,
                existing != null && existing.attached()));
        log.info("Dashboard: {} saved role panel {} ({}, {} buttons)", actor.getId(), saved.id(), name, buttons.size());
        return json(saved);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        RolePanel panel = find(guild, rawId);
        // take its message down too, so buttons that no longer do anything aren't left in a channel (a message it was attached to keeps everything but our reactions)
        service.takeDown(guild, panel);
        repository.delete(guild.getIdLong(), panel.id());
        log.info("Dashboard: {} deleted role panel {} ({})", actor.getId(), panel.id(), panel.name());
        return list(guild);
    }

    DataObject post(Guild guild, Member actor, String rawId, DataObject body) {
        RolePanel panel = find(guild, rawId);
        String link = body.isNull("messageLink") ? "" : body.getString("messageLink", "").strip();
        if (!link.isEmpty()) {
            try {
                RolePanel attached = service.attach(guild, panel, link);
                log.info("Dashboard: {} put role panel {} on the message {}", actor.getId(), panel.id(), link);
                return json(attached);
            } catch (RolePanelService.PostException e) {
                throw new ApiError(400, e.getMessage());
            }
        }
        String channelText = body.getString("channelId", "").strip();
        if (!channelText.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel_to_post_in"));
        try {
            RolePanel posted = service.post(guild, panel, Long.parseLong(channelText));
            log.info("Dashboard: {} posted role panel {} in channel {}", actor.getId(), panel.id(), channelText);
            return json(posted);
        } catch (RolePanelService.PostException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    private RolePanel find(Guild guild, String rawId) {
        return repository.get(guild.getIdLong(), parseId(rawId)).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_panel_doesn_t_exist")));
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_panel"));
        return Long.parseLong(raw.strip());
    }

    private static DataObject json(RolePanel panel) {
        DataArray buttons = DataArray.empty();
        for (RoleButton b : panel.buttons()) {
            buttons.add(DataObject.empty().put("roleId", Long.toString(b.roleId())).put("label", b.label()).put("emoji", b.emoji()).put("style", b.style()));
        }
        return DataObject.empty().put("id", Long.toString(panel.id())).put("name", panel.name()).put("title", panel.title()).put("description", panel.description())
                .put("color", panel.color()).put("mode", panel.mode().name()).put("style", panel.style().name()).put("rule", panel.rule().name())
                .put("attached", panel.attached()).put("posted", panel.posted())
                .put("channelId", panel.channelId() == null ? null : Long.toString(panel.channelId()))
                .put("messageId", panel.messageId() == null ? null : Long.toString(panel.messageId())).put("buttons", buttons);
    }
}
