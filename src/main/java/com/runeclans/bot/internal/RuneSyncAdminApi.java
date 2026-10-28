package com.runeclans.bot.internal;

import com.runeclans.bot.notice.BotOwners;
import com.runeclans.bot.plugin.RuneSyncService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * The owner's RuneSync diagnostics viewer (T-202) under {@code /internal/admin/runesync/...}, for the RuneClan Admin console (runeclans.com/admin):
 * {@code GET runesync/diagnostics/{id}} opens one snapshot a member sent from RuneSync's panel for a bug report. Snapshots were redacted
 * when stored (no tokens, assertions, tracking ids or chat text). Owner only; anyone else gets 403 whether or not the id exists.
 */
@BService
public class RuneSyncAdminApi implements AdminRouteExtension {
    private final RuneSyncService runeSync;
    private final BotOwners owners;

    public RuneSyncAdminApi(RuneSyncService runeSync, BotOwners owners) {
        this.runeSync = runeSync;
        this.owners = owners;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 3 || !parts[0].equals("runesync") || !parts[1].equals("diagnostics")) return null;
        if (!owners.isOwner(guild.getJDA(), actor.getIdLong())) throw AdminRouteExtension.refuse(403, "Only the bot's owner can open RuneSync diagnostics.");
        AdminRouteExtension.requireMethod(method, "GET");
        try {
            DataObject found = runeSync.diagnostic(parts[2], Instant.now());
            if (found == null) throw AdminRouteExtension.refuse(404, "No snapshot has that id (they are kept " + RuneSyncService.SNAPSHOT_KEPT.toDays() + " days).");
            return found;
        } catch (RuneSyncService.RuneSyncException e) {
            throw AdminRouteExtension.refuse(e.status(), e.getMessage());
        }
    }
}
