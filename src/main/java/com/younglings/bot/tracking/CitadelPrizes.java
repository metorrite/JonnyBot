package com.younglings.bot.tracking;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.commands.coffer.CofferHolder;
import com.younglings.bot.commands.coffer.GpAmountParser;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.tracking.CitadelWinners.CannotLock;
import com.younglings.bot.wheel.WheelWinnerRepository;
import com.younglings.bot.wheel.WheelWinnerRepository.Prize;
import com.younglings.bot.wheel.WheelWinnerRepository.PrizeStatus;
import com.younglings.bot.wheel.WheelWinnerRepository.Winner;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * What happens to a Citadel wheel winner's prize: marked as given (handed over some other way), or recorded in the clan coffer, which moves the gp
 * out of the balance of whoever paid it into a giveaway for the winner, exactly like {@code /coffer giveaway}. A prize recorded in the coffer can't be
 * recorded twice and can't be undone from here, because the money has moved.
 */
@BService
public class CitadelPrizes {
    private static final Logger log = LoggerFactory.getLogger(CitadelPrizes.class);
    public static final int MAX_NOTE = 200;

    private final WheelWinnerRepository repository;
    private final CofferRepository coffer;
    private final PlayerLinkRepository links;

    public CitadelPrizes(WheelWinnerRepository repository, CofferRepository coffer, PlayerLinkRepository links) {
        this.repository = repository;
        this.coffer = coffer;
        this.links = links;
    }

    /** Who holds gp in the clan coffer right now, the most first. Only people holding something are listed. */
    public List<CofferHolder> holders(long guildId) {
        return coffer.getHolders(guildId).stream().filter(h -> h.amount() > 0).sorted(Comparator.comparingLong(CofferHolder::amount).reversed()).toList();
    }

    public long balanceOf(long guildId, long discordUserId) {
        return coffer.getHolderBalance(guildId, discordUserId);
    }

    /** The Discord member a winner's RuneScape account is linked to (/rs), or null when it isn't linked. */
    public Long linkedUser(Guild guild, String rsn) {
        try {
            for (var link : links.getAllLinks(guild.getIdLong())) {
                if (link.rsn().equalsIgnoreCase(rsn)) return link.discordUserId();
            }
        } catch (RuntimeException e) {
            log.debug("Couldn't look up the winner's Discord account", e);
        }
        return null;
    }

    private static String clean(String note) {
        String text = note == null ? "" : note.strip();
        if (text.length() > MAX_NOTE) throw new CannotLock(400, "Keep the note to " + MAX_NOTE + " characters or fewer.");
        return text.isEmpty() ? null : text;
    }

    private Winner require(long guildId, LocalDate weekEnd, int place) {
        return repository.get(guildId, weekEnd, place).orElseThrow(() -> new CannotLock(404, "That winner isn't locked."));
    }

    /** Marks a prize as handed over without touching the coffer (it was paid some other way). {@code amount} may be null. */
    public Winner markGiven(long guildId, LocalDate weekEnd, int place, Long amount, String note, long actorId) {
        Winner row = require(guildId, weekEnd, place);
        if (row.prize().status() == PrizeStatus.GIVEN) throw new CannotLock(409, "This prize is already marked as given.");
        if (amount != null && amount <= 0) throw new CannotLock(400, "The amount has to be more than zero.");
        Prize prize = new Prize(PrizeStatus.GIVEN, amount, null, null, actorId, Instant.now(), clean(note), false);
        if (!repository.setPrize(guildId, weekEnd, place, prize, true)) throw new CannotLock(409, "This prize was just marked as given by someone else.");
        log.info("Prize for place {} of the week ending {} in guild {} marked as given by {}", place, weekEnd, guildId, actorId);
        return require(guildId, weekEnd, place);
    }

    /** Puts a prize back to "not given" after it was marked by hand. A prize recorded in the coffer stays given. */
    public Winner markPending(long guildId, LocalDate weekEnd, int place) {
        Winner row = require(guildId, weekEnd, place);
        if (row.prize().status() == PrizeStatus.PENDING) return row;
        if (row.prize().inCoffer()) throw new CannotLock(409, "This prize was recorded in the coffer, so the gp has already moved. It can't be set back to not given.");
        repository.setPrize(guildId, weekEnd, place, Prize.pending(), false);
        return require(guildId, weekEnd, place);
    }

    /**
     * Records the prize in the clan coffer: {@code paidBy} (a Discord user holding gp in the coffer) gives {@code amountText} to the winner, who is
     * {@code recipientOverride} when one is chosen, otherwise the member their RuneScape name is linked to. Checks everything first and writes nothing
     * if any of it is wrong.
     */
    public Winner recordInCoffer(Guild guild, LocalDate weekEnd, int place, String amountText, long paidBy, Long recipientOverride, String note, long actorId) {
        long guildId = guild.getIdLong();
        Winner row = require(guildId, weekEnd, place);
        if (row.prize().status() == PrizeStatus.GIVEN) throw new CannotLock(409, "This prize has already been recorded as given.");

        long amount;
        try {
            amount = GpAmountParser.parse(amountText);
        } catch (IllegalArgumentException e) {
            throw new CannotLock(400, e.getMessage());
        }
        Long recipient = recipientOverride != null ? recipientOverride : linkedUser(guild, row.winner());
        if (recipient == null) throw new CannotLock(400, row.winner() + " isn't linked to a Discord account. Link their RuneScape name with /rs, or choose their Discord account.");
        if (recipient == paidBy) throw new CannotLock(400, "The person who paid can't also be the winner.");
        long balance = coffer.getHolderBalance(guildId, paidBy);
        if (balance < amount) throw new CannotLock(409, "That person only holds " + GpAmountParser.format(balance) + " in the coffer, so they can't have paid " + GpAmountParser.format(amount) + ".");

        String text = clean(note);
        Prize prize = new Prize(PrizeStatus.GIVEN, amount, paidBy, recipient, actorId, Instant.now(), text, true);
        // claim the prize first, so two people recording it at once can't both pay it
        if (!repository.setPrize(guildId, weekEnd, place, prize, true)) throw new CannotLock(409, "This prize was just recorded by someone else.");
        String description = "Citadel wheel prize, place " + place + " (" + weekEnd.minusDays(7) + " to " + weekEnd + "): " + row.winner() + (text == null ? "" : " - " + text);
        boolean paid;
        try {
            paid = coffer.insertGiveaway(guildId, paidBy, recipient, amount, description);
        } catch (RuntimeException e) {
            repository.setPrize(guildId, weekEnd, place, Prize.pending(), false);
            throw e;
        }
        if (!paid) {
            repository.setPrize(guildId, weekEnd, place, Prize.pending(), false);
            throw new CannotLock(409, "That person's coffer balance changed just now, so nothing was recorded. Try again.");
        }
        log.info("Recorded a Citadel wheel prize of {} gp for place {} of the week ending {} in guild {} (paid by {}, to {}, recorded by {})", amount, place, weekEnd, guildId, paidBy, recipient, actorId);
        return require(guildId, weekEnd, place);
    }
}
