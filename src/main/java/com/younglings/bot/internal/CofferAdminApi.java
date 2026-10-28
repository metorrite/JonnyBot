package com.younglings.bot.internal;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.discord.DiscordLookups;
import com.younglings.bot.commands.coffer.CofferHolder;
import com.younglings.bot.commands.coffer.GpAmountParser;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.tracking.CitadelPrizes;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The dashboard's Clan coffer page, the website side of {@code /coffer}: the same data and the same three things an admin can do in Discord.
 * <ul>
 *   <li>{@code GET coffer}: the total, who holds what, and the latest donations, giveaways and transfers.</li>
 *   <li>{@code POST coffer/donation} {@code {donor, amount, heldBy?}}: logs a donation; whoever holds the gp (the asker unless {@code heldBy}) goes up.</li>
 *   <li>{@code POST coffer/giveaway} {@code {from?, recipient? | recipientRsn?, amount, description?}}: gp paid out of a holder's balance to a member,
 *   chosen by Discord id or by their linked RuneScape name.</li>
 *   <li>{@code POST coffer/transfer} {@code {from?, to, amount}}: gp handed from one holder to another.</li>
 * </ul>
 * Reached only through {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server (the Admin tier or the
 * Developer role, the same people {@code /coffer}'s buttons allow); event coordinators never reach it.
 */
@BService
public class CofferAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(CofferAdminApi.class);
    static final int RECENT = 50;
    static final int MAX_NAME = 64;
    static final int MAX_DESCRIPTION = 200;

    private final CofferRepository coffer;
    private final CitadelPrizes prizes;

    public CofferAdminApi(CofferRepository coffer, CitadelPrizes prizes) {
        this.coffer = coffer;
        this.prizes = prizes;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length < 1 || parts.length > 2 || !parts[0].equals("coffer")) return null;
        if (parts.length == 1) {
            AdminRouteExtension.requireMethod(method, "GET");
            return overview(guild);
        }
        return switch (parts[1]) {
            case "donation" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield donation(guild, actor, body.get());
            }
            case "giveaway" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield giveaway(guild, actor, body.get());
            }
            case "transfer" -> {
                AdminRouteExtension.requireMethod(method, "POST");
                yield transfer(guild, actor, body.get());
            }
            default -> null;
        };
    }

    DataObject overview(Guild guild) {
        long guildId = guild.getIdLong();
        List<CofferHolder> holders = coffer.getHolders(guildId);
        var donations = coffer.getRecentDonations(guildId, RECENT);
        var giveaways = coffer.getRecentGiveaways(guildId, RECENT);
        var transfers = coffer.getRecentTransfers(guildId, RECENT);

        Set<Long> people = new LinkedHashSet<>();
        holders.forEach(h -> people.add(h.discordUserId()));
        donations.forEach(d -> people.add(d.submittedByDiscordId()));
        giveaways.forEach(g -> {
            people.add(g.givenByDiscordId());
            people.add(g.recipientDiscordId());
        });
        transfers.forEach(t -> {
            people.add(t.fromDiscordId());
            people.add(t.toDiscordId());
        });
        Map<Long, String> names = CitadelAdminApi.memberNames(guild, people);

        DataArray holderRows = DataArray.empty();
        holders.forEach(h -> holderRows.add(DataObject.empty().put("userId", Long.toString(h.discordUserId())).put("name", names.get(h.discordUserId()))
                .put("balance", h.amount())));
        DataArray donationRows = DataArray.empty();
        donations.forEach(d -> donationRows.add(DataObject.empty().put("donor", d.donorName()).put("amount", d.amount())
                .put("heldBy", Long.toString(d.submittedByDiscordId())).put("heldByName", names.get(d.submittedByDiscordId()))
                .put("at", d.submittedAt() == null ? null : d.submittedAt().toInstant().toString())));
        DataArray giveawayRows = DataArray.empty();
        giveaways.forEach(g -> giveawayRows.add(DataObject.empty().put("from", Long.toString(g.givenByDiscordId())).put("fromName", names.get(g.givenByDiscordId()))
                .put("to", Long.toString(g.recipientDiscordId())).put("toName", names.get(g.recipientDiscordId())).put("amount", g.amount())
                .put("description", g.description()).put("at", g.givenAt() == null ? null : g.givenAt().toInstant().toString())));
        DataArray transferRows = DataArray.empty();
        transfers.forEach(t -> transferRows.add(DataObject.empty().put("from", Long.toString(t.fromDiscordId())).put("fromName", names.get(t.fromDiscordId()))
                .put("to", Long.toString(t.toDiscordId())).put("toName", names.get(t.toDiscordId())).put("amount", t.amount())
                .put("at", t.createdAt() == null ? null : t.createdAt().toInstant().toString())));

        long total = holders.stream().mapToLong(CofferHolder::amount).sum();
        return DataObject.empty().put("total", total).put("holders", holderRows).put("donations", donationRows).put("giveaways", giveawayRows)
                .put("transfers", transferRows);
    }

    private DataObject donation(Guild guild, Member actor, DataObject body) {
        String donor = body.getString("donor", "").strip();
        if (donor.isEmpty()) throw new ApiError(400, "Say who donated.");
        if (donor.length() > MAX_NAME) throw new ApiError(400, "Keep the donor's name to " + MAX_NAME + " characters or fewer.");
        long amount = amountOf(body);
        Long heldBy = optionalId(body, "heldBy");
        long holder = heldBy == null ? actor.getIdLong() : heldBy;
        if (heldBy != null) requireMember(guild, holder, "The person holding the gp isn't in this server.");
        coffer.insertDonation(guild.getIdLong(), donor, amount, holder);
        log.info("Dashboard: {} logged a coffer donation of {} gp from {} (held by {})", actor.getId(), amount, donor, holder);
        return overview(guild);
    }

    private DataObject giveaway(Guild guild, Member actor, DataObject body) {
        long amount = amountOf(body);
        Long from = optionalId(body, "from");
        long giver = from == null ? actor.getIdLong() : from;
        Long recipient = optionalId(body, "recipient");
        String rsn = body.getString("recipientRsn", "").strip();
        if (recipient == null && !rsn.isEmpty()) {
            recipient = prizes.linkedUser(guild, rsn);
            if (recipient == null) throw new ApiError(400, rsn + " isn't linked to a Discord account. Link their RuneScape name with /rs, or give their Discord user id.");
        }
        if (recipient == null) throw new ApiError(400, "Say who the giveaway went to.");
        if (recipient == giver) throw new ApiError(400, "The person paying can't also be the one receiving it.");
        requireMember(guild, recipient, "The person receiving it isn't in this server.");
        String description = body.getString("description", "").strip();
        if (description.length() > MAX_DESCRIPTION) throw new ApiError(400, "Keep the description to " + MAX_DESCRIPTION + " characters or fewer.");
        long balance = coffer.getHolderBalance(guild.getIdLong(), giver);
        if (balance < amount) throw new ApiError(409, "That person only holds " + GpAmountParser.format(balance) + " in the coffer, so they can't give away " + GpAmountParser.format(amount) + ".");
        if (!coffer.insertGiveaway(guild.getIdLong(), giver, recipient, amount, description.isEmpty() ? null : description)) {
            throw new ApiError(409, "That person's coffer balance changed just now, so nothing was recorded. Try again.");
        }
        log.info("Dashboard: {} recorded a coffer giveaway of {} gp from {} to {}", actor.getId(), amount, giver, recipient);
        return overview(guild);
    }

    private DataObject transfer(Guild guild, Member actor, DataObject body) {
        long amount = amountOf(body);
        Long from = optionalId(body, "from");
        long giver = from == null ? actor.getIdLong() : from;
        Long to = optionalId(body, "to");
        if (to == null) throw new ApiError(400, "Say who the gp went to.");
        if (to == giver) throw new ApiError(400, "Choose two different people.");
        requireMember(guild, to, "The person receiving the gp isn't in this server.");
        long balance = coffer.getHolderBalance(guild.getIdLong(), giver);
        if (balance < amount) throw new ApiError(409, "That person only holds " + GpAmountParser.format(balance) + " in the coffer, so they can't hand over " + GpAmountParser.format(amount) + ".");
        if (!coffer.executeTransfer(guild.getIdLong(), giver, to, amount)) {
            throw new ApiError(409, "That person's coffer balance changed just now, so nothing was moved. Try again.");
        }
        log.info("Dashboard: {} recorded a coffer transfer of {} gp from {} to {}", actor.getId(), amount, giver, to);
        return overview(guild);
    }

    private static long amountOf(DataObject body) {
        String raw = body.isNull("amount") ? "" : body.getString("amount", "").strip();
        if (raw.length() > 30) throw new ApiError(400, "That isn't an amount. Use 5m, 500k or 5000000.");
        try {
            return GpAmountParser.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    private static Long optionalId(DataObject body, String key) {
        if (!body.hasKey(key) || body.isNull(key)) return null;
        String raw = body.getString(key, "").strip();
        if (raw.isEmpty()) return null;
        if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "That isn't a Discord user id.");
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a Discord user id.");
        }
    }

    /**
     * Refuses someone who isn't in the server (the cache only holds who is online, so they are looked up, waiting at most
     * {@link DiscordLookups#BUDGET}). When Discord doesn't answer in time this throws {@link DiscordLookups.Slow} (a 503), never a wrong "not in this server".
     */
    private static void requireMember(Guild guild, long userId, String problem) {
        if (guild.getMemberById(userId) != null) return;
        if (DiscordLookups.memberOrSlow(guild, userId) == null) throw new ApiError(400, problem);
    }
}
