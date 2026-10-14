package com.younglings.bot.tracking;

import com.younglings.bot.discord.Containers;
import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.wheel.WheelEligibility;
import com.younglings.bot.wheel.WheelEntries;
import com.younglings.bot.wheel.WheelSpin;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
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
    private static final int REVEAL_AFTER_SECONDS = 6;

    private final CitadelWheelEntries wheelEntries;
    private final AdminRoleFilter adminRoleFilter;
    private Executor worker = Executors.newFixedThreadPool(2, (ThreadFactory) runnable -> {
        Thread thread = new Thread(runnable, "citadel-wheel");
        thread.setDaemon(true);
        return thread;
    });

    /** For tests: run the follow-up work on the calling thread. */
    void useExecutor(Executor executor) {
        this.worker = executor;
    }

    public WeeklyDigestInteractionListener(CitadelWheelEntries wheelEntries, AdminRoleFilter adminRoleFilter) {
        this.wheelEntries = wheelEntries;
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
                    Containers.replyEphemeral(event, Containers.WARNING, "Only staff can spin the wheel. Use **Copy names** if you want the list.");
                    return;
                }
                TextInput extras = TextInput.create("extras", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Axley\nBerserker87 x2\n(one name per line; x2 gives two entries)")
                        .setRequired(false)
                        .setMaxLength(1500)
                        .build();
                Modal modal = Modal.create(MODAL_PREFIX + id.substring(SPIN_PREFIX.length()), "Spin the wheel")
                        .addComponents(Label.of("Extra entries (optional)", extras))
                        .build();
                event.replyModal(modal).queue();
            } else if (id.startsWith(COPY_PREFIX)) {
                copyNames(event, guild, windowOf(id, COPY_PREFIX));
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
                    event.getHook().editOriginal("Nobody is on the wheel for that week" + (eligible.leftOut().isEmpty() ? " (nobody capped)." : " (everyone who capped is staff).")).queue();
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
                event.getHook().editOriginal("**On the wheel for that week (" + capped.size() + ")**, one per person, to paste into a wheel website:\n```\n"
                        + list.toString().stripTrailing() + "\n```" + more + notes(eligible)).queue();
            } catch (Exception e) {
                log.error("Could not build the Copy names list", e);
                event.getHook().editOriginal("I couldn't get the list just now. Try again in a moment.").queue();
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
                Containers.replyEphemeral(event, Containers.WARNING, "Only staff can spin the wheel.");
                return;
            }
            OffsetDateTime[] window = windowOf(id, MODAL_PREFIX);
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
                        spin(event, entries, capped.size(), extras, notes(eligible));
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

    private void spin(ModalInteractionEvent event, List<String> entries, int cappedCount, WheelEntries.Parsed extras, String notes) {
        try {
            List<String> drawn = WheelEntries.spread(entries);
            WheelSpin.Result result = WheelSpin.spin(drawn, drawn.size() > 40 ? 640 : 560);
            String summary = cappedCount + " capped" + (extras.names().isEmpty() ? "" : " + " + extras.names().size() + " extra " + (extras.names().size() == 1 ? "entry" : "entries"))
                    + " · " + drawn.size() + " on the wheel";
            String skipped = (extras.problems().isEmpty() ? "" : "\n-# Skipped: " + String.join(" ", extras.problems())) + notes;
            event.getHook().editOriginal("🎡 Spinning the wheel... (" + summary + ")" + skipped)
                    .setAttachments(FileUpload.fromData(result.gif(), "citadel-wheel.gif")).queue();
            // the winner is named once the wheel has stopped, not before the animation has finished
            String reveal = "🎡 The wheel landed on **" + result.winner() + "**! (" + summary + ")" + skipped;
            worker.execute(() -> {
                try {
                    TimeUnit.SECONDS.sleep(REVEAL_AFTER_SECONDS);
                    event.getHook().editOriginal(reveal).queue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            log.error("Could not draw the citadel wheel", e);
            event.getHook().editOriginal("I couldn't draw the wheel just now. Try again in a moment.").queue();
        }
    }
}
