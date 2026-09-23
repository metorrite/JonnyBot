package com.younglings.bot.commands.signup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-progress {@code /signupbuilder} SUBMISSION-type build, held in memory only until finished or cancelled. */
public record SubmissionDraft(long guildId, long adminChannelId, long publicChannelId, String title,
                               Integer maxEntries, List<SubmissionField> fields, Instant lastTouchedAt) {

    /** Bumps {@link #lastTouchedAt} — called on real user activity (adding a field). */
    public SubmissionDraft withField(SubmissionField field) {
        List<SubmissionField> updated = new ArrayList<>(fields);
        updated.add(field);
        return new SubmissionDraft(guildId, adminChannelId, publicChannelId, title, maxEntries,
                List.copyOf(updated), Instant.now());
    }
}
