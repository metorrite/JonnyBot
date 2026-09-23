package com.younglings.bot.commands.coffer;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.discord.Containers;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@BService
public class CofferService {
    private static final Logger log = LoggerFactory.getLogger(CofferService.class);

    /**
     * When true, /coffer transfer creates a pending transfer that the recipient must accept before
     * balances move. When false, transfers execute immediately. Toggle here — no command changes needed.
     */
    public static final boolean REQUIRE_TRANSFER_VERIFICATION = false;

    private static final Color COLOR_GOLD   = new Color(0xFFD700);
    private static final Color COLOR_ORANGE = new Color(0xFF7A00);
    private static final Color COLOR_PURPLE = new Color(0x9B59B6);

    private final CofferRepository repository;

    public CofferService(CofferRepository repository) {
        this.repository = repository;
    }

    public void submitDonation(IReplyCallback event, String donorName, String amountStr) {
        long amount;
        try {
            amount = GpAmountParser.parse(amountStr);
        } catch (IllegalArgumentException e) {
            event.reply(e.getMessage()).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        long submittedBy = event.getUser().getIdLong();

        repository.insertDonation(guildId, donorName.trim(), amount, submittedBy);

        Container container = Containers.card(Containers.SUCCESS,
                TextDisplay.of("### Donation Logged"),
                TextDisplay.of(
                        "💰 **" + escMd(donorName.trim()) + "** donated **" + GpAmountParser.toShorthand(amount) + "**\n" +
                        "🏦 Held by " + event.getUser().getAsMention()
                ));

        event.replyComponents(List.of(container)).useComponentsV2(true).queue();
    }

    public void displayCoffer(IReplyCallback event) {
        long guildId = event.getGuild().getIdLong();
        List<CofferHolder> holders = repository.getHolders(guildId);
        long total = holders.stream().mapToLong(CofferHolder::amount).sum();

        List<TextDisplay> body = new ArrayList<>();
        body.add(TextDisplay.of("### Clan Coffer"));

        if (total == 0) {
            body.add(TextDisplay.of("The clan coffer is currently empty."));
        } else {
            body.add(TextDisplay.of("💰 **Total:** `" + GpAmountParser.format(total) + "`"));

            StringBuilder holdersText = new StringBuilder("**Holders**\n");
            for (CofferHolder holder : holders) {
                holdersText.append("<@").append(holder.discordUserId()).append("> — **")
                        .append(GpAmountParser.toShorthand(holder.amount())).append("** (`")
                        .append(String.format("%,d", holder.amount())).append(" GP`)\n");
            }
            body.add(TextDisplay.of(holdersText.toString().trim()));
        }

        Container container = Containers.card(COLOR_GOLD, body);
        event.replyComponents(List.of(container)).useComponentsV2(true).queue();
    }

    public void displayLog(IReplyCallback event) {
        long guildId = event.getGuild().getIdLong();
        List<CofferDonation> donations = repository.getRecentDonations(guildId, 25);

        String body;
        if (donations.isEmpty()) {
            body = "No donations have been logged yet.";
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < donations.size(); i++) {
                CofferDonation d = donations.get(i);
                sb.append(String.format("`#%02d`", i + 1))
                        .append(" **").append(escMd(d.donorName())).append("** donated **")
                        .append(GpAmountParser.toShorthand(d.amount())).append("**")
                        .append(" — <@").append(d.submittedByDiscordId()).append(">")
                        .append(" • <t:").append(d.submittedAt().toEpochSecond()).append(":d>\n");
            }
            body = sb.toString().trim();
        }

        Container container = Containers.card(COLOR_PURPLE, TextDisplay.of("### Recent Coffer Donations"), TextDisplay.of(body));
        event.replyComponents(List.of(container)).useComponentsV2(true).queue();
    }

    /** {@code fromUser} is who actually holds/transfers the GP — defaults to the command runner if {@code null}, so an admin can log a transfer on someone else's behalf. */
    public void transferCoffer(IReplyCallback event, @Nullable User fromUser, User toUser, String amountStr) {
        long amount;
        try {
            amount = GpAmountParser.parse(amountStr);
        } catch (IllegalArgumentException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
            return;
        }

        long guildId = event.getGuild().getIdLong();
        User actualFromUser = fromUser != null ? fromUser : event.getUser();
        long fromId = actualFromUser.getIdLong();
        long toId = toUser.getIdLong();

        if (fromId == toId) {
            Containers.replyEphemeral(event, Containers.WARNING, "You cannot transfer to yourself.");
            return;
        }

        // Note: the balance check that actually matters happens atomically inside
        // executeTransfer/insertPendingTransfer's debit — this is just an early, friendly
        // rejection so most "you don't have enough" cases don't need a round trip to find out.
        long balance = repository.getHolderBalance(guildId, fromId);
        if (balance < amount) {
            String subject = fromUser != null ? fromUser.getAsMention() + " only holds" : "You only hold";
            Containers.replyEphemeral(event, Containers.WARNING, subject + " **" + GpAmountParser.format(balance) + "** in the coffer. " +
                        "Cannot transfer **" + GpAmountParser.format(amount) + "**.");
            return;
        }

        if (REQUIRE_TRANSFER_VERIFICATION) {
            long transferId = repository.insertPendingTransfer(guildId, fromId, toId, amount);
            // TODO: send accept/reject buttons to toUser when verification is enabled
            Containers.replyEphemeral(event, Containers.INFO,
                    "Transfer of **" + GpAmountParser.format(amount) + "** to " + toUser.getAsMention() +
                        " is pending their acceptance. (Transfer ID: `" + transferId + "`)");
        } else {
            boolean success = repository.executeTransfer(guildId, fromId, toId, amount);
            if (!success) {
                // Balance changed between the check above and the atomic debit (e.g. a
                // concurrent transfer/giveaway). Reject rather than allow an overdraft.
                String subject = fromUser != null ? fromUser.getAsMention() + "'s balance" : "Your balance";
                Containers.replyEphemeral(event, Containers.WARNING, subject + " changed before this transfer could complete — please try again.");
                return;
            }

            Container container = Containers.card(COLOR_ORANGE,
                    TextDisplay.of("### Coffer Transfer"),
                    TextDisplay.of(actualFromUser.getAsMention() + " transferred **" + GpAmountParser.format(amount) +
                            "** to " + toUser.getAsMention()));

            event.replyComponents(List.of(container)).useComponentsV2(true).queue();
        }
    }

    public void recordGiveaway(IReplyCallback event, @Nullable User holderUser, User recipientUser,
                               String amountStr, @Nullable String description) {
        long amount;
        try {
            amount = GpAmountParser.parse(amountStr);
        } catch (IllegalArgumentException e) {
            Containers.replyEphemeral(event, Containers.WARNING, e.getMessage());
            return;
        }

        long guildId = event.getGuild().getIdLong();
        long givenById = holderUser != null ? holderUser.getIdLong() : event.getUser().getIdLong();
        User givenByUser = holderUser != null ? holderUser : event.getUser();

        // Early, friendly check — the atomic debit inside insertGiveaway is the real guard.
        long balance = repository.getHolderBalance(guildId, givenById);
        if (balance < amount) {
            String subject = holderUser != null ? holderUser.getAsMention() + " only holds" : "You only hold";
            Containers.replyEphemeral(event, Containers.WARNING, subject + " **" + GpAmountParser.format(balance) + "** in the coffer. " +
                        "Cannot give away **" + GpAmountParser.format(amount) + "**.");
            return;
        }

        boolean success = repository.insertGiveaway(guildId, givenById, recipientUser.getIdLong(), amount, description);
        if (!success) {
            String subject = holderUser != null ? holderUser.getAsMention() + "'s balance" : "Your balance";
            Containers.replyEphemeral(event, Containers.WARNING, subject + " changed before this giveaway could complete — please try again.");
            return;
        }

        StringBuilder desc = new StringBuilder()
                .append("🏆 **").append(GpAmountParser.toShorthand(amount))
                .append(" GP** given to ").append(recipientUser.getAsMention()).append("\n")
                .append("💼 Paid from ").append(givenByUser.getAsMention()).append("'s coffer balance");

        if (description != null && !description.isBlank()) {
            desc.append("\n📝 *").append(escMd(description.trim())).append("*");
        }

        Container container = Containers.card(COLOR_GOLD, TextDisplay.of("### Coffer Giveaway"), TextDisplay.of(desc.toString()));
        event.replyComponents(List.of(container)).useComponentsV2(true).queue();
    }

    private static String escMd(String text) {
        return text.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("~", "\\~")
                .replace("`", "\\`")
                .replace("|", "\\|");
    }
}
