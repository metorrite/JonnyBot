package com.younglings.bot.internal;

import com.younglings.bot.clanprofile.ClanRecruiting;
import com.younglings.bot.clanprofile.ClanRecruitingRepository;
import com.younglings.bot.forms.WebFormRepository;
import com.younglings.bot.forms.WebForms.Form;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A server's recruiting post on the Clans Hub, for the dashboard's Clan profile page: {@code GET clan-recruiting} (the post, the chosen
 * application form, the invite, and this server's web forms to choose from) and {@code PUT clan-recruiting} {@code {post, applyFormId,
 * inviteUrl}}, checked whole before anything is stored. Reached only through {@link TicketAdminApi}, which checks the asker and audit-logs.
 */
@BService
public class ClanRecruitingAdminApi implements AdminRouteExtension {
    private final ClanRecruitingRepository repository;
    private final WebFormRepository forms;

    public ClanRecruitingAdminApi(ClanRecruitingRepository repository, WebFormRepository forms) {
        this.repository = repository;
        this.forms = forms;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("clan-recruiting")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, body.get());
    }

    DataObject get(Guild guild) {
        ClanRecruiting r = repository.of(guild.getIdLong());
        DataArray list = DataArray.empty();
        for (Form f : forms.list(guild.getIdLong())) {
            list.add(DataObject.empty().put("id", Long.toString(f.id())).put("name", f.title().isBlank() ? f.name() : f.title()).put("enabled", f.enabled()));
        }
        return DataObject.empty().put("post", r.post()).put("applyFormId", r.applyFormId() == null ? null : Long.toString(r.applyFormId()))
                .put("inviteUrl", r.inviteUrl()).put("forms", list).put("maxPost", ClanRecruiting.MAX_POST);
    }

    DataObject save(Guild guild, DataObject body) {
        List<String> problems = new ArrayList<>();
        String post = body.getString("post", "").strip();
        if (post.length() > ClanRecruiting.MAX_POST) problems.add("The recruiting post can be at most " + ClanRecruiting.MAX_POST + " characters.");

        Long formId = null;
        String rawForm = body.getString("applyFormId", "");
        if (rawForm != null && !rawForm.isBlank()) {
            try {
                long id = Long.parseLong(rawForm.strip());
                if (forms.get(id).filter(f -> f.guildId() == guild.getIdLong()).isEmpty()) problems.add("Pick one of this server's web forms.");
                else formId = id;
            } catch (NumberFormatException e) {
                problems.add("Pick one of this server's web forms.");
            }
        }

        String invite = body.getString("inviteUrl", "").strip();
        if (invite.isEmpty()) invite = null;
        else if (!ClanRecruiting.validInvite(invite)) problems.add("The invite must be a Discord invite link, like https://discord.gg/abc123.");

        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.the_recruiting_post_can_t_be"), problems);
        repository.save(new ClanRecruiting(guild.getIdLong(), post, formId, invite, Instant.now()));
        return get(guild);
    }
}
