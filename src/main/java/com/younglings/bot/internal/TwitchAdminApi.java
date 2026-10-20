package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.twitch.TwitchClient;
import com.younglings.bot.twitch.TwitchRepository;
import com.younglings.bot.twitch.TwitchRepository.Settings;
import com.younglings.bot.twitch.TwitchRepository.Streamer;
import com.younglings.bot.twitch.TwitchRules;
import com.younglings.bot.twitch.TwitchRules.OfflineAction;
import com.younglings.bot.twitch.TwitchService;
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
 * Twitch go-live alerts for the dashboard ({@code twitch}): the server's switch and what happens when a stream ends, and the streamers it follows.
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET twitch}: everything, and whether the bot has a Twitch app at all</li>
 *   <li>{@code PUT twitch/settings} {@code {enabled, offlineAction}}</li>
 *   <li>{@code POST twitch/streamers} {@code {login, channelId, pingRoleId?, message?}}: follows a channel that exists on Twitch</li>
 *   <li>{@code PUT twitch/streamers/{id}} {@code {channelId, pingRoleId, message, enabled}}</li>
 *   <li>{@code DELETE twitch/streamers/{id}}</li>
 * </ul>
 */
@BService
public class TwitchAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(TwitchAdminApi.class);

    private final TwitchRepository repository;
    private final TwitchClient client;

    public TwitchAdminApi(TwitchRepository repository, TwitchClient client) {
        this.repository = repository;
        this.client = client;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("twitch")) return null;
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
            String problem = channel == null ? "This channel no longer exists." : TwitchService.problemWith(channel);
            streamers.add(DataObject.empty().put("id", Long.toString(s.id())).put("login", s.login()).put("displayName", s.displayName()).put("channelId", Long.toString(s.channelId()))
                    .put("pingRoleId", s.pingRoleId() == null ? null : Long.toString(s.pingRoleId())).put("message", s.message()).put("enabled", s.enabled())
                    .put("liveNow", s.postedStream() != null).put("problem", problem));
        }
        return DataObject.empty().put("configured", client.configured()).put("enabled", settings.enabled()).put("offlineAction", settings.offlineAction().name())
                .put("streamers", streamers).put("max", TwitchRules.MAX_STREAMERS_PER_SERVER).put("maxMessage", TwitchRules.MAX_MESSAGE_LENGTH).put("defaultMessage", TwitchRules.DEFAULT_MESSAGE)
                .put("envNames", DataArray.empty().add(TwitchClient.ID_VARIABLE).add(TwitchClient.SECRET_VARIABLE));
    }

    DataObject saveSettings(Guild guild, DataObject body) {
        OfflineAction action = OfflineAction.ofKey(body.getString("offlineAction", "")).orElseThrow(() -> new ApiError(400, "Choose what happens to the post when the stream ends."));
        boolean enabled = body.getBoolean("enabled", false);
        if (enabled && !client.configured()) throw new ApiError(400, "Twitch alerts cannot be switched on yet: the bot has no Twitch app set up. The bot's owner has to add it first.");
        repository.saveSettings(guild.getIdLong(), new Settings(enabled, action));
        return list(guild);
    }

    DataObject add(Guild guild, DataObject body) {
        if (!client.configured()) throw new ApiError(400, "The bot has no Twitch app set up yet, so streamers cannot be added. The bot's owner has to add it first.");
        String login = TwitchRules.loginFrom(body.getString("login", "")).orElseThrow(() -> new ApiError(400, "That is not a Twitch channel name. Use the name from the channel's address, like twitch.tv/name."));
        if (repository.streamers(guild.getIdLong()).size() >= TwitchRules.MAX_STREAMERS_PER_SERVER) throw new ApiError(400, "A server can follow at most " + TwitchRules.MAX_STREAMERS_PER_SERVER + " streamers.");
        long channelId = checkedChannel(guild, body);
        Long role = checkedRole(guild, body);
        String message = checkedMessage(body);

        Optional<TwitchClient.User> user;
        try {
            user = client.user(login);
        } catch (IOException e) {
            log.warn("Twitch could not look up {}: {}", login, e.getMessage());
            throw new ApiError(502, "Twitch could not be reached just now. Try again in a moment.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiError(502, "Twitch could not be reached just now. Try again in a moment.");
        }
        if (user.isEmpty()) throw new ApiError(404, "There is no Twitch channel called \"" + login + "\".");
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
        String problem = TwitchService.problemWith(channel);
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
        if (message.length() > TwitchRules.MAX_MESSAGE_LENGTH) throw new ApiError(400, "Keep the message under " + TwitchRules.MAX_MESSAGE_LENGTH + " characters.");
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
