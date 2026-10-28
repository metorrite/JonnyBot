package com.runeclans.bot.internal;

import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.permission.OwnerOverride;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/**
 * The bot owner's "lift restrictions" route and rules (T-445), kept out of {@link TicketAdminApi} so each gate can be tested on its own:
 * {@code owner-override} on the guild-scoped admin API.
 * <ul>
 *   <li>{@code GET} the state; {@code POST {"acknowledged": true, "minutes": n}} lifts the restrictions on this server for {@code minutes}
 *   (the Switches panel's {@code owner-override-minutes}, kept within {@link OwnerOverride#MIN_MINUTES} to {@link OwnerOverride#MAX_MINUTES},
 *   {@link OwnerOverride#DEFAULT_MINUTES} when left out); {@code DELETE} ends it now. All answer {@code {active, until, minutes}}.</li>
 *   <li>The bot's owner only (403 for anyone else, whatever they send); a lift without the acknowledgement is 400.</li>
 *   <li>The lift is written to the server's audit log BEFORE it takes effect, so a lift that couldn't be logged never happens.</li>
 * </ul>
 */
final class OwnerOverrideRoute {
    /** Added to the name in the server's audit log for every change made with restrictions lifted. */
    static final String OVERRIDE_MARK = " (bot owner, restrictions lifted)";
    static final String ROUTE = "owner-override";

    /** Where audit rows go ({@code AdminToolsStore.audit}). */
    interface Audit {
        void write(long guildId, String actorId, String actorName, String method, String route, int status);
    }

    private OwnerOverrideRoute() {}

    static DataObject handle(OwnerOverride override, LongPredicate isBotOwner, Supplier<DataObject> body, Audit audit, Supplier<String> ownerName,
                             long guildId, long askerId, String method) {
        if (askerId == 0 || !isBotOwner.test(askerId)) throw new TicketAdminApi.ApiError(403, "Only the bot's owner can lift the restrictions on a server.");
        switch (method) {
            case "GET" -> {
            }
            case "POST" -> {
                DataObject b = body.get();
                if (!b.getBoolean("acknowledged", false)) {
                    throw new TicketAdminApi.ApiError(400, "Confirm that you understand you are changing another clan's settings before lifting the restrictions.");
                }
                Integer minutes = b.isType("minutes", DataType.INT) ? b.getInt("minutes") : null;
                // logged first: if the audit row can't be written, this throws and the restrictions stay in place
                audit.write(guildId, Long.toString(askerId), ownerName.get() + OVERRIDE_MARK, "POST", ROUTE + "/" + OwnerOverride.minutes(minutes) + "min", 200);
                override.lift(guildId, askerId, minutes);
            }
            case "DELETE" -> {
                if (override.end(guildId, askerId)) audit.write(guildId, Long.toString(askerId), ownerName.get() + " (bot owner)", "DELETE", ROUTE, 200);
            }
            default -> throw new TicketAdminApi.ApiError(405, Text.en("dashboard.method_not_allowed"));
        }
        return state(override, guildId, askerId);
    }

    /** {@code {active, until}} ({@code until} an ISO time, or null), plus how long the last lift was for. */
    static DataObject state(OwnerOverride override, long guildId, long askerId) {
        long until = override.until(guildId, askerId);
        return DataObject.empty().put("active", until != 0).put("until", until == 0 ? null : Instant.ofEpochMilli(until).toString())
                .put("minutes", override.length(guildId, askerId));
    }

    /** The dashboard's actor: the member found in the server, else (only while the owner's override runs) a stand-in, else nobody. */
    static Member actorFor(Member found, boolean overriding, Supplier<Member> standIn) {
        return found == null && overriding ? standIn.get() : found;
    }

    /** The name a dashboard change is logged under: marked when made with restrictions lifted. */
    static String auditName(String name, boolean overriding) {
        return overriding ? name + OVERRIDE_MARK : name;
    }
}
