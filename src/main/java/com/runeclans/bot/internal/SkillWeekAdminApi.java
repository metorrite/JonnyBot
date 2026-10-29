package com.runeclans.bot.internal;

import com.runeclans.bot.events.SkillWeekRepository;
import com.runeclans.bot.events.SkillWeekRules;
import com.runeclans.bot.events.SkillWeekService;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.runescape.RuneScapeSkillCatalog;
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
 * The dashboard's Skill of the week (T-541), reached through {@link TicketAdminApi} (dashboard access already checked, writes audit-logged):
 * <ul>
 *     <li>{@code GET skill-week}: the switch, the rota, the skill next in line, this week and its standings, last week's winners, and the skills to
 *     choose from</li>
 *     <li>{@code PUT skill-week} {@code {enabled, rota: [skillId...], mode, announceChannelId, autoEnrol}}: saves them. Switching on needs the
 *     bot connected to the RuneClans hub (the weeks are counted from its XP ledger) and at least one skill. Switching on opens this week's
 *     competition straight away. The options (T-711) are off unless sent: {@code mode} {@code "rota"} or {@code "vote"} (members vote for next
 *     week's skill among the rota's on the clan website), {@code announceChannelId} a channel of this server (or null for no posts) and
 *     {@code autoEnrol}.</li>
 * </ul>
 */
@BService
public class SkillWeekAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(SkillWeekAdminApi.class);

    private final SkillWeekService weeks;
    private final SkillWeekRepository repository;

    public SkillWeekAdminApi(SkillWeekService weeks, SkillWeekRepository repository) {
        this.weeks = weeks;
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("skill-week")) return null;
        if (method.equals("PUT")) {
            DataObject sent = body.get();
            Long channel = channelOf(sent);
            // checked only for a new channel: one the bot later lost the right to post in never blocks saving anything else (or turning it off)
            if (channel != null && !channel.equals(savedChannel(guild.getIdLong()))) {
                var target = guild.getChannelById(net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel.class, channel);
                if (target == null) throw new ApiError(400, "Choose a channel of this server for the announcements.");
                // canTalk, as the announcement job checks (threads need their own send permission, not MESSAGE_SEND)
                if (!target.canTalk()) {
                    throw new ApiError(400, "The bot needs to see and send messages in #" + target.getName() + " to announce the week there.");
                }
            }
            return save(guild.getIdLong(), actor.getIdLong(), sent);
        }
        AdminRouteExtension.requireMethod(method, "GET");
        return overview(guild.getIdLong());
    }

    /** The announcement channel saved now, or null for none. */
    private Long savedChannel(long guildId) {
        SkillWeekRepository.Settings saved = repository.settings(guildId);
        return saved == null || saved.options() == null ? null : saved.options().announceChannelId();
    }

    /** The announcement channel sent, or null for none; a value that isn't a Discord id is refused. */
    static Long channelOf(DataObject body) {
        if (!body.hasKey("announceChannelId") || body.isNull("announceChannelId")) return null;
        String raw = body.get("announceChannelId").toString();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "Choose a channel of this server for the announcements.");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "Choose a channel of this server for the announcements.");
        }
    }

    DataObject overview(long guildId) {
        DataArray skills = DataArray.empty();
        for (int id = 0; id < RuneScapeSkillCatalog.skillCount(); id++) skills.add(DataObject.empty().put("id", id).put("name", RuneScapeSkillCatalog.nameFor(id)));
        return weeks.view(guildId, 10).put("skills", skills).put("maxRota", SkillWeekRules.MAX_ROTA);
    }

    DataObject save(long guildId, long actorId, DataObject body) {
        boolean enabled = body.getBoolean("enabled", false);
        List<Integer> rota = new ArrayList<>();
        try {
            DataArray raw = body.optArray("rota").orElse(DataArray.empty());
            for (int i = 0; i < raw.length(); i++) rota.add(raw.getInt(i));
        } catch (RuntimeException e) {
            throw new ApiError(400, "Choose each skill from the list.");
        }
        String mode = body.getString("mode", "rota");
        if (!mode.equals("rota") && !mode.equals("vote")) throw new ApiError(400, "Choose the rota or a members' vote.");
        SkillWeekRepository.Options options = new SkillWeekRepository.Options(mode.equals("vote") ? SkillWeekRepository.Mode.VOTE : SkillWeekRepository.Mode.ROTA,
                channelOf(body), body.getBoolean("autoEnrol", false));
        List<String> problems = SkillWeekRules.rotaProblems(rota, enabled);
        if (enabled && options.mode() == SkillWeekRepository.Mode.VOTE && rota.size() < 2) problems.add("A vote needs at least two skills to choose from.");
        if (enabled && !weeks.available()) problems.add("Skill of the week counts XP from RuneSync, through the RuneClans hub, and this bot isn't connected to it.");
        if (!problems.isEmpty()) throw new ApiError(400, "Skill of the week can't be saved.", problems);
        repository.save(guildId, enabled, rota, options, actorId);
        log.info("Dashboard: {} saved skill of the week ({}, {} skills, {}, posts {}, auto-enrol {})", actorId, enabled ? "on" : "off", rota.size(), mode,
                options.announceChannelId() == null ? "off" : "on", options.autoEnrol() ? "on" : "off");
        if (enabled) {
            // this week starts now rather than at the next pass
            try {
                weeks.open(guildId, java.time.Instant.now());
            } catch (RuntimeException e) {
                log.warn("Could not open skill of the week in {} on saving", guildId, e);
            }
        }
        return overview(guildId);
    }
}
