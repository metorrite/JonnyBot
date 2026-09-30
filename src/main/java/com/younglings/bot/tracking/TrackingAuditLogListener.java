package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.audit.AuditLogEntry;
import net.dv8tion.jda.api.events.guild.GuildAuditLogEntryCreateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

/**
 * Source 4 of the approved taxonomy — Discord's own admin log, live, as it happens. Needs the
 * {@code GUILD_MODERATION} gateway intent and the bot to have the "View Audit Log" permission in the
 * guild; without either, this event simply never fires (no error, nothing to catch) — see
 * {@link com.younglings.bot.Bot#getIntents()}.
 */
@BService
public class TrackingAuditLogListener extends ListenerAdapter {
    private final TrackingEventClassifier classifier;
    private final TrackingEventRouter router;

    public TrackingAuditLogListener(TrackingEventClassifier classifier, TrackingEventRouter router) {
        this.classifier = classifier;
        this.router = router;
    }

    @Override
    public void onGuildAuditLogEntryCreate(GuildAuditLogEntryCreateEvent event) {
        AuditLogEntry logEntry = event.getEntry();
        String actorMention = "<@" + logEntry.getUserIdLong() + ">";

        classifier.classify(logEntry, actorMention)
                .ifPresent(classified -> router.dispatchContainer(event.getGuild(), classified.group(), classified.container()));
    }
}
