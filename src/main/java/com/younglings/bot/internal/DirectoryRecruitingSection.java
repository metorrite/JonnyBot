package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanProfile;
import com.younglings.bot.clanprofile.ClanRecruiting;
import com.younglings.bot.clanprofile.ClanRecruitingRepository;
import com.younglings.bot.forms.WebFormRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

/** The recruiting post on a clan's page (T-070); it has no routes of its own. */
@BService
public class DirectoryRecruitingSection implements DirectoryRouteModule {
    private final ClanRecruitingRepository recruiting;
    private final WebFormRepository forms;

    public DirectoryRecruitingSection(ClanRecruitingRepository recruiting, WebFormRepository forms) {
        this.recruiting = recruiting;
        this.forms = forms;
    }

    @Override
    public void addToClan(ClanProfile profile, DataObject json) {
        json.put("recruitingPost", recruitingJson(profile));
    }

    /**
     * The clan's recruiting post: what it is looking for, its invite, and the address of the application form the hub's Apply button opens.
     * The form only while the clan is recruiting and the form is switched on; its address is the public form page the clan already shares.
     */
    private DataObject recruitingJson(ClanProfile p) {
        ClanRecruiting r = recruiting.of(p.guildId());
        boolean open = p.recruiting() != ClanProfile.Recruiting.CLOSED;
        String formPath = null;
        if (open && r.applyFormId() != null) {
            formPath = forms.get(r.applyFormId()).filter(f -> f.guildId() == p.guildId() && f.enabled()).map(f -> "/forms/" + f.guildId() + "/" + f.id()).orElse(null);
        }
        return DataObject.empty().put("post", r.post()).put("applyFormPath", formPath).put("inviteUrl", open && ClanRecruiting.validInvite(r.inviteUrl()) ? r.inviteUrl() : null);
    }
}
