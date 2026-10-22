package com.younglings.bot.internal;

import com.younglings.bot.goodbye.GoodbyeConfig;
import com.younglings.bot.goodbye.GoodbyeRepository;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's goodbye message for the website. A save is checked whole before anything is stored. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class GoodbyeAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(GoodbyeAdminApi.class);

    private final GoodbyeRepository repository;

    public GoodbyeAdminApi(GoodbyeRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("goodbye")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        GoodbyeConfig config = repository.get(guild.getIdLong());
        return DataObject.empty().put("enabled", config.enabled()).put("channelId", config.channelId() == null ? null : Long.toString(config.channelId()))
                .put("content", config.content()).put("maxContent", GoodbyeConfig.MAX_CONTENT).put("serverName", guild.getName()).put("memberCount", guild.getMemberCount());
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        GoodbyeConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();

        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        Long channelId = current.channelId();
        if (body.hasKey("channelId")) {
            String raw = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
            if (raw.isEmpty()) {
                channelId = null;
            } else if (!raw.matches("\\d{1,20}")) {
                problems.add("That channel isn't valid.");
            } else {
                channelId = Long.parseLong(raw);
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                if (channel == null) problems.add("That channel doesn't exist in this server.");
                else if (!channel.canTalk()) problems.add("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
            }
        }
        String content = body.hasKey("content") ? body.getString("content", "").strip() : current.content();
        if (content.length() > GoodbyeConfig.MAX_CONTENT) problems.add("The message can be at most " + GoodbyeConfig.MAX_CONTENT + " characters.");
        if (enabled && channelId == null) problems.add("Choose a channel before switching the goodbye message on.");

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_goodbye_message_can_t_be"), problems);

        repository.save(new GoodbyeConfig(guild.getIdLong(), enabled, channelId, content));
        log.info("Dashboard: {} saved the goodbye message ({})", actor.getId(), enabled ? "on" : "off");
        return get(guild);
    }
}
