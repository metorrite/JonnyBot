package com.younglings.bot.internal;

import com.younglings.bot.alliance.AllianceRepository;
import com.younglings.bot.alliance.AllianceRules.Status;
import com.younglings.bot.alliance.AllianceVisibility;
import com.younglings.bot.alliance.AllianceVisibility.Ally;
import com.younglings.bot.alliance.AllianceVisibility.AllianceView;
import com.younglings.bot.alliance.AllianceVisibility.Group;
import com.younglings.bot.alliance.AllianceVisibility.Section;
import com.younglings.bot.alliance.AllianceVisibility.Visibility;
import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanProfileRepository;
import com.younglings.bot.clanprofile.ClanProfileService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Alliances in the directory (T-069): {@code GET alliances/{id}} names only the clans that chose to show their alliances (404 unless at
 * least two did), and a clan's page says which alliances it is in only when it chose to.
 */
@BService
public class DirectoryAllianceRoutes implements DirectoryRouteModule {
    private final AllianceRepository alliances;
    private final ClanProfileRepository repository;
    private final ClanProfileService service;

    public DirectoryAllianceRoutes(AllianceRepository alliances, ClanProfileRepository repository, ClanProfileService service) {
        this.alliances = alliances;
        this.repository = repository;
        this.service = service;
    }

    @Override
    public boolean route(DirectoryRequest request) throws IOException {
        String[] parts = request.parts();
        if (!request.head().equals("alliances") || parts.length != 2 || !parts[1].matches("\\d{1,18}")) return false;
        if (!request.requireGet()) return true;
        DataObject page = alliancePage(Long.parseLong(parts[1]));
        if (page == null) request.sendError(404, "No such alliance");
        else request.send(200, page);
        return true;
    }

    @Override
    public void addToClan(ClanProfile profile, DataObject json) {
        json.put("alliances", alliancesJson(profile.guildId()));
    }

    /** An alliance's members as the visibility rules need them. */
    private List<AllianceView> viewsOf(List<AllianceRepository.Alliance> list) {
        List<AllianceView> views = new ArrayList<>();
        for (var a : list) {
            var members = alliances.membersOf(a.id());
            var choices = alliances.visibilities(members.stream().map(m -> m.guildId()).toList());
            List<Ally> allies = new ArrayList<>();
            for (var m : members) {
                boolean listed = service.publicView(m.guildId(), false).isPresent();
                allies.add(new Ally(m.guildId(), choices.getOrDefault(m.guildId(), Visibility.HIDDEN), listed, m.status() == Status.ACTIVE));
            }
            views.add(new AllianceView(a.id(), a.name(), allies));
        }
        return views;
    }

    /** What a clan's page says about its alliances, or null when its clan chose to say nothing. Only clans that chose to be shown, and are listed, are ever named. */
    private DataObject alliancesJson(long guildId) {
        Visibility choice = alliances.visibility(guildId);
        List<AllianceView> views = choice == Visibility.SHOWN ? viewsOf(alliances.alliancesOf(guildId)) : List.of();
        Section section = AllianceVisibility.sectionFor(guildId, choice, views);
        if (section.mode() == AllianceVisibility.Mode.ABSENT) return null;
        DataArray groups = DataArray.empty();
        for (Group g : section.groups()) {
            DataArray clans = DataArray.empty();
            for (long ally : g.allies()) {
                repository.get(ally).ifPresent(p -> {
                    if (p.slug() != null) clans.add(DataObject.empty().put("slug", p.slug()).put("name", p.displayName()).put("logoUrl", p.logoUrl()).put("colour", p.colour()));
                });
            }
            groups.add(DataObject.empty().put("id", Long.toString(g.allianceId())).put("name", g.name()).put("clans", clans).put("hasPage", g.pageAvailable()));
        }
        return DataObject.empty().put("mode", section.mode().name()).put("alliances", groups);
    }

    /** An alliance's public page, naming only the clans that chose to show their alliances. Null when fewer than two can be named (so a hidden clan's alliance is not revealed). */
    private DataObject alliancePage(long id) {
        var alliance = alliances.alliance(id);
        if (alliance.isEmpty()) return null;
        AllianceView view = viewsOf(List.of(alliance.get())).get(0);
        if (!AllianceVisibility.hasPage(view)) return null;
        DataArray clans = DataArray.empty();
        for (Ally ally : AllianceVisibility.nameableMembers(view)) {
            repository.get(ally.guildId()).ifPresent(p -> {
                if (p.slug() != null) clans.add(DataObject.empty().put("slug", p.slug()).put("name", p.displayName()).put("tagline", p.tagline()).put("logoUrl", p.logoUrl()).put("colour", p.colour()));
            });
        }
        return clans.length() < 2 ? null : DataObject.empty().put("id", Long.toString(id)).put("name", alliance.get().name()).put("clans", clans);
    }
}
