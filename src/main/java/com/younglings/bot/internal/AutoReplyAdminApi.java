package com.younglings.bot.internal;

import com.younglings.bot.autoreply.AutoReply;
import com.younglings.bot.autoreply.AutoReplyRepository;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's automatic replies for the website: list, add, change, delete. Each save is checked whole before anything is stored.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and
 * audit-logs every change.
 */
@BService
public class AutoReplyAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AutoReplyAdminApi.class);

    private final AutoReplyRepository repository;

    public AutoReplyAdminApi(AutoReplyRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("autoresponders")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return save(guild, actor, null, body.get());
        }
        if (parts.length == 2) {
            if (method.equals("PUT")) return save(guild, actor, parts[1], body.get());
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        return null;
    }

    DataObject list(Guild guild) {
        DataArray replies = DataArray.empty();
        for (AutoReply r : repository.forGuild(guild.getIdLong())) replies.add(json(r));
        return DataObject.empty().put("replies", replies).put("max", AutoReply.MAX_PER_GUILD).put("maxTrigger", AutoReply.MAX_TRIGGER).put("maxReply", AutoReply.MAX_REPLY)
                .put("minCooldown", AutoReply.MIN_COOLDOWN).put("maxCooldown", AutoReply.MAX_COOLDOWN);
    }

    DataObject save(Guild guild, Member actor, String rawId, DataObject body) {
        Long id = rawId == null ? null : parseId(rawId);
        if (id != null && repository.get(guild.getIdLong(), id).isEmpty()) throw new ApiError(404, "That reply doesn't exist.");
        List<String> problems = new ArrayList<>();

        String trigger = body.getString("trigger", "").strip();
        if (trigger.isEmpty() || trigger.length() > AutoReply.MAX_TRIGGER) problems.add("The trigger must be 1 to " + AutoReply.MAX_TRIGGER + " characters.");
        String reply = body.getString("reply", "").strip();
        if (reply.isEmpty() || reply.length() > AutoReply.MAX_REPLY) problems.add("The reply must be 1 to " + AutoReply.MAX_REPLY + " characters.");
        AutoReply.Match match = AutoReply.Match.WORD;
        try {
            match = AutoReply.Match.valueOf(body.getString("match", "WORD"));
        } catch (IllegalArgumentException e) {
            problems.add("Choose how the trigger is matched.");
        }
        int cooldown = body.getInt("cooldownSeconds", 30);
        if (cooldown < AutoReply.MIN_COOLDOWN || cooldown > AutoReply.MAX_COOLDOWN) problems.add("The cooldown must be between " + AutoReply.MIN_COOLDOWN + " seconds and 24 hours.");
        boolean enabled = body.getBoolean("enabled", true);
        if (id == null && repository.forGuild(guild.getIdLong()).size() >= AutoReply.MAX_PER_GUILD) problems.add("A server can have at most " + AutoReply.MAX_PER_GUILD + " automatic replies.");
        final long self = id == null ? -1 : id;
        final AutoReply.Match chosen = match;
        if (!trigger.isEmpty() && repository.forGuild(guild.getIdLong()).stream().anyMatch(r -> r.id() != self && r.match() == chosen && r.trigger().equalsIgnoreCase(trigger))) {
            problems.add("There is already a reply for that trigger.");
        }

        if (!problems.isEmpty()) throw new ApiError(400, "That reply can't be saved yet.", problems);

        AutoReply saved = repository.save(new AutoReply(id, guild.getIdLong(), trigger, match, reply, cooldown, enabled));
        log.info("Dashboard: {} saved automatic reply {} ({} on \"{}\")", actor.getId(), saved.id(), saved.match(), trigger);
        return json(saved);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        long id = parseId(rawId);
        if (!repository.delete(guild.getIdLong(), id)) throw new ApiError(404, "That reply doesn't exist.");
        log.info("Dashboard: {} deleted automatic reply {}", actor.getId(), id);
        return list(guild);
    }

    private static long parseId(String raw) {
        if (raw == null || !raw.strip().matches("\\d{1,18}")) throw new ApiError(400, "That isn't a valid reply.");
        return Long.parseLong(raw.strip());
    }

    private static DataObject json(AutoReply r) {
        return DataObject.empty().put("id", Long.toString(r.id())).put("trigger", r.trigger()).put("match", r.match().name()).put("reply", r.reply())
                .put("cooldownSeconds", r.cooldownSeconds()).put("enabled", r.enabled());
    }
}
