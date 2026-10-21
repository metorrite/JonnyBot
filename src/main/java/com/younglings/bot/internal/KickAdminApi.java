package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.kick.KickClient;
import com.younglings.bot.kick.KickRepository;
import com.younglings.bot.kick.KickRepository.Settings;
import com.younglings.bot.kick.KickRepository.Streamer;
import com.younglings.bot.kick.KickRules;
import com.younglings.bot.kick.KickRules.OfflineAction;
import com.younglings.bot.kick.KickService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Kick go-live alerts for the dashboard ({@code kick}): the server's switch and what happens when a stream ends, and the streamers it follows.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET kick}: everything, and whether the bot has a Kick app at all</li>
 *   <li>{@code PUT kick/settings} {@code {enabled, offlineAction}}</li>
 *   <li>{@code POST kick/streamers} {@code {login, channelId, pingRoleId?, message?}}: follows a channel that exists on Kick</li>
 *   <li>{@code PUT kick/streamers/{id}} {@code {channelId, pingRoleId, message, enabled}}</li>
 *   <li>{@code DELETE kick/streamers/{id}}</li>
 * </ul>
 */
@BService
public class KickAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(KickAdminApi.class);

    private final KickRepository repository;
    private final KickClient client;

    public KickAdminApi(KickRepository repository, KickClient client) {
        this.repository = repository;
        this.client = client;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("kick")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list(guild);
        }
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, body.get());
        }
        if (parts[1].equals("streamers")) {
            if (parts.length == 2) {
                AdminRouteExtension.requireMethod(method, "POST");
                return add(guild, body.get());
            }
            if (parts.length == 3) {
                long id = parseId(parts[2]);
                if (method.equals("PUT")) return update(guild, id, body.get());
                AdminRouteExtension.requireMethod(method, "DELETE");
                return remove(guild, id);
            }
        }
        return null;
    }

    DataObject list(Guild guild) {
        Settings settings = repository.settings(guild.getIdLong());
        DataArray streamers = DataArray.empty();
        for (Streamer s : repository.streamers(guild.getIdLong())) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, s.channelId());
            String problem = channel == null ? "This channel no longer exists." : KickService.problemWith(channel);
            streamers.add(DataObject.empty().put("id", Long.toString(s.id())).put("login", s.login()).put("displayName", s.displayName()).put("channelId", Long.toString(s.channelId()))
                    .put("pingRoleId", s.pingRoleId() == null ? null : Long.toString(s.pingRoleId())).put("message", s.message()).put("enabled", s.enabled())
                    .put("liveNow", s.postedStream() != null).put("problem", problem));
        }
        return DataObject.empty().put("configured", client.configured()).put("enabled", settings.enabled()).put("offlineAction", settings.offlineAction().name())
                .put("streamers", streamers).put("max", KickRules.MAX_STREAMERS_PER_SERVER).put("maxMessage", KickRules.MAX_MESSAGE_LENGTH).put("defaultMessage", KickRules.DEFAULT_MESSAGE)
                .put("envNames", DataArray.empty().add(KickClient.ID_VARIABLE).add(KickClient.SECRET_VARIABLE));
    }

    DataObject saveSettings(Guild guild, DataObject body) {
        OfflineAction action = OfflineAction.ofKey(body.getString("offlineAction", "")).orElseThrow(() -> new ApiError(400, "Choose what happens to the post when the stream ends."));
        boolean enabled = body.getBoolean("enabled", false);
        if (enabled && !client.configured()) throw new ApiError(400, "Kick alerts cannot be switched on yet: the bot has no Kick app set up. The bot's owner has to add it first.");
        repository.saveSettings(guild.getIdLong(), new Settings(enabled, action));
        return list(guild);
    }

    DataObject add(Guild guild, DataObject body) {
        if (!client.configured()) throw new ApiError(400, "The bot has no Kick app set up yet, so streamers cannot be added. The bot's owner has to add it first.");
        String login = KickRules.loginFrom(body.getString("login", "")).orElseThrow(() -> new ApiError(400, "That is not a Kick channel name. Use the name from the channel's address, like kick.com/name."));
        if (repository.streamers(guild.getIdLong()).size() >= KickRules.MAX_STREAMERS_PER_SERVER) throw new ApiError(400, "A server can follow at most " + KickRules.MAX_STREAMERS_PER_SERVER + " streamers.");
        long channelId = checkedChannel(guild, body);
        Long role = checkedRole(guild, body);
        String message = checkedMessage(body);

        Optional<KickClient.User> user;
        try {
            user = client.user(login);
        } catch (IOException e) {
            log.warn("Kick could not look up {}: {}", login, e.getMessage());
            throw new ApiError(502, "Kick could not be reached just now. Try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(502, "Kick could not be reached just now. Try again in a moment.");
        }
        if (user.isEmpty()) throw new ApiError(404, "There is no Kick channel called \"" + login + "\".");
        if (repository.add(guild.getIdLong(), user.get().login(), user.get().name(), channelId, message, role).isEmpty()) throw new ApiError(409, user.get().name() + " is already followed.");
        return list(guild);
    }

    DataObject update(Guild guild, long id, DataObject body) {
        Streamer existing = repository.get(guild.getIdLong(), id).orElseThrow(() -> new ApiError(404, "That streamer is not followed any more."));
        long channelId = body.hasKey("channelId") ? checkedChannel(guild, body) : existing.channelId();
        Long role = body.hasKey("pingRoleId") ? checkedRole(guild, body) : existing.pingRoleId();
        String message = body.hasKey("message") ? checkedMessage(body) : existing.message();
        boolean enabled = body.getBoolean("enabled", existing.enabled());
        if (!repository.update(guild.getIdLong(), id, channelId, message, role, enabled)) throw new ApiError(404, "That streamer is not followed any more.");
        return list(guild);
    }

    DataObject remove(Guild guild, long id) {
        if (!repository.delete(guild.getIdLong(), id)) throw new ApiError(404, "That streamer is not followed any more.");
        return list(guild);
    }

    // ---------- checks ----------

    private static long checkedChannel(Guild guild, DataObject body) {
        long channelId = parseId(body.getString("channelId", ""));
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, "Choose a text channel from this server for the alerts.");
        String problem = KickService.problemWith(channel);
        if (problem != null) throw new ApiError(400, problem);
        return channelId;
    }

    private static Long checkedRole(Guild guild, DataObject body) {
        if (body.isNull("pingRoleId") || body.getString("pingRoleId", "").isBlank()) return null;
        Role role = guild.getRoleById(parseId(body.getString("pingRoleId", "")));
        if (role == null) throw new ApiError(400, "Choose a role from this server to ping.");
        return role.getIdLong();
    }

    private static String checkedMessage(DataObject body) {
        String message = body.isNull("message") ? "" : body.getString("message", "").strip();
        if (message.length() > KickRules.MAX_MESSAGE_LENGTH) throw new ApiError(400, "Keep the message under " + KickRules.MAX_MESSAGE_LENGTH + " characters.");
        return message.isEmpty() ? null : message;
    }

    private static long parseId(String raw) {
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That is not a valid id.");
        }
    }
}
