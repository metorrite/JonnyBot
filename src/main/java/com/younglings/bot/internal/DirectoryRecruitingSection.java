package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanRecruiting;
import com.younglings.bot.clanprofile.ClanRecruitingRepository;
import com.younglings.bot.clanprofile.JoinLinks;
import com.younglings.bot.forms.WebFormRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

/** The recruiting post on a clan's page (T-070); it has no routes of its own. */
@BService
public class DirectoryRecruitingSection implements DirectoryRouteModule {
    private final ClanRecruitingRepository recruiting;
    private final WebFormRepository forms;
    private final JoinLinks joinLinks;

    public DirectoryRecruitingSection(ClanRecruitingRepository recruiting, WebFormRepository forms, JoinLinks joinLinks) {
        this.recruiting = recruiting;
        this.forms = forms;
        this.joinLinks = joinLinks;
    }

    @Override
    public void addToClan(ClanProfile profile, DataObject json) {
        json.put("recruitingPost", recruitingJson(profile));
        // the clan's own website shows its join link whether or not it is recruiting (T-633); the Clans Hub page keeps the post's rule
        json.put("joinLink", joinLinks.of(profile.guildId(), profile.applyUrl()));
    }

    /**
     * The clan's recruiting post: what it is looking for, its invite, and the address of the application form the hub's Apply button opens.
     * The form only while the clan is recruiting and the form is switched on; its address is the public form page the clan already shares.
     * The invite is the server's one join link ({@link JoinLinks}), the same one its hub events show.
     */
    private DataObject recruitingJson(ClanProfile p) {
        ClanRecruiting r = recruiting.of(p.guildId());
        boolean open = p.recruiting() != ClanProfile.Recruiting.CLOSED;
        String formPath = null;
        if (open && r.applyFormId() != null) {
            formPath = forms.get(r.applyFormId()).filter(f -> f.guildId() == p.guildId() && f.enabled()).map(f -> "/forms/" + f.guildId() + "/" + f.id()).orElse(null);
        }
        return DataObject.empty().put("post", r.post()).put("applyFormPath", formPath).put("inviteUrl", open ? joinLinks.of(p.guildId(), p.applyUrl()) : null);
    }
}
