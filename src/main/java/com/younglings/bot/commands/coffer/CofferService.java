package com.younglings.bot.commands.coffer;

import com.younglings.bot.coffer.CofferRepository;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class CofferService {
    private static final Logger log = LoggerFactory.getLogger(CofferService.class);

    /**
     * When true, /coffertransfer creates a pending transfer that the recipient must accept before
     * balances move. When false, transfers execute immediately. Toggle here — no command changes needed.
     */
    public static final boolean REQUIRE_TRANSFER_VERIFICATION = false;

    private static final int COLOR_GREEN  = 0x57F287;
    private static final int COLOR_GOLD   = 0xFFD700;
    private static final int COLOR_ORANGE = 0xFF7A00;
    private static final int COLOR_PURPLE = 0x9B59B6;

    private final CofferRepository repository;

    public CofferService(CofferRepository repository) {
        this.repository = repository;
    }

    public void submitDonation(GuildSlashEvent event, String donorName, String amountStr) {
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

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Donation Logged")
                .setColor(COLOR_GREEN)
                .setDescription(
                        "💰 **" + escMd(donorName.trim()) + "** donated **" + GpAmountParser.toShorthand(amount) + "**\n" +
                        "🏦 Held by " + event.getUser().getAsMention()
                );

        event.replyEmbeds(embed.build()).queue();
    }

    public void displayCoffer(GuildSlashEvent event) {
        long guildId = event.getGuild().getIdLong();
        List<CofferHolder> holders = repository.getHolders(guildId);
        long total = holders.stream().mapToLong(CofferHolder::amount).sum();

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Clan Coffer")
                .setColor(COLOR_GOLD);

        if (total == 0) {
            embed.setDescription("The clan coffer is currently empty.");
        } else {
            embed.setDescription("💰 **Total:** `" + GpAmountParser.format(total) + "`");

            StringBuilder holdersText = new StringBuilder();
            for (CofferHolder holder : holders) {
                holdersText.append("<@").append(holder.discordUserId()).append("> — **")
                        .append(GpAmountParser.toShorthand(holder.amount())).append("** (`")
                        .append(String.format("%,d", holder.amount())).append(" GP`)\n");
            }
            embed.addField("Holders", holdersText.toString().trim(), false);
        }

        event.replyEmbeds(embed.build()).queue();
    }

    public void displayLog(GuildSlashEvent event) {
        long guildId = event.getGuild().getIdLong();
        List<CofferDonation> donations = repository.getRecentDonations(guildId, 25);

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Recent Coffer Donations")
                .setColor(COLOR_PURPLE);

        if (donations.isEmpty()) {
            embed.setDescription("No donations have been logged yet.");
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
            embed.setDescription(sb.toString().trim());
        }

        event.replyEmbeds(embed.build()).queue();
    }

    public void transferCoffer(GuildSlashEvent event, User toUser, String amountStr) {
        long amount;
        try {
            amount = GpAmountParser.parse(amountStr);
        } catch (IllegalArgumentException e) {
            event.reply(e.getMessage()).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        long fromId = event.getUser().getIdLong();
        long toId = toUser.getIdLong();

        if (fromId == toId) {
            event.reply("You cannot transfer to yourself.").setEphemeral(true).queue();
            return;
        }

        long balance = repository.getHolderBalance(guildId, fromId);
        if (balance < amount) {
            event.reply("You only hold **" + GpAmountParser.format(balance) + "** in the coffer. " +
                        "Cannot transfer **" + GpAmountParser.format(amount) + "**.")
                    .setEphemeral(true).queue();
            return;
        }

        if (REQUIRE_TRANSFER_VERIFICATION) {
            long transferId = repository.insertPendingTransfer(guildId, fromId, toId, amount);
            // TODO: send accept/reject buttons to toUser when verification is enabled
            event.reply("Transfer of **" + GpAmountParser.format(amount) + "** to " + toUser.getAsMention() +
                        " is pending their acceptance. (Transfer ID: `" + transferId + "`)")
                    .setEphemeral(true).queue();
        } else {
            repository.executeTransfer(guildId, fromId, toId, amount);

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle("Coffer Transfer")
                    .setColor(COLOR_ORANGE)
                    .setDescription(
                            event.getUser().getAsMention() + " transferred **" + GpAmountParser.format(amount) +
                            "** to " + toUser.getAsMention()
                    );

            event.replyEmbeds(embed.build()).queue();
        }
    }

    public void recordGiveaway(GuildSlashEvent event, @Nullable User holderUser, User recipientUser,
                               String amountStr, @Nullable String description) {
        long amount;
        try {
            amount = GpAmountParser.parse(amountStr);
        } catch (IllegalArgumentException e) {
            event.reply(e.getMessage()).setEphemeral(true).queue();
            return;
        }

        long guildId = event.getGuild().getIdLong();
        long givenById = holderUser != null ? holderUser.getIdLong() : event.getUser().getIdLong();
        User givenByUser = holderUser != null ? holderUser : event.getUser();

        long balance = repository.getHolderBalance(guildId, givenById);
        if (balance < amount) {
            String subject = holderUser != null ? holderUser.getAsMention() + " only holds" : "You only hold";
            event.reply(subject + " **" + GpAmountParser.format(balance) + "** in the coffer. " +
                        "Cannot give away **" + GpAmountParser.format(amount) + "**.")
                    .setEphemeral(true).queue();
            return;
        }

        repository.insertGiveaway(guildId, givenById, recipientUser.getIdLong(), amount, description);

        StringBuilder desc = new StringBuilder()
                .append("🏆 **").append(GpAmountParser.toShorthand(amount))
                .append(" GP** given to ").append(recipientUser.getAsMention()).append("\n")
                .append("💼 Paid from ").append(givenByUser.getAsMention()).append("'s coffer balance");

        if (description != null && !description.isBlank()) {
            desc.append("\n📝 *").append(escMd(description.trim())).append("*");
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle("Coffer Giveaway")
                .setColor(COLOR_GOLD)
                .setDescription(desc.toString());

        event.replyEmbeds(embed.build()).queue();
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
