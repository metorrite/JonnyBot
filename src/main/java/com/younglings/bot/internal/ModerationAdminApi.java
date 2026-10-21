package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.moderation.ModCase;
import com.younglings.bot.moderation.ModCaseRepository;
import com.younglings.bot.moderation.ModService;
import com.younglings.bot.moderation.ModSettings;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The dashboard's Moderation page: a server's numbered cases (latest, by member, by moderator, one case), editing a case's reason, and the
 * moderation settings (DM the reason, show the moderator, appeal link, protected roles). Nothing here moderates anyone: bans, kicks and
 * timeouts are done with the slash commands, which check the role hierarchy against the moderator who runs them, something a dashboard
 * click has no moderator to compare with. Reached only through {@link TicketAdminApi}, which has already checked the asker may use the
 * dashboard in this server and audit-logs every change.
 */
@BService
public class ModerationAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(ModerationAdminApi.class);
    static final int LIMIT = 100;

    private final ModCaseRepository cases;

    public ModerationAdminApi(ModCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || !parts[0].equals("moderation")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        if (parts.length == 2 && parts[1].equals("settings")) {
            AdminRouteExtension.requireMethod(method, "PUT");
            return saveSettings(guild, actor, body.get());
        }
        if (parts.length == 3 && parts[1].equals("user")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return DataObject.empty().put("cases", caseList(cases.forUser(guild.getIdLong(), id(parts[2]), LIMIT)));
        }
        if (parts.length == 3 && parts[1].equals("moderator")) {
            AdminRouteExtension.requireMethod(method, "GET");
            return DataObject.empty().put("cases", caseList(cases.byModerator(guild.getIdLong(), id(parts[2]), LIMIT)));
        }
        if (parts.length == 3 && parts[1].equals("case")) {
            long number = id(parts[2]);
            if (method.equals("GET")) return DataObject.empty().put("case", caseJson(cases.find(guild.getIdLong(), number).orElseThrow(() -> new ApiError(404, "There is no such case."))));
            AdminRouteExtension.requireMethod(method, "PUT");
            return editCase(guild, actor, number, body.get());
        }
        return null;
    }

    DataObject overview(Guild guild) {
        ModSettings s = cases.settings(guild.getIdLong());
        DataArray roles = DataArray.empty();
        s.protectedRoleIds().forEach(id -> roles.add(Long.toString(id)));
        return DataObject.empty()
                .put("settings", DataObject.empty().put("dmReason", s.dmReason()).put("showModerator", s.showModerator()).put("appealUrl", s.appealUrl()).put("protectedRoleIds", roles))
                .put("maxProtectedRoles", ModSettings.MAX_PROTECTED_ROLES).put("limit", LIMIT)
                .put("cases", caseList(cases.recent(guild.getIdLong(), LIMIT)));
    }

    DataObject saveSettings(Guild guild, Member actor, DataObject body) {
        ModSettings current = cases.settings(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        boolean dm = body.getBoolean("dmReason", current.dmReason());
        boolean showModerator = body.getBoolean("showModerator", current.showModerator());

        String appeal = current.appealUrl();
        if (body.hasKey("appealUrl")) {
            String raw = body.isNull("appealUrl") ? "" : body.getString("appealUrl").strip();
            appeal = ModService.cleanAppealUrl(raw);
            if (!raw.isEmpty() && appeal == null) problems.add("The appeal link isn't a usable web address (use something like https://example.com/appeal).");
        }

        List<Long> roles = current.protectedRoleIds();
        if (body.hasKey("protectedRoleIds") && !body.isNull("protectedRoleIds")) {
            Set<Long> out = new LinkedHashSet<>();
            DataArray input = body.getArray("protectedRoleIds");
            for (int i = 0; i < input.length(); i++) {
                String raw = input.getString(i, "").strip();
                if (!raw.matches("\\d{1,20}") || guild.getRoleById(Long.parseLong(raw)) == null) {
                    problems.add("A protected role doesn't exist in this server any more.");
                    continue;
                }
                out.add(Long.parseLong(raw));
            }
            if (out.size() > ModSettings.MAX_PROTECTED_ROLES) problems.add("At most " + ModSettings.MAX_PROTECTED_ROLES + " protected roles.");
            roles = List.copyOf(out);
        }
        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved yet.", problems);

        cases.saveSettings(new ModSettings(guild.getIdLong(), dm, showModerator, appeal, roles));
        log.info("Dashboard: {} saved the moderation settings of {}", actor.getId(), guild.getId());
        return overview(guild);
    }

    DataObject editCase(Guild guild, Member actor, long number, DataObject body) {
        String reason = body.getString("reason", "").strip();
        if (reason.isEmpty()) throw new ApiError(400, "Write a reason.");
        if (reason.length() > ModService.MAX_REASON) throw new ApiError(400, "A reason can be at most " + ModService.MAX_REASON + " characters.");
        if (!cases.editReason(guild.getIdLong(), number, reason, actor.getIdLong())) throw new ApiError(404, "There is no such case.");
        log.info("Dashboard: {} edited the reason of case {} in {}", actor.getId(), number, guild.getId());
        return DataObject.empty().put("case", caseJson(cases.find(guild.getIdLong(), number).orElseThrow()));
    }

    private static long id(String raw) {
        if (!raw.matches("\\d{1,19}")) throw new ApiError(400, "That isn't a valid number.");
        return Long.parseLong(raw);
    }

    private static DataArray caseList(List<ModCase> list) {
        DataArray out = DataArray.empty();
        list.forEach(c -> out.add(caseJson(c)));
        return out;
    }

    static DataObject caseJson(ModCase c) {
        return DataObject.empty().put("caseNo", Long.toString(c.caseNo())).put("userId", Long.toString(c.userId())).put("userName", c.userName())
                .put("moderatorId", Long.toString(c.moderatorId())).put("moderatorName", c.moderatorName()).put("action", c.action().name()).put("actionLabel", c.action().label)
                .put("reason", c.reason()).put("durationMinutes", c.durationMinutes()).put("source", c.source()).put("createdAt", c.createdAt().toString())
                .put("editedAt", c.editedAt() == null ? null : c.editedAt().toString()).put("editedBy", c.editedBy() == null ? null : Long.toString(c.editedBy()))
                .put("originalReason", c.originalReason());
    }
}
