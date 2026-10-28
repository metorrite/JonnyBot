package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.lookup.LookupReminder;
import com.runeclans.bot.lookup.LookupReminderRepository;
import com.runeclans.bot.lookup.ReminderKind;
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
import java.util.List;
import java.util.function.Supplier;

/**
 * The RuneScape timer reminders of a server for the website (route {@code lookups}): per reminder an on/off switch, the channel it posts in and the role it
 * pings. The lookup commands themselves (/price, /worldevents, ...) are switched on and off on the Commands page like any command. A save is checked whole
 * before anything is stored. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server.
 */
@BService
public class LookupAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(LookupAdminApi.class);

    private final LookupReminderRepository repository;

    public LookupAdminApi(LookupReminderRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("lookups")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        DataArray reminders = DataArray.empty();
        for (LookupReminder r : repository.forGuild(guild.getIdLong())) {
            reminders.add(DataObject.empty().put("kind", r.kind().key()).put("title", r.kind().title()).put("description", r.kind().description())
                    .put("enabled", r.enabled()).put("channelId", r.channelId() == null ? null : Long.toString(r.channelId()))
                    .put("roleId", r.roleId() == null ? null : Long.toString(r.roleId())));
        }
        return DataObject.empty().put("reminders", reminders);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        List<LookupReminder> toSave = new ArrayList<>();
        DataArray input = body.hasKey("reminders") ? body.getArray("reminders") : DataArray.empty();
        for (int i = 0; i < input.length(); i++) {
            DataObject in = input.getObject(i);
            ReminderKind kind = ReminderKind.ofKey(in.getString("kind", "")).orElse(null);
            if (kind == null) {
                problems.add("There is no reminder called \"" + in.getString("kind", "") + "\".");
                continue;
            }
            boolean enabled = in.getBoolean("enabled", false);
            Long channelId = null;
            String rawChannel = in.isNull("channelId") ? "" : in.getString("channelId", "").strip();
            if (!rawChannel.isEmpty()) {
                if (!rawChannel.matches("\\d{1,20}")) {
                    problems.add(kind.title() + ": that channel isn't valid.");
                } else {
                    channelId = Long.parseLong(rawChannel);
                    GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
                    if (channel == null) problems.add(kind.title() + ": that channel doesn't exist in this server.");
                    else if (!channel.canTalk()) problems.add(kind.title() + ": the bot can't post in #" + channel.getName() + ". It needs permission to view the channel and send messages there.");
                }
            }
            if (enabled && channelId == null) problems.add(kind.title() + ": choose a channel before switching it on.");
            Long roleId = null;
            String rawRole = in.isNull("roleId") ? "" : in.getString("roleId", "").strip();
            if (!rawRole.isEmpty()) {
                if (!rawRole.matches("\\d{1,20}")) {
                    problems.add(kind.title() + ": that role isn't valid.");
                } else {
                    roleId = Long.parseLong(rawRole);
                    Role role = guild.getRoleById(roleId);
                    if (role == null) problems.add(kind.title() + ": that role doesn't exist in this server.");
                    else if (role.isPublicRole()) problems.add(kind.title() + ": pick a role members opt in to, not everyone.");
                }
            }
            toSave.add(new LookupReminder(guild.getIdLong(), kind, enabled, channelId, roleId, ""));
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_reminders_can_t_be_saved"), problems);
        toSave.forEach(repository::save);
        log.info("Dashboard: {} saved the timer reminders ({} changed)", actor.getId(), toSave.size());
        return get(guild);
    }
}
