package com.younglings.bot.rsnlink;

import java.util.Optional;

/**
 * A check that can raise a link above {@link Verification#IMPLICIT} (LINK-SYSTEM.md section 12). The service runs every registered
 * verifier after a link is made and on each {@code /v1/session}; the strongest result wins and is stored with its evidence. v1 ships with
 * <b>none</b>: the candidates (the "plugin knew it first" XP check, a directed Hiscores challenge, Jagex-signed sessions) are parked in T-122.
 * <p>
 * A verifier must be quick or do its work in the background and report later; it must never call out to anything while a plugin request
 * waits. It sees only what the service already holds about the link, never a token, code or Discord id.
 */
public interface LinkVerifier {
    /** A short name stored with the evidence, e.g. {@code "xp_first_seen"}. */
    String method();

    /** A result when this check can vouch for the link now, otherwise empty. */
    Optional<VerificationResult> check(LinkContext context);

    /** What a verifier may look at. {@code report} is the plugin's own session report (null right after linking). */
    record LinkContext(String linkId, String characterId, String rsnLookup, boolean hasTrackingId, Verification current,
                       java.util.Map<String, Object> report) {}

    /**
     * A verifier's verdict.
     *
     * @param level    the level it vouches for (never {@link Verification#IMPLICIT})
     * @param evidence small JSON describing what was matched, stored in {@code link.verification_data}; no personal data beyond the link's own
     */
    record VerificationResult(Verification level, String method, String evidence) {}
}
