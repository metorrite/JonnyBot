package com.younglings.bot.commands.coffer;

import com.younglings.bot.coffer.CofferRepository;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.discord.Pagination;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.components.ComponentInteraction;
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

    public void displayCoffer(ComponentInteraction event, int pageIndex, boolean isPageNav) {
        long guildId = event.getGuild().getIdLong();
        List<CofferHolder> holders = repository.getHolders(guildId);
        long total = holders.stream().mapToLong(CofferHolder::amount).sum();

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Clan Coffer"));

        if (total == 0) {
            children.add(TextDisplay.of("The clan coffer is currently empty."));
        } else {
            children.add(TextDisplay.of("💰 **Total:** `" + GpAmountParser.format(total) + "`"));

            var page = Pagination.paginate(holders, pageIndex);
            StringBuilder holdersText = new StringBuilder("**Holders** (" + holders.size() + ")\n");
            for (CofferHolder holder : page.items()) {
                holdersText.append("<@").append(holder.discordUserId()).append("> — **")
                        .append(GpAmountParser.toShorthand(holder.amount())).append("** (`")
                        .append(String.format("%,d", holder.amount())).append(" GP`)\n");
            }
            children.add(TextDisplay.of(holdersText.toString().trim()));

            if (!page.isSinglePage()) {
                children.add(Pagination.navRow(page, "coffer_hub_display_page:"));
            }
        }

        replyOrEdit(event, Containers.card(COLOR_GOLD, children), isPageNav);
    }

    private static final int LOG_FETCH_LIMIT = 500;

    public void displayLog(ComponentInteraction event, int pageIndex, boolean isPageNav) {
        long guildId = event.getGuild().getIdLong();
        List<CofferDonation> donations = repository.getRecentDonations(guildId, LOG_FETCH_LIMIT);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### Recent Coffer Donations (" + donations.size() + ")"));

        if (donations.isEmpty()) {
            children.add(TextDisplay.of("No donations have been logged yet."));
        } else {
            var page = Pagination.paginate(donations, pageIndex);
            StringBuilder sb = new StringBuilder();
            int position = page.pageIndex() * Pagination.DEFAULT_PAGE_SIZE + 1;
            for (CofferDonation d : page.items()) {
                sb.append(String.format("`#%02d`", position++))
                        .append(" **").append(escMd(d.donorName())).append("** donated **")
                        .append(GpAmountParser.toShorthand(d.amount())).append("**")
                        .append(" — <@").append(d.submittedByDiscordId()).append(">")
                        .append(" • <t:").append(d.submittedAt().toEpochSecond()).append(":d>\n");
            }
            children.add(TextDisplay.of(sb.toString().trim()));

            if (!page.isSinglePage()) {
                children.add(Pagination.navRow(page, "coffer_hub_log_page:"));
            }
        }

        replyOrEdit(event, Containers.card(COLOR_PURPLE, children), isPageNav);
    }

    private void replyOrEdit(ComponentInteraction event, Container container, boolean isPageNav) {
        if (isPageNav) {
            event.editComponents(List.of(container)).useComponentsV2(true).queue();
        } else {
            event.replyComponents(List.of(container)).useComponentsV2(true).queue();
        }
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
