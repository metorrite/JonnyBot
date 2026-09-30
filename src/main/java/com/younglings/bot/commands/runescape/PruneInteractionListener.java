package com.younglings.bot.commands.runescape;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Collectors;

/**
 * The Prune Messages tool on {@code /rsadmin}'s panel — one modal captures the whole spec (who,
 * how many, which channel(s)), then a confirm step (this is destructive and irreversible) before
 * anything actually gets deleted. Gated the same way as the rest of {@code /rsadmin}, via
 * {@link AdminRoleFilter}.
 * <p>
 * A separate listener from {@link RsAdminInteractionListener} purely because that class was already
 * sizeable before this — same reasoning already applied to the Tracking/Announcement/Weekly-digest
 * panels elsewhere in this codebase.
 * <p>
 * The parsed spec from the modal is kept in an in-memory map between the confirm prompt and the
 * button click that actually runs it — a customId can't hold a whole multi-user/multi-channel spec,
 * and there's nothing here worth persisting past that one confirmation.
 */
@BService
public class PruneInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PruneInteractionListener.class);

    private final AdminRoleFilter adminRoleFilter;
    private final Map<String, PruneSpec> pendingSpecs = new ConcurrentHashMap<>();
    private final ExecutorService pruneExecutor = Executors.newCachedThreadPool((ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "prune-worker");
        thread.setDaemon(true);
        return thread;
    });

    public PruneInteractionListener(AdminRoleFilter adminRoleFilter) {
        this.adminRoleFilter = adminRoleFilter;
    }

    private record PruneSpec(String who, List<Long> specificUserIds, int amount, String channelsMode,
                              List<Long> specificChannelIds, long invokingChannelId) {}

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getComponentId();
        if (guild == null || member == null || !id.startsWith("rsadmin_prune")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }

            String[] parts = id.split(":", 2);
            switch (parts[0]) {
                case "rsadmin_prune_open" -> event.replyModal(buildPruneModal()).queue();
                case "rsadmin_prune_cancel" -> {
                    pendingSpecs.remove(parts[1]);
                    Containers.edit(event, Containers.WARNING, "Prune cancelled — nothing was deleted.");
                }
                case "rsadmin_prune_confirm" -> doPruneConfirm(event, guild, parts[1]);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in prune button interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        Guild guild = event.getGuild();
        Member member = event.getMember();
        String id = event.getModalId();
        if (guild == null || member == null || !id.startsWith("rsadmin_prune")) return;

        try {
            if (!adminRoleFilter.isAuthorized(guild, member)) {
                Containers.replyEphemeral(event, Containers.WARNING, "You need the Admin role (or higher) to use this.");
                return;
            }

            if (id.equals("rsadmin_prune_modal:_")) {
                handlePruneModal(event, guild);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in prune modal interaction '{}'", id, e);
            Containers.replyError(event);
        }
    }

    private Modal buildPruneModal() {
        StringSelectMenu who = StringSelectMenu.create("prune_who")
                .setRequiredRange(1, 1)
                .addOption("Everyone", "EVERYONE")
                .addOption("Bots only", "BOTS")
                .addOption("Specific user(s) — pick below", "SPECIFIC")
                .build();

        EntitySelectMenu users = EntitySelectMenu.create("prune_users", EntitySelectMenu.SelectTarget.USER)
                .setRequiredRange(0, 25)
                .setRequired(false)
                .setPlaceholder("Only used if \"Specific user(s)\" is picked above")
                .build();

        StringSelectMenu amount = StringSelectMenu.create("prune_amount")
                .setRequiredRange(1, 1)
                .addOption("10 messages", "10")
                .addOption("25 messages", "25")
                .addOption("50 messages", "50")
                .addOption("100 messages", "100")
                .addOption("250 messages", "250")
                .addOption("500 messages", "500")
                .addOption("All matching messages", "ALL")
                .build();

        StringSelectMenu channels = StringSelectMenu.create("prune_channels")
                .setRequiredRange(1, 1)
                .addOption("This channel", "HERE")
                .addOption("All channels", "ALL")
                .addOption("Specific channel(s) — pick below", "SPECIFIC")
                .build();

        EntitySelectMenu channelPicks = EntitySelectMenu.create("prune_channel_picks", EntitySelectMenu.SelectTarget.CHANNEL)
                .setChannelTypes(ChannelType.TEXT, ChannelType.GUILD_PUBLIC_THREAD, ChannelType.GUILD_PRIVATE_THREAD)
                .setRequiredRange(0, 25)
                .setRequired(false)
                .setPlaceholder("Only used if \"Specific channel(s)\" is picked above")
                .build();

        return Modal.create("rsadmin_prune_modal:_", "Prune Messages")
                .addComponents(
                        Label.of("Who", who),
                        Label.of("Specific User(s)", users),
                        Label.of("How Many", amount),
                        Label.of("Channels", channels),
                        Label.of("Specific Channel(s)", channelPicks))
                .build();
    }

    private void handlePruneModal(ModalInteractionEvent event, Guild guild) {
        String who = event.getValue("prune_who").getAsStringList().getFirst();
        List<Long> specificUserIds = event.getValue("prune_users").getAsLongList();
        String amountRaw = event.getValue("prune_amount").getAsStringList().getFirst();
        String channelsMode = event.getValue("prune_channels").getAsStringList().getFirst();
        List<Long> specificChannelIds = event.getValue("prune_channel_picks").getAsLongList();

        if (who.equals("SPECIFIC") && specificUserIds.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "You picked \"Specific user(s)\" but didn't select anyone — pick at least one user, or choose \"Everyone\"/\"Bots only\" instead.");
            return;
        }
        if (channelsMode.equals("SPECIFIC") && specificChannelIds.isEmpty()) {
            Containers.replyEphemeral(event, Containers.WARNING,
                    "You picked \"Specific channel(s)\" but didn't select any — pick at least one, or choose \"This channel\"/\"All channels\" instead.");
            return;
        }

        int amount = amountRaw.equals("ALL") ? Integer.MAX_VALUE : Integer.parseInt(amountRaw);
        PruneSpec spec = new PruneSpec(who, specificUserIds, amount, channelsMode, specificChannelIds, event.getChannelIdLong());

        String token = UUID.randomUUID().toString().substring(0, 8);
        pendingSpecs.put(token, spec);

        event.replyComponents(List.of(buildConfirmPanel(spec, token))).useComponentsV2(true).setEphemeral(true).queue();
    }

    private Container buildConfirmPanel(PruneSpec spec, String token) {
        String whoDesc = switch (spec.who()) {
            case "EVERYONE" -> "**everyone**";
            case "BOTS" -> "**bots only**";
            default -> spec.specificUserIds().size() + " specific user(s): " +
                    spec.specificUserIds().stream().map(id -> "<@" + id + ">").collect(Collectors.joining(", "));
        };
        String amountDesc = spec.amount() == Integer.MAX_VALUE ? "**all matching messages**" : "up to **" + spec.amount() + "**";
        String channelsDesc = switch (spec.channelsMode()) {
            case "HERE" -> "<#" + spec.invokingChannelId() + ">";
            case "ALL" -> "**every text channel and thread in this server**";
            default -> spec.specificChannelIds().size() + " channel(s): " +
                    spec.specificChannelIds().stream().map(id -> "<#" + id + ">").collect(Collectors.joining(", "));
        };

        return Containers.card(Containers.DANGER,
                TextDisplay.of("### Confirm Prune"),
                TextDisplay.of("This will delete " + amountDesc + " message(s) from " + whoDesc + " in " + channelsDesc + "."),
                TextDisplay.of("-# This can't be undone. Messages older than 14 days are deleted one at a time (Discord's bulk-delete limit) and can take a while for a large channel or \"all matching messages\"."),
                ActionRow.of(
                        Button.danger("rsadmin_prune_confirm:" + token, "Yes, Prune"),
                        Button.secondary("rsadmin_prune_cancel:" + token, "Cancel")));
    }

    private void doPruneConfirm(ButtonInteractionEvent event, Guild guild, String token) {
        PruneSpec spec = pendingSpecs.remove(token);
        if (spec == null) {
            Containers.replyEphemeral(event, Containers.WARNING, "This confirmation expired — open Prune Messages again.");
            return;
        }

        event.deferEdit().queue();
        pruneExecutor.submit(() -> runPrune(event, guild, spec));
    }

    private void runPrune(ButtonInteractionEvent event, Guild guild, PruneSpec spec) {
        List<GuildMessageChannel> targetChannels = resolveChannels(guild, spec);
        int totalDeleted = 0;
        int channelsFailed = 0;

        for (GuildMessageChannel channel : targetChannels) {
            try {
                totalDeleted += pruneChannel(channel, spec);
            } catch (Exception e) {
                log.error("Failed to prune channel {} in guild {}", channel.getId(), guild.getIdLong(), e);
                channelsFailed++;
            }
        }

        String message = "**Prune complete.** Deleted " + totalDeleted + " message(s) across " + targetChannels.size() + " channel(s).";
        if (channelsFailed > 0) message += " " + channelsFailed + " channel(s) failed — check the bot's logs (likely a missing Manage Messages permission there).";

        event.getHook().editOriginalComponents(List.of(Containers.toast(
                channelsFailed == 0 ? Containers.SUCCESS : Containers.WARNING, message))).useComponentsV2(true).queue();
    }

    private List<GuildMessageChannel> resolveChannels(Guild guild, PruneSpec spec) {
        return switch (spec.channelsMode()) {
            case "HERE" -> {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, spec.invokingChannelId());
                yield channel != null ? List.of(channel) : List.of();
            }
            case "ALL" -> guild.getTextChannels().stream().map(c -> (GuildMessageChannel) c).toList();
            default -> spec.specificChannelIds().stream()
                    .map(channelId -> guild.getChannelById(GuildMessageChannel.class, channelId))
                    .filter(Objects::nonNull)
                    .toList();
        };
    }

    /** Fetches history backwards in pages of 100 until {@code spec.amount()} matching messages are found (or the channel runs out), then deletes them via {@link GuildMessageChannel#purgeMessages}, which already splits bulk-delete-eligible (under 14 days) messages from ones that need an individual delete. */
    private int pruneChannel(GuildMessageChannel channel, PruneSpec spec) {
        List<Message> matched = new ArrayList<>();
        var history = channel.getHistory();

        while (matched.size() < spec.amount()) {
            List<Message> batch = history.retrievePast(100).complete();
            if (batch.isEmpty()) break;

            for (Message message : batch) {
                if (matchesFilter(message, spec)) {
                    matched.add(message);
                    if (matched.size() >= spec.amount()) break;
                }
            }
        }

        if (matched.isEmpty()) return 0;

        List<CompletableFuture<Void>> futures = channel.purgeMessages(matched);
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return matched.size();
    }

    private static boolean matchesFilter(Message message, PruneSpec spec) {
        return switch (spec.who()) {
            case "EVERYONE" -> true;
            case "BOTS" -> message.getAuthor().isBot();
            default -> spec.specificUserIds().contains(message.getAuthor().getIdLong());
        };
    }
}
