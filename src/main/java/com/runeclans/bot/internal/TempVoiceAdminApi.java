package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.tempvoice.TempVoiceRepository;
import com.runeclans.bot.tempvoice.TempVoiceRules;
import com.runeclans.bot.tempvoice.TempVoiceRules.Hub;
import com.runeclans.bot.tempvoice.TempVoiceRules.Temp;
import com.runeclans.bot.tempvoice.TempVoiceService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's temporary voice hubs for the website. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the
 * dashboard in this server and audit-logs every change.
 * <ul>
 *   <li>{@code GET tempvoice}: the hubs, the voice channels and categories to choose from, and the temporary channels in use</li>
 *   <li>{@code PUT tempvoice} {@code {hubChannelId | createHub:true, hubName?, categoryId?, namePattern, userLimit}}: makes a voice channel a hub (or creates one) or changes a hub</li>
 *   <li>{@code DELETE tempvoice/{hubChannelId}}: stops it being a hub (the channel stays; channels already made last until they empty)</li>
 * </ul>
 */
@BService
public class TempVoiceAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(TempVoiceAdminApi.class);

    private final TempVoiceRepository repository;

    public TempVoiceAdminApi(TempVoiceRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("tempvoice")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "DELETE");
            return delete(guild, actor, parts[1]);
        }
        return null;
    }

    DataObject get(Guild guild) {
        DataArray hubs = DataArray.empty();
        for (Hub hub : repository.hubs(guild.getIdLong())) {
            VoiceChannel channel = guild.getVoiceChannelById(hub.hubChannelId());
            Category category = hub.categoryId() == null ? null : guild.getCategoryById(hub.categoryId());
            String problem = channel == null ? "This channel no longer exists." : null;
            hubs.add(DataObject.empty().put("hubChannelId", Long.toString(hub.hubChannelId())).put("hubChannelName", channel == null ? null : channel.getName())
                    .put("categoryId", hub.categoryId() == null ? null : Long.toString(hub.categoryId())).put("categoryName", category == null ? null : category.getName())
                    .put("namePattern", hub.namePattern()).put("userLimit", hub.userLimit()).put("problem", problem));
        }
        DataArray voice = DataArray.empty();
        for (VoiceChannel v : guild.getVoiceChannels()) {
            voice.add(DataObject.empty().put("id", v.getId()).put("name", v.getName()).put("category", v.getParentCategory() == null ? null : v.getParentCategory().getName()));
        }
        DataArray categories = DataArray.empty();
        for (Category c : guild.getCategories()) categories.add(DataObject.empty().put("id", c.getId()).put("name", c.getName()));
        DataArray active = DataArray.empty();
        List<Temp> temps = repository.channels(guild.getIdLong());
        for (Temp t : temps) {
            VoiceChannel channel = guild.getVoiceChannelById(t.channelId());
            Member owner = guild.getMemberById(t.ownerId());
            active.add(DataObject.empty().put("channelId", Long.toString(t.channelId())).put("name", channel == null ? null : channel.getName())
                    .put("ownerName", owner == null ? null : owner.getEffectiveName()).put("members", channel == null ? 0 : channel.getMembers().size()).put("locked", t.locked()));
        }
        return DataObject.empty().put("hubs", hubs).put("voiceChannels", voice).put("categories", categories).put("active", active)
                .put("problem", TempVoiceService.problemWith(guild)).put("maxHubs", TempVoiceRules.MAX_HUBS_PER_GUILD).put("maxChannels", TempVoiceRules.MAX_TEMP_CHANNELS_PER_GUILD)
                .put("maxName", TempVoiceRules.MAX_NAME).put("maxUserLimit", TempVoiceRules.MAX_USER_LIMIT).put("defaultPattern", TempVoiceRules.DEFAULT_PATTERN);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        List<String> problems = new ArrayList<>();
        String pattern = body.isNull("namePattern") ? "" : body.getString("namePattern", "").strip();
        int limit = body.getInt("userLimit", 0);
        problems.addAll(TempVoiceRules.hubProblems(pattern, limit));
        String permission = TempVoiceService.problemWith(guild);
        if (permission != null) problems.add(permission);

        Category category = null;
        String rawCategory = body.isNull("categoryId") ? "" : body.getString("categoryId", "").strip();
        if (!rawCategory.isEmpty()) {
            category = rawCategory.matches("\\d{1,20}") ? guild.getCategoryById(Long.parseLong(rawCategory)) : null;
            if (category == null) problems.add("That category isn't in this server.");
        }

        boolean create = body.getBoolean("createHub", false);
        VoiceChannel hubChannel = null;
        if (!create) {
            String rawChannel = body.isNull("hubChannelId") ? "" : body.getString("hubChannelId", "").strip();
            hubChannel = rawChannel.matches("\\d{1,20}") ? guild.getVoiceChannelById(Long.parseLong(rawChannel)) : null;
            if (hubChannel == null) problems.add("Choose the voice channel people join to get their own, or let the bot create one.");
            else if (repository.channel(hubChannel.getIdLong()).isPresent()) problems.add("That channel is a temporary channel itself, so it can't be a hub.");
        }
        boolean isNew = create || (hubChannel != null && repository.hubByChannel(hubChannel.getIdLong()).isEmpty());
        if (isNew && repository.hubs(guild.getIdLong()).size() >= TempVoiceRules.MAX_HUBS_PER_GUILD) problems.add("A server can have at most " + TempVoiceRules.MAX_HUBS_PER_GUILD + " hubs.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_hub_can_t_be_saved"), problems);

        if (create) {
            String name = TempVoiceRules.cleanName(body.isNull("hubName") ? "" : body.getString("hubName", ""));
            try {
                hubChannel = guild.createVoiceChannel(name.equals("Voice channel") ? "➕ Create voice channel" : name, category).reason("Temporary voice hub created from the dashboard").complete();
            } catch (RuntimeException e) {
                throw new ApiError(400, Text.of(guild, "dashboard.discord_refused_to_create_the_channel"), List.of("Check the bot may manage channels" + (category == null ? "." : " in that category.")));
            }
        }
        repository.saveHub(new Hub(guild.getIdLong(), hubChannel.getIdLong(), category == null ? null : category.getIdLong(), pattern, limit));
        log.info("Dashboard: {} {} the temporary voice hub #{}", actor.getId(), isNew ? "added" : "changed", hubChannel.getName());
        return get(guild);
    }

    DataObject delete(Guild guild, Member actor, String rawId) {
        if (rawId == null || !rawId.strip().matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_valid_channel"));
        if (!repository.deleteHub(guild.getIdLong(), Long.parseLong(rawId.strip()))) throw new ApiError(404, Text.of(guild, "dashboard.that_channel_isn_t_a_hub"));
        log.info("Dashboard: {} removed the temporary voice hub {}", actor.getId(), rawId.strip());
        return get(guild);
    }
}
