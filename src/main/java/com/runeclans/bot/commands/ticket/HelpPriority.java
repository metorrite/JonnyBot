package com.runeclans.bot.commands.ticket;

import com.runeclans.bot.ticket.HelpExtras;
import com.runeclans.bot.ticket.TicketModels.HelpSettings;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who gets help first: clan members, then longtime guests, then new guests, when the server switched priority tiers on ({@link HelpExtras#priorityTiers}). A tier
 * decides whether a ticket pings the helpers as it opens and how long it waits before pinging the next role up, so a member is helped soonest and a new guest last.
 * It also decides who has to show kill counts for a hard Combat Achievement tier: only new guests, and only where the server named a requirement. No Discord in
 * here, so every rule can be tested.
 */
public final class HelpPriority {
    private HelpPriority() {}

    public enum Tier { MEMBER, LONGTIME_GUEST, NEW_GUEST }

    /** A member is in the clan; any other server member is a longtime guest once they have been in the server for the configured days, and a new guest before that. */
    public static Tier tierOf(boolean member, OffsetDateTime joinedServer, Instant now, HelpExtras extras) {
        if (member) return Tier.MEMBER;
        if (joinedServer == null) return Tier.NEW_GUEST;
        return Duration.between(joinedServer.toInstant(), now).toDays() >= extras.longtimeGuestDays() ? Tier.LONGTIME_GUEST : Tier.NEW_GUEST;
    }

    /**
     * How a ticket's pings are set up for its tier. Without priority tiers it is what it always was (a member follows the member settings, any guest the guest
     * settings). With them a longtime guest has pings and escalation of their own, between the two.
     */
    public static HelpRules.Pings pingsFor(HelpSettings settings, HelpExtras extras, Tier tier) {
        if (!extras.priorityTiers() || tier == Tier.MEMBER) return HelpRules.pingsFor(settings, tier == Tier.MEMBER);
        if (tier == Tier.NEW_GUEST) return HelpRules.pingsFor(settings, false);
        int hours = extras.longtimeGuestEscalationHours();
        return new HelpRules.Pings(extras.longtimeGuestPings(), hours == 0 ? null : hours);
    }

    /**
     * Whether a guest asking for a tier has to describe their earlier attempts because the server wants kill counts for it. Only new guests, only when priority
     * tiers are on and the tier has a requirement; satisfied by tracked kills of at least the requirement, otherwise the attempts description stands in.
     */
    public static boolean needsAttemptsForKc(HelpExtras extras, Tier tier, String tierLabel, OptionalInt trackedKills) {
        if (!extras.priorityTiers() || tier != Tier.NEW_GUEST) return false;
        Integer need = extras.kcRequirement(tierLabel);
        if (need == null) return false;
        return trackedKills.isEmpty() || trackedKills.getAsInt() < need;
    }

    private static final Pattern KILLED = Pattern.compile("^I (?:killed|defeated) (\\d[\\d,]*)? ?(.+?)\\.?$");

    /** How many kills an adventure-log line reports for {@code boss} (a line without a number is one kill), or 0 when it is not a kill of that boss. */
    public static int killsIn(String text, String boss) {
        if (text == null || boss == null || boss.isBlank()) return 0;
        Matcher m = KILLED.matcher(text.strip());
        if (!m.matches() || !m.group(2).toLowerCase(java.util.Locale.ROOT).contains(boss.strip().toLowerCase(java.util.Locale.ROOT))) return 0;
        if (m.group(1) == null) return 1;
        try {
            return Integer.parseInt(m.group(1).replace(",", ""));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** The settings with only {@code tierLabel} counting as a tier whose guests must describe earlier attempts: how the kill count shortfall reuses the attempts rule. */
    static HelpSettings requiringAttemptsFor(HelpSettings s, String tierLabel) {
        return new HelpSettings(s.guildId(), s.helperRoleId(), s.helperPlusRoleId(), s.guidelines(), s.memberPingOnOpen(), s.memberEscalationHours(), s.guestPingsEnabled(),
                s.guestPingOnOpen(), s.guestEscalationHours(), true, tierLabel, s.postedChannelId(), s.postedMessageId());
    }
}
