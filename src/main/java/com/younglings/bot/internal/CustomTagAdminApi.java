package com.younglings.bot.internal;

import com.younglings.bot.customtag.CustomTag;
import com.younglings.bot.customtag.CustomTag.Delivery;
import com.younglings.bot.customtag.CustomTagRepository;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A server's custom tags for the website: the switch, and list / add / change / delete. Each save is checked whole before anything is stored. Reached only
 * through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class CustomTagAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(CustomTagAdminApi.class);

    private final CustomTagRepository repository;

    public CustomTagAdminApi(CustomTagRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("custom-tags")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            boolean on = body.get().getBoolean("enabled", false);
            repository.setEnabled(guild.getIdLong(), on);
            log.info("Dashboard: {} switched custom tags {}", actor.getId(), on ? "on" : "off");
            return list(guild);
        }
        if (parts.length >= 2 && parts[1].equals("tags")) {
            if (parts.length == 2) {
                AdminRouteExtension.requireMethod(method, "POST");
                return save(guild, actor, null, body.get());
            }
            if (parts.length == 3) {
                if (method.equals("PUT")) return save(guild, actor, parts[2], body.get());
                AdminRouteExtension.requireMethod(method, "DELETE");
                return delete(guild, actor, parts[2]);
            }
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray tags = DataArray.empty();
        for (CustomTag t : repository.forGuild(guild.getIdLong())) tags.add(json(t));
        return DataObject.empty().put("enabled", repository.enabled(guild.getIdLong())).put("tags", tags)
                .put("limits", DataObject.empty().put("max", CustomTag.MAX_PER_GUILD).put("maxName", CustomTag.MAX_NAME).put("maxContent", CustomTag.MAX_CONTENT)
                        .put("maxTitle", CustomTag.MAX_TITLE).put("maxRoles", CustomTag.MAX_ROLES).put("maxCooldown", CustomTag.MAX_COOLDOWN));
    }

    private static DataObject json(CustomTag t) {
        return DataObject.empty().put("id", Long.toString(t.id())).put("name", t.name()).put("content", t.content()).put("embed", t.embed()).put("embedTitle", t.embedTitle())
                .put("embedColor", CustomTag.hex(t.embedColor())).put("delivery", t.delivery().name())
                .put("allowedRoles", DataArray.fromCollection(t.allowedRoles().stream().map(String::valueOf).toList())).put("cooldownSeconds", t.cooldownSeconds())
                .put("enabled", t.enabled()).put("uses", t.uses());
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_tag"));
        return Long.parseLong(raw.strip());
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        Long id = rawId == null ? null : parseId(rawId);
        if (id != null && repository.get(guild.getIdLong(), id).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.that_tag_doesn_t_exist"));
        List<String> problems = new ArrayList<>();

        String name = CustomTag.normalise(body.getString("name", ""));
        if (!CustomTag.validName(name)) problems.add("A tag's name is up to " + CustomTag.MAX_NAME + " letters, numbers, - or _, starting with a letter or number.");
        String content = body.getString("content", "").strip();
        if (content.isEmpty() || content.length() > CustomTag.MAX_CONTENT) problems.add("A tag's text must be 1 to " + CustomTag.MAX_CONTENT + " characters.");
        boolean embed = body.getBoolean("embed", false);
        String title = body.getString("embedTitle", "").strip();
        if (title.length() > CustomTag.MAX_TITLE) problems.add("The title can be at most " + CustomTag.MAX_TITLE + " characters.");
        Integer colour = null;
        String rawColour = body.getString("embedColor", "").strip();
        if (!rawColour.isEmpty()) {
            colour = CustomTag.parseColour(rawColour);
            if (colour == null) problems.add("The colour must look like #5865f2.");
        }
        Delivery delivery = Delivery.CHANNEL;
        try {
            delivery = Delivery.valueOf(body.getString("delivery", "CHANNEL"));
        } catch (IllegalArgumentException e) {
            problems.add("Choose where the tag is answered.");
        }
        int cooldown = body.getInt("cooldownSeconds", 0);
        if (cooldown < 0 || cooldown > CustomTag.MAX_COOLDOWN) problems.add("The wait between uses must be between 0 and 24 hours.");
        Set<Long> roles = new LinkedHashSet<>();
        if (body.hasKey("allowedRoles")) {
            DataArray input = body.getArray("allowedRoles");
            if (input.length() > CustomTag.MAX_ROLES) problems.add("At most " + CustomTag.MAX_ROLES + " roles can be listed.");
            for (int i = 0; i < input.length() && i < CustomTag.MAX_ROLES; i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getRoleById(Long.parseLong(raw)) == null) problems.add("A role that may use the tag doesn't exist in this server.");
                else roles.add(Long.parseLong(raw));
            }
        }
        boolean enabled = body.getBoolean("enabled", true);
        if (id == null && repository.forGuild(guild.getIdLong()).size() >= CustomTag.MAX_PER_GUILD) problems.add("A server can have at most " + CustomTag.MAX_PER_GUILD + " tags.");
        final long self = id == null ? -1 : id;
        if (CustomTag.validName(name) && repository.forGuild(guild.getIdLong()).stream().anyMatch(t -> t.id() != self && t.name().equals(name))) problems.add("There is already a tag called \"" + name + "\".");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_tag_can_t_be_saved"), problems);

        long uses = id == null ? 0 : repository.get(guild.getIdLong(), id).map(CustomTag::uses).orElse(0L);
        CustomTag saved = repository.save(new CustomTag(id, guild.getIdLong(), name, content, embed, title, colour, delivery, roles, cooldown, enabled, uses));
        log.info("Dashboard: {} saved the tag {} ({})", actor.getId(), saved.id(), name);
        return json(saved);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        long id = parseId(rawId);
        if (!repository.delete(guild.getIdLong(), id)) throw new ApiError(404, Text.of(guild, "dashboard.that_tag_doesn_t_exist"));
        log.info("Dashboard: {} deleted the tag {}", actor.getId(), id);
        return list(guild);
    }
}
