package com.runeclans.bot.internal;

import com.runeclans.bot.devtools.DevSnapshotRepository;
import com.runeclans.bot.devtools.DevSnapshotService;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.notice.BotOwners;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.Base64;
import java.util.function.Supplier;

/**
 * The dev snapshot routes the dashboard calls, bot owner only (the dashboard's own check has already passed by the time a request
 * gets here; this is the second, stricter one). A non-owner gets 403 on every path, so the existence of a snapshot is not revealed.
 * <ul>
 *   <li>{@code GET dev/snapshot}: take a snapshot of this server now. {@code POST dev/snapshot} does the same and also reads
 *       {@code {"focus": "event:12", "siteCommit": "abc123"}} from the body.</li>
 *   <li>{@code GET dev/snapshots}: the stored snapshots, newest first.</li>
 *   <li>{@code GET dev/snapshots/<id>}: one snapshot with its zip as base64; {@code DELETE dev/snapshots/<id>} removes it.</li>
 * </ul>
 */
@BService
public class DevSnapshotAdminApi implements AdminRouteExtension {
    private final DevSnapshotService service;
    private final DevSnapshotRepository repository;
    private final BotOwners owners;

    public DevSnapshotAdminApi(DevSnapshotService service, DevSnapshotRepository repository, BotOwners owners) {
        this.service = service;
        this.repository = repository;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 2 || !parts[0].equals("dev")) return null;
        boolean create = parts[1].equals("snapshot") && parts.length == 2;
        boolean stored = parts[1].equals("snapshots") && parts.length <= 3;
        if (!create && !stored) return null;
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw new ApiError(403, Text.of(guild, "dashboard.only_the_bot_owner_can_use"));

        if (create) {
            if (method.equals("POST")) {
                DataObject input = body.get();
                return take(guild, actor, input.getString("focus", ""), input.getString("siteCommit", ""));
            }
            AdminRouteExtension.requireMethod(method, "GET");
            return take(guild, actor, "", "");
        }
        if (parts.length == 2) {
            AdminRouteExtension.requireMethod(method, "GET");
            return list();
        }
        String id = parts[2];
        if (!DevSnapshotService.ID_PATTERN.matcher(id).matches()) throw new ApiError(400, Text.of(guild, "dashboard.that_isn_t_a_snapshot_id"));
        if (method.equals("DELETE")) {
            if (!repository.delete(id)) throw new ApiError(404, Text.of(guild, "dashboard.that_snapshot_doesn_t_exist_snapshots", DevSnapshotRepository.KEEP_DAYS));
            return DataObject.empty().put("deleted", id);
        }
        AdminRouteExtension.requireMethod(method, "GET");
        var meta = repository.find(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_snapshot_doesn_t_exist_snapshots", DevSnapshotRepository.KEEP_DAYS)));
        byte[] zip = repository.zip(id).orElseThrow(() -> new ApiError(404, Text.of(guild, "dashboard.that_snapshot_doesn_t_exist")));
        return meta(meta).put("filename", id + ".zip").put("zipBase64", Base64.getEncoder().encodeToString(zip));
    }

    private DataObject take(Guild guild, Member actor, String focus, String siteCommit) {
        try {
            var result = service.create(guild, actor.getIdLong(), actor.getEffectiveName(), focus, siteCommit);
            return DataObject.empty().put("id", result.id()).put("guildId", guild.getId()).put("createdAt", result.createdAt().toString()).put("focus", result.focus())
                    .put("requestedBy", actor.getId()).put("sizeBytes", result.zip().length).put("filename", result.id() + ".zip")
                    .put("zipBase64", Base64.getEncoder().encodeToString(result.zip()));
        } catch (DevSnapshotService.SnapshotException e) {
            throw new ApiError(e.status(), e.getMessage());
        }
    }

    private DataObject list() {
        DataArray snapshots = DataArray.empty();
        for (var meta : repository.list()) snapshots.add(meta(meta));
        return DataObject.empty().put("snapshots", snapshots).put("keepDays", DevSnapshotRepository.KEEP_DAYS).put("maxBytes", DevSnapshotService.MAX_BYTES);
    }

    private static DataObject meta(DevSnapshotRepository.Meta meta) {
        return DataObject.empty().put("id", meta.id()).put("guildId", Long.toString(meta.guildId())).put("createdAt", meta.createdAt().toInstant().toString())
                .put("sizeBytes", meta.sizeBytes()).put("requestedBy", Long.toString(meta.requestedBy())).put("focus", meta.focus());
    }
}
