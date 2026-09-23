package com.younglings.bot.commands.signup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** In-progress {@code /signupbuilder} SUBMISSION-type build, held in memory only until finished or cancelled. */
public record SubmissionDraft(long guildId, long adminChannelId, long publicChannelId, String title,
                               Integer maxEntries, List<SubmissionField> fields, Long statusMessageId,
                               Instant lastTouchedAt) {

    /** Bumps {@link #lastTouchedAt} — called on real user activity (adding a field), not on plumbing updates. */
    public SubmissionDraft withField(SubmissionField field) {
        List<SubmissionField> updated = new ArrayList<>(fields);
        updated.add(field);
        return new SubmissionDraft(guildId, adminChannelId, publicChannelId, title, maxEntries,
                List.copyOf(updated), statusMessageId, Instant.now());
    }

    /** Records the "Submission Builder" status message's ID once known, so later steps can edit it back in place. */
    public SubmissionDraft withStatusMessageId(long messageId) {
        return new SubmissionDraft(guildId, adminChannelId, publicChannelId, title, maxEntries,
                fields, messageId, lastTouchedAt);
    }
}
