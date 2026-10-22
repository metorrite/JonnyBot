package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.wheel.WheelEligibility;
import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelSpin;
import com.younglings.bot.wheel.WheelWinnerRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The Weekly Citadel Report's two buttons.
 * <ul>
 *   <li><b>Spin Wheel</b> (staff): asks for any extra entries (a name per line, {@code Name x2} for more than one: extra entries given as a prize or to
 *   make up for a missed cap), then posts an animated wheel with everyone who capped plus the extras, which turns and stops on the winner.</li>
 *   <li><b>Copy names</b>: everyone who capped, one per line in a code block, to paste into another wheel website.</li>
 * </ul>
 * Both rebuild the report's week from the button's id (the date the week ended), so there is one definition of the week ({@link CitadelWeek}).
 */
@BService
public class WeeklyDigestInteractionListener extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(WeeklyDigestInteractionListener.class);
    private static final String SPIN_PREFIX = "weekly_spin_wheel:";
    private static final String MODAL_PREFIX = "weekly_spin_modal:";
    private static final String COPY_PREFIX = "weekly_copy_names:";
    private static final String POST_PREFIX = "weekly_post_winner:";
    private static final String LOCK_PREFIX = "weekly_lock_winner:";
    private static final int REVEAL_AFTER_SECONDS = 6;
    /** The wheel is drawn this many pixels across; a GIF that would be too big for Discord is redrawn smaller automatically. */
    static final int WHEEL_SIZE = 720;

    private final CitadelWheelEntries wheelEntries;
    private final CitadelWheelPoster poster;
    private final AdminRoleFilter adminRoleFilter;
    private final CitadelWinners winners;
    /** The wheel each recent spin was made from, by the "Lock in winner" button's id, so locking keeps the entries (a restart forgets them: the winner is then locked alone). */
    private final java.util.Map<String, List<String>> spunWheels = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, List<String>> eldest) {
            return size() > 100;
        }
    });
    /** Spin messages whose winner has already been announced, so a second click doesn't post it twice. */
    private final java.util.Set<Long> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private Executor worker = Executors.newFixedThreadPool(2, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "citadel-wheel");
        thread.setDaemon(true);
        return thread;
    });

    /** For tests: run the follow-up work on the calling thread. */
    void useExecutor(Executor executor) {
        this.worker = executor;
    }

    public WeeklyDigestInteractionListener(CitadelWheelEntries wheelEntries, CitadelWheelPoster poster, AdminRoleFilter adminRoleFilter, CitadelWinners winners) {
        this.winners = winners;
        this.wheelEntries = wheelEntries;
        this.poster = poster;
        this.adminRoleFilter = adminRoleFilter;
    }

    private boolean isStaff(Guild guild, Member member) {
        return member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
    }

    private static OffsetDateTime[] windowOf(String id, String prefix) {
        // the id only carries the date the week ended: the report and its buttons are always built from the same Citadel week
        return CitadelWeek.windowEndingOn(LocalDate.parse(id.substring(prefix.length())));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        Guild guild = event.getGuild();
        if (guild == null) return;
        try {
            if (id.startsWith(SPIN_PREFIX)) {
                if (!isStaff(guild, event.getMember())) {
                    Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.only_staff_can_spin_the_wheel"));
                    return;
                }
                TextInput extras = TextInput.create("extras", TextInputStyle.PARAGRAPH)
                        .setPlaceholder(Text.of(event, "tracking.axley_berserker87_x2_one_name_per"))
                        .setRequired(false)
                        .setMaxLength(1500)
                        .build();
                Modal modal = Modal.create(MODAL_PREFIX + id.substring(SPIN_PREFIX.length()), "Spin the wheel")
                        .addComponents(Label.of("Extra entries (optional)", extras))
                        .build();
                event.replyModal(modal).queue();
            } else if (id.startsWith(COPY_PREFIX)) {
                copyNames(event, guild, windowOf(id, COPY_PREFIX));
            } else if (id.startsWith(POST_PREFIX)) {
                postWinner(event, guild, id);
            } else if (id.startsWith(LOCK_PREFIX)) {
                lockWinner(event, guild, id);
            }
        } catch (Exception e) {
            log.error("Unhandled exception in weekly digest button '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /**
     * Working out who is on the wheel reads the database and looks up Discord members, which can take longer than the three seconds Discord gives a
     * button to be answered. So the click is acknowledged straight away and the list is filled in afterwards.
     */
    private void copyNames(ButtonInteractionEvent event, Guild guild, OffsetDateTime[] window) {
        event.deferReply(true).queue();
        worker.execute(() -> {
            try {
                WheelEligibility.Result eligible = wheelEntries.forWeek(guild, window[0], window[1]);
                List<String> capped = eligible.names();
                if (capped.isEmpty()) {
                    event.getHook().editOriginal(Text.of(event, eligible.leftOut().isEmpty() ? "tracking.nobody_on_wheel_nobody_capped" : "tracking.nobody_on_wheel_all_staff")).queue();
                    return;
                }
                StringBuilder list = new StringBuilder();
                int shown = 0;
                for (String name : capped) {
                    if (list.length() + name.length() + 1 > 1700) break;
                    list.append(name).append('\n');
                    shown++;
                }
                String more = shown < capped.size() ? "\n-# " + (capped.size() - shown) + " more didn't fit." : "";
                event.getHook().editOriginal(Text.of(event, "tracking.on_the_wheel_for_that_week", capped.size(), list.toString().stripTrailing(), more, notes(eligible))).queue();
            } catch (Exception e) {
                log.error("Could not build the Copy names list", e);
                event.getHook().editOriginal(Text.of(event, "tracking.i_couldn_t_get_the_list")).queue();
            }
        });
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        String id = event.getModalId();
        if (!id.startsWith(MODAL_PREFIX)) return;
        Guild guild = event.getGuild();
        if (guild == null) return;
        try {
            if (!isStaff(guild, event.getMember())) {
                Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.only_staff_can_spin_the_wheel_2"));
                return;
            }
            OffsetDateTime[] window = windowOf(id, MODAL_PREFIX);
            String dateKey = id.substring(MODAL_PREFIX.length());
            var extrasValue = event.getValue("extras");
            WheelEntries.Parsed extras = WheelEntries.parseExtras(extrasValue == null ? "" : extrasValue.getAsString());

            // acknowledge first: finding who is on the wheel can take longer than the three seconds Discord allows
            event.deferReply().queue();
            worker.execute(() -> {
                try {
                    WheelEligibility.Result eligible = wheelEntries.forWeek(guild, window[0], window[1]);
                    List<String> capped = eligible.names();
                    List<String> entries = new ArrayList<>(capped);
                    entries.addAll(extras.names());
                    if (entries.isEmpty()) {
                        refuse(event, "Nobody is on the wheel for that week" + (eligible.leftOut().isEmpty() ? "" : " (everyone who capped is staff)") + " and no extra entries were given, so there's nothing to spin.");
                    } else if (entries.size() > WheelEntries.MAX_ENTRIES) {
                        refuse(event, "That's " + entries.size() + " entries; the wheel holds up to " + WheelEntries.MAX_ENTRIES + ".");
                    } else {
                        spin(event, entries, capped.size(), extras, notes(eligible), dateKey);
                    }
                } catch (Exception e) {
                    log.error("Could not set up the citadel wheel", e);
                    refuse(event, "I couldn't set up the wheel just now. Try again in a moment.");
                }
            });
        } catch (Exception e) {
            log.error("Unhandled exception in weekly digest spin modal '{}'", id, e);
            Containers.replyError(event);
        }
    }

    /** The id of the "Post winner" button: the week, how many entries were on the wheel, and the winner (encoded, since a name can hold any character). */
    static String postId(String dateKey, int entryCount, String winner) {
        return buttonId(POST_PREFIX, dateKey, entryCount, winner);
    }

    /** The id of the "Lock in winner" button: the same parts as {@link #postId}. */
    static String lockId(String dateKey, int entryCount, String winner) {
        return buttonId(LOCK_PREFIX, dateKey, entryCount, winner);
    }

    private static String buttonId(String prefix, String dateKey, int entryCount, String winner) {
        String id = prefix + dateKey + ":" + entryCount + ":" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(winner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return id.length() <= 100 ? id : null;
    }

    /** Announces the spin's winner in the configured channel, with the configured message and the GIF that was spun. Staff only, once per spin. */
    private void postWinner(ButtonInteractionEvent event, Guild guild, String id) {
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.only_staff_can_post_the_winner"));
            return;
        }
        String[] parts = id.substring(POST_PREFIX.length()).split(":", 3);
        if (parts.length != 3) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.that_button_is_out_of_date"));
            return;
        }
        OffsetDateTime[] window = CitadelWeek.windowEndingOn(LocalDate.parse(parts[0]));
        int entryCount = Integer.parseInt(parts[1]);
        String winner = new String(java.util.Base64.getUrlDecoder().decode(parts[2]), java.nio.charset.StandardCharsets.UTF_8);
        long messageId = event.getMessageIdLong();
        if (!announced.add(messageId)) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "tracking.this_winner_has_already_been_posted"));
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> {
            try {
                var attachments = event.getMessage().getAttachments();
                byte[] gif = attachments.isEmpty() ? null : attachments.get(0).getProxy().download().get().readAllBytes();
                var channel = poster.postWinner(guild, window, winner, entryCount, gif);
                event.getHook().editOriginal(Text.of(event, "tracking.posted_in", winner, channel.getAsMention())).queue();
            } catch (CitadelWheelPoster.CannotPost e) {
                announced.remove(messageId);
                event.getHook().editOriginal(e.getMessage()).queue();
            } catch (Exception e) {
                announced.remove(messageId);
                log.error("Could not post the Citadel wheel winner", e);
                event.getHook().editOriginal(Text.of(event, "tracking.i_couldn_t_post_that_just")).queue();
            }
        });
    }

    /**
     * Locks the spin's winner in as the week's winner (staff only), with the wheel it was spun from and the picture that was spun. The same rules as the
     * dashboard's lock: a week that already has a winner is never added to or replaced here (more places and unlocking are done on the dashboard).
     */
    private void lockWinner(ButtonInteractionEvent event, Guild guild, String id) {
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.only_staff_can_lock_in_the"));
            return;
        }
        String[] parts = id.substring(LOCK_PREFIX.length()).split(":", 3);
        if (parts.length != 3) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "tracking.that_button_is_out_of_date"));
            return;
        }
        LocalDate weekEnd = LocalDate.parse(parts[0]);
        String winner = new String(java.util.Base64.getUrlDecoder().decode(parts[2]), java.nio.charset.StandardCharsets.UTF_8);
        event.deferReply(true).queue();
        worker.execute(() -> {
            try {
                var already = winners.forWeek(guild.getIdLong(), weekEnd);
                if (!already.isEmpty()) {
                    event.getHook().editOriginal(Text.of(event, "tracking.that_week_already_has_a_locked", already.get(0).winner())).queue();
                    return;
                }
                var attachments = event.getMessage().getAttachments();
                byte[] gif = null;
                try {
                    gif = attachments.isEmpty() ? null : attachments.get(0).getProxy().download().get().readAllBytes();
                } catch (Exception e) {
                    log.warn("Could not fetch the spun wheel picture to lock; the bot will draw its own", e);
                }
                List<String> wheel = spunWheels.get(id);
                winners.lock(guild.getIdLong(), weekEnd, winner, wheel, WheelWinnerRepository.Method.SPUN, event.getUser().getIdLong(), gif, WheelWinnerRepository.GifSource.BOT);
                event.getHook().editOriginal(Text.of(event, "tracking.locked_in_as_the_winner_of", winner, weekEnd)).queue();
            } catch (CitadelWinners.CannotLock e) {
                event.getHook().editOriginal(e.getMessage()).queue();
            } catch (Exception e) {
                log.error("Could not lock the Citadel wheel winner", e);
                event.getHook().editOriginal(Text.of(event, "tracking.i_couldn_t_lock_that_just")).queue();
            }
        });
    }

    /** The spin was already acknowledged in public, so take that message down and tell only the person who asked. */
    private static void refuse(ModalInteractionEvent event, String message) {
        event.getHook().deleteOriginal().queue(ok -> event.getHook().sendMessage(message).setEphemeral(true).queue(), error -> event.getHook().sendMessage(message).setEphemeral(true).queue());
    }

    /** A line or two saying who was left off the wheel and why, and who was merged into one entry, so nobody wonders. */
    static String notes(WheelEligibility.Result eligible) {
        StringBuilder out = new StringBuilder();
        if (!eligible.leftOut().isEmpty()) {
            out.append("\n-# Left off (staff): ");
            int shown = 0;
            for (var left : eligible.leftOut()) {
                if (shown == 6) break;
                if (shown++ > 0) out.append(", ");
                out.append(left.name()).append(" (").append(left.reason()).append(')');
            }
            if (eligible.leftOut().size() > shown) out.append(" and ").append(eligible.leftOut().size() - shown).append(" more");
        }
        if (!eligible.merged().isEmpty()) {
            out.append("\n-# One entry per person: ");
            out.append(String.join(", ", eligible.merged().stream().map(m -> String.join(" + ", m.accounts())).limit(6).toList()));
        }
        return out.toString();
    }

    private void spin(ModalInteractionEvent event, List<String> entries, int cappedCount, WheelEntries.Parsed extras, String notes, String dateKey) {
        try {
            List<String> drawn = WheelEntries.spread(entries);
            WheelSpin.Result result = WheelSpin.spin(drawn, WHEEL_SIZE);
            String summary = cappedCount + " capped" + (extras.names().isEmpty() ? "" : " + " + extras.names().size() + " extra " + (extras.names().size() == 1 ? "entry" : "entries"))
                    + " · " + drawn.size() + " on the wheel";
            String skipped = (extras.problems().isEmpty() ? "" : "\n-# Skipped: " + String.join(" ", extras.problems())) + notes;
            event.getHook().editOriginal(Text.of(event, "tracking.spinning_the_wheel", summary, skipped))
                    .setAttachments(FileUpload.fromData(result.gif(), "citadel-wheel.gif")).queue();
            // the winner is named once the wheel has stopped, not before the animation has finished
            String reveal = "🎡 The wheel landed on **" + result.winner() + "**! (" + summary + ")" + skipped;
            worker.execute(() -> {
                try {
                    TimeUnit.SECONDS.sleep(REVEAL_AFTER_SECONDS);
                    String postId = postId(dateKey, drawn.size(), result.winner());
                    String lockId = lockId(dateKey, drawn.size(), result.winner());
                    if (lockId != null) spunWheels.put(lockId, List.copyOf(drawn));
                    var edit = event.getHook().editOriginal(reveal);
                    if (postId != null && lockId != null) edit = edit.setComponents(ActionRow.of(Button.success(postId, Text.of(event, "tracking.post_winner")), Button.secondary(lockId, Text.of(event, "tracking.lock_in_winner"))));
                    else if (postId != null) edit = edit.setComponents(ActionRow.of(Button.success(postId, Text.of(event, "tracking.post_winner"))));
                    edit.queue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.error("Could not draw the citadel wheel", e);
            event.getHook().editOriginal(Text.of(event, "tracking.i_couldn_t_draw_the_wheel")).queue();
        }
    }
}
