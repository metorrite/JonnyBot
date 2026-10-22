package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.voicetext.VoiceTextRepository;
import com.younglings.bot.voicetext.VoiceTextRepository.Config;
import com.younglings.bot.voicetext.VoiceTextService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The dashboard's Voice text page: {@code GET voice-text} and {@code PUT voice-text} ({@code enabled}, {@code announce}, {@code purgeWhenEmpty}, and
 * {@code links} as a list of {@code {voiceChannelId, textChannelId}}). A save is checked whole before anything changes. Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class VoiceTextAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(VoiceTextAdminApi.class);

    private final VoiceTextRepository repository;
    private final VoiceTextService service;

    public VoiceTextAdminApi(VoiceTextRepository repository, VoiceTextService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("voice-text")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        Config config = repository.config(guild.getIdLong());
        DataArray links = DataArray.empty();
        for (var link : repository.links(guild.getIdLong()).entrySet()) {
            VoiceChannel voice = guild.getVoiceChannelById(link.getKey());
            TextChannel text = guild.getTextChannelById(link.getValue());
            String problem = voice == null ? "The voice channel no longer exists."
                    : text == null ? "The text channel no longer exists."
                    : !VoiceTextService.canManage(guild, text) ? "JonnyBot needs View Channel, Send Messages and Manage Permissions in #" + text.getName() + "."
                    : null;
            boolean everyoneCanSee = text != null && guild.getPublicRole().hasPermission(text, Permission.VIEW_CHANNEL);
            links.add(DataObject.empty().put("voiceChannelId", Long.toString(link.getKey())).put("voiceName", voice == null ? null : voice.getName())
                    .put("textChannelId", Long.toString(link.getValue())).put("textName", text == null ? null : text.getName())
                    .put("problem", problem).put("everyoneCanSee", everyoneCanSee));
        }
        DataArray voiceChannels = DataArray.empty();
        for (VoiceChannel v : guild.getVoiceChannels()) voiceChannels.add(DataObject.empty().put("id", v.getId()).put("name", v.getName()));
        return DataObject.empty().put("enabled", config.enabled()).put("announce", config.announce()).put("purgeWhenEmpty", config.purgeWhenEmpty())
                .put("links", links).put("voiceChannels", voiceChannels).put("maxLinks", Config.MAX_LINKS);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        Config current = repository.config(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        boolean announce = body.hasKey("announce") ? body.getBoolean("announce", false) : current.announce();
        boolean purge = body.hasKey("purgeWhenEmpty") ? body.getBoolean("purgeWhenEmpty", false) : current.purgeWhenEmpty();

        Map<Long, Long> before = repository.links(guild.getIdLong());
        Map<Long, Long> links = before;
        if (body.hasKey("links")) {
            links = new LinkedHashMap<>();
            DataArray input = body.getArray("links");
            if (input.length() > Config.MAX_LINKS) problems.add("At most " + Config.MAX_LINKS + " voice channels can be linked.");
            java.util.Set<Long> textUsed = new java.util.HashSet<>();
            for (int i = 0; i < input.length() && i < Config.MAX_LINKS; i++) {
                DataObject link = input.getObject(i);
                String v = link.getString("voiceChannelId", "").strip();
                String t = link.getString("textChannelId", "").strip();
                VoiceChannel voice = v.matches("\\d{1,20}") ? guild.getVoiceChannelById(Long.parseLong(v)) : null;
                TextChannel text = t.matches("\\d{1,20}") ? guild.getTextChannelById(Long.parseLong(t)) : null;
                if (voice == null) {
                    problems.add("A linked voice channel doesn't exist in this server.");
                    continue;
                }
                if (text == null) {
                    problems.add("The text channel for " + voice.getName() + " doesn't exist in this server.");
                    continue;
                }
                if (!VoiceTextService.canManage(guild, text)) problems.add("JonnyBot needs View Channel, Send Messages and Manage Permissions in #" + text.getName() + " to give people access.");
                if (links.containsKey(voice.getIdLong())) problems.add(voice.getName() + " is linked twice.");
                if (!textUsed.add(text.getIdLong())) problems.add("#" + text.getName() + " is linked to more than one voice channel: leaving one would take away access that the other still needs.");
                links.put(voice.getIdLong(), text.getIdLong());
            }
        }
        if (enabled && links.isEmpty()) problems.add("Link at least one voice channel before switching this on.");
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.those_settings_can_t_be_saved"), problems.stream().distinct().toList());

        repository.saveConfig(new Config(guild.getIdLong(), enabled, announce, purge));
        if (body.hasKey("links")) {
            repository.saveLinks(guild.getIdLong(), links);
            // text channels no longer paired lose the access JonnyBot gave in them
            for (long oldText : before.values()) if (!links.containsValue(oldText)) service.releaseAll(guild, oldText);
        }
        log.info("Dashboard: {} saved the voice text links ({}, {} pairs)", actor.getId(), enabled ? "on" : "off", links.size());
        return get(guild);
    }
}
