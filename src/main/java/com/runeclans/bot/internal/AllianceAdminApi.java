package com.runeclans.bot.internal;

import com.runeclans.bot.discord.Shards;
import com.runeclans.bot.alliance.AllianceRepository;
import com.runeclans.bot.alliance.AllianceRepository.Alliance;
import com.runeclans.bot.alliance.AllianceRepository.Membership;
import com.runeclans.bot.alliance.AllianceRules;
import com.runeclans.bot.alliance.AllianceRules.Status;
import com.runeclans.bot.alliance.AllianceVisibility.Visibility;
import com.runeclans.bot.clanprofile.ClanProfile;
import com.runeclans.bot.clanprofile.ClanProfileRepository;
import com.runeclans.bot.clanprofile.ClanProfileService;
import com.runeclans.bot.clanprofile.PublicEvents;
import com.runeclans.bot.clanprofile.PublicEvents.EventInfo;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Alliances for the website. {@code GET alliances} lists this server's alliances (and invitations); {@code POST alliances} {@code {name}}
 * starts one; {@code POST alliances/{id}/invite} {@code {slug}} invites a clan by its directory address; {@code .../accept},
 * {@code .../leave} (which also declines an invitation) and {@code .../remove} {@code {guildId}} (only the alliance's founder) change
 * membership; {@code GET alliances/{id}/events} is the shared calendar of the alliance's active members. Events a clan shares with its
 * allies are visible only here, never in the public feeds. Reached only through {@link TicketAdminApi}, which has already checked the
 * asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class AllianceAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AllianceAdminApi.class);
    private static final int EVENTS_LIMIT = 100;

    private final AllianceRepository alliances;
    private final ClanProfileRepository profiles;
    private final ClanProfileService profileService;

    public AllianceAdminApi(AllianceRepository alliances, ClanProfileRepository profiles, ClanProfileService profileService) {
        this.alliances = alliances;
        this.profiles = profiles;
        this.profileService = profileService;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("alliances")) return null;
        if (parts.length == 1) {
            if (method.equals("GET")) return list(guild);
            AdminRouteExtension.requireMethod(method, "POST");
            return create(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[1].equals("visibility")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveVisibility(guild, actor, body.get());
        }
        long id = allianceId(parts[1]);
        if (parts.length == 3 && parts[2].equals("events")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return events(guild, id);
        }
        if (parts.length != 3) return null;
        AdminRouteExtension.requireMethod(method, "POST");
        return switch (parts[2]) {
            case "invite" -> invite(guild, actor, id, body.get());
            case "accept" -> accept(guild, actor, id);
            case "leave" -> leave(guild, actor, id);
            case "remove" -> remove(guild, actor, id, body.get());
            default -> null;
        };
    }

    private static long allianceId(String raw) {
        if (raw == null || !raw.matches("\\d{1,18}")) throw new ApiError(400, Text.en("dashboard.that_isn_t_a_valid_alliance"));
        return Long.parseLong(raw);
    }

    DataObject list(Guild guild) {
        DataArray array = DataArray.empty();
        for (Alliance a : alliances.alliancesOf(guild.getIdLong())) {
            List<Membership> members = alliances.membersOf(a.id());
            Status mine = members.stream().filter(m -> m.guildId() == guild.getIdLong()).map(Membership::status).findFirst().orElse(Status.INVITED);
            DataArray memberArray = DataArray.empty();
            for (Membership m : members) {
                // an invited clan sees who invited it and who is already in, but not the other pending invitations
                if (mine == Status.INVITED && m.status() == Status.INVITED && m.guildId() != guild.getIdLong()) continue;
                memberArray.add(memberJson(guild, a, m));
            }
            array.add(DataObject.empty().put("id", Long.toString(a.id())).put("name", a.name()).put("status", mine.name())
                    .put("founder", a.createdByGuild() == guild.getIdLong()).put("members", memberArray));
        }
        return DataObject.empty().put("alliances", array).put("maxPerServer", AllianceRules.MAX_PER_SERVER).put("maxMembers", AllianceRules.MAX_MEMBERS)
                .put("visibility", alliances.visibility(guild.getIdLong()).name());
    }

    /** What the public hub page of this clan says about its alliances: HIDDEN (nothing, the default), NO_ALLIES (says there are none) or SHOWN (lists its allies). */
    DataObject saveVisibility(Guild guild, Member actor, DataObject body) {
        String raw = body.getString("visibility", "").strip().toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("HIDDEN", "NO_ALLIES", "SHOWN").contains(raw)) throw new ApiError(400, Text.of(guild, "dashboard.choose_whether_your_clan_page_shows"));
        alliances.saveVisibility(guild.getIdLong(), Visibility.parse(raw));
        log.info("Dashboard: {} set the alliance visibility of the clan page to {}", actor.getId(), raw);
        return list(guild);
    }

    private DataObject memberJson(Guild viewer, Alliance a, Membership m) {
        ClanProfile profile = profiles.get(m.guildId()).orElse(null);
        Guild member = Shards.guild(viewer.getJDA(), m.guildId());
        String name = profile != null && profile.displayName() != null && !profile.displayName().isBlank() ? profile.displayName() : member != null ? member.getName() : "A clan";
        return DataObject.empty().put("guildId", Long.toString(m.guildId())).put("name", name).put("slug", profile == null ? null : profile.slug())
                .put("status", m.status().name()).put("founder", m.guildId() == a.createdByGuild()).put("you", m.guildId() == viewer.getIdLong());
    }

    DataObject create(Guild guild, Member actor, DataObject body) {
        String name = body.getString("name", "").strip();
        String problem = AllianceRules.nameProblem(name);
        if (problem == null) problem = AllianceRules.serverLimitProblem(alliances.countFor(guild.getIdLong()));
        if (problem != null) throw new ApiError(400, problem);
        if (alliances.create(name, guild.getIdLong(), actor.getIdLong()).isEmpty()) throw new ApiError(409, Text.of(guild, "dashboard.there_is_already_an_alliance_with"));
        log.info("Dashboard: {} started the alliance {}", actor.getId(), name);
        return list(guild);
    }

    DataObject invite(Guild guild, Member actor, long id, DataObject body) {
        requireActive(guild, id);
        String slug = body.getString("slug", "").strip().toLowerCase(java.util.Locale.ROOT);
        if (slug.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.enter_the_clan_directory_address_its"));
        Optional<ClanProfile> target = profiles.bySlug(slug);
        // only a clan that has chosen to be visible can be invited, so nobody can be pulled into an alliance by guessing a private server
        if (target.isEmpty() || profileService.publicView(target.get().guildId(), true).isEmpty()) throw new ApiError(404, Text.of(guild, "dashboard.no_visible_clan_has_that_address"));
        List<Membership> members = alliances.membersOf(id);
        boolean involved = members.stream().anyMatch(m -> m.guildId() == target.get().guildId());
        String problem = AllianceRules.inviteProblem(target.get().guildId() == guild.getIdLong(), involved, members.size());
        if (problem == null && !involved) problem = AllianceRules.serverLimitProblem(alliances.countFor(target.get().guildId()));
        if (problem != null) throw new ApiError(400, problem);
        if (!alliances.invite(id, target.get().guildId(), actor.getIdLong(), AllianceRules.MAX_MEMBERS)) throw new ApiError(400, Text.of(guild, "dashboard.that_clan_couldn_t_be_invited"));
        log.info("Dashboard: {} invited {} to alliance {}", actor.getId(), target.get().guildId(), id);
        return list(guild);
    }

    DataObject accept(Guild guild, Member actor, long id) {
        if (!alliances.accept(id, guild.getIdLong())) throw new ApiError(404, Text.of(guild, "dashboard.there_is_no_invitation_to_accept"));
        log.info("Dashboard: {} joined alliance {}", actor.getId(), id);
        return list(guild);
    }

    DataObject leave(Guild guild, Member actor, long id) {
        if (!alliances.remove(id, guild.getIdLong())) throw new ApiError(404, Text.of(guild, "dashboard.this_server_isn_t_part_of"));
        log.info("Dashboard: {} left alliance {}", actor.getId(), id);
        return list(guild);
    }

    DataObject remove(Guild guild, Member actor, long id, DataObject body) {
        Alliance alliance = alliances.alliance(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_alliance_doesn_t_exist")));
        requireActive(guild, id);
        if (alliance.createdByGuild() != guild.getIdLong()) throw new ApiError(403, Text.of(guild, "dashboard.only_the_clan_that_started_the"));
        String raw = body.getString("guildId", "").strip();
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_clan_to_remove"));
        long target = Long.parseLong(raw);
        if (target == guild.getIdLong()) throw new ApiError(400, Text.of(guild, "dashboard.use_leave_to_take_your_own"));
        if (!alliances.remove(id, target)) throw new ApiError(404, Text.of(guild, "dashboard.that_clan_isn_t_part_of"));
        log.info("Dashboard: {} removed {} from alliance {}", actor.getId(), target, id);
        return list(guild);
    }

    /** The alliance's shared calendar: upcoming events its active members share with allies or with everyone, soonest first. */
    DataObject events(Guild guild, long id) {
        requireActive(guild, id);
        Instant now = Instant.now();
        record Row(Guild owner, ClanProfile profile, EventInfo event, String scope) {}
        List<Row> rows = new ArrayList<>();
        for (Membership m : alliances.membersOf(id)) {
            if (m.status() != Status.ACTIVE) continue;
            Guild member = Shards.guild(guild.getJDA(), m.guildId());
            if (member == null) continue;
            Map<Long, String> scopes = profiles.eventScopes(m.guildId());
            ClanProfile profile = profiles.get(m.guildId()).orElse(null);
            for (EventInfo e : DirectoryApi.eventsOf(member)) {
                if (scopes.containsKey(e.id()) && PublicEvents.upcoming(e, now)) rows.add(new Row(member, profile, e, scopes.get(e.id())));
            }
        }
        rows.sort(Comparator.comparing((Row r) -> r.event().start()).thenComparingLong(r -> r.event().id()));
        DataArray array = DataArray.empty();
        for (Row r : rows.stream().limit(EVENTS_LIMIT).toList()) {
            String clan = r.profile() != null && r.profile().displayName() != null && !r.profile().displayName().isBlank() ? r.profile().displayName() : r.owner().getName();
            array.add(DataObject.empty().put("id", Long.toString(r.event().id())).put("name", r.event().name()).put("location", r.event().location())
                    .put("status", r.event().status()).put("startTime", r.event().start().toString())
                    .put("endTime", r.event().end() == null ? null : r.event().end().toString()).put("url", r.event().url())
                    .put("clan", clan).put("yours", r.owner().getIdLong() == guild.getIdLong()).put("scope", r.scope()));
        }
        return DataObject.empty().put("events", array);
    }

    private void requireActive(Guild guild, long id) {
        Optional<Membership> mine = alliances.membership(id, guild.getIdLong());
        if (mine.isEmpty() || mine.get().status() != Status.ACTIVE) throw new ApiError(403, Text.of(guild, "dashboard.this_server_isn_t_an_active"));
    }
}
