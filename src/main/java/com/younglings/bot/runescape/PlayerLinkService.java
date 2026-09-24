package com.younglings.bot.runescape;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@BService
public class PlayerLinkService {
    private static final Logger log = LoggerFactory.getLogger(PlayerLinkService.class);

    public static final String METHOD_MAKEOVER_MAGE = "MAKEOVER_MAGE";
    public static final String METHOD_ADMIN_MANUAL = "ADMIN_MANUAL";

    private final PlayerLinkRepository repository;

    public PlayerLinkService(PlayerLinkRepository repository) {
        this.repository = repository;
    }

    /** Starts a new verification attempt with a freshly-randomized appearance to apply in-game. */
    public VerificationAttempt startVerification(long guildId, long discordUserId, String rsn) {
        MakeoverAppearance appearance = MakeoverAppearance.random();
        long attemptId = repository.createAttempt(guildId, discordUserId, rsn,
                appearance.hairstyle(), appearance.hairColor(), appearance.skinTone());
        return repository.getAttempt(attemptId);
    }

    public VerificationAttempt getAttempt(long attemptId) {
        return repository.getAttempt(attemptId);
    }

    public VerificationAttempt getPendingAttemptForUser(long guildId, long discordUserId) {
        return repository.getPendingAttemptForUser(guildId, discordUserId);
    }

    /** Lets a user abandon their own pending attempt (e.g. a mistyped RSN) without needing an admin. */
    public boolean cancelOwn(long attemptId, long discordUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status()) || attempt.discordUserId() != discordUserId) {
            return false;
        }

        repository.resolveAttempt(attemptId, "CANCELLED", discordUserId);
        return true;
    }

    public List<VerificationAttempt> getPendingAttempts(long guildId) {
        return repository.getPendingAttempts(guildId);
    }

    /** Approves the attempt and creates the confirmed link. Returns false if the attempt is gone or already resolved. */
    public boolean approve(long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status())) return false;

        repository.resolveAttempt(attemptId, "APPROVED", resolvedByUserId);
        repository.createLink(attempt.guildId(), attempt.discordUserId(), attempt.rsn(), METHOD_MAKEOVER_MAGE);
        log.info("Verification attempt {} approved by {} — linked '{}' to {}",
                attemptId, resolvedByUserId, attempt.rsn(), attempt.discordUserId());
        return true;
    }

    /** Returns false if the attempt is gone or already resolved. */
    public boolean reject(long attemptId, long resolvedByUserId) {
        VerificationAttempt attempt = repository.getAttempt(attemptId);
        if (attempt == null || !"PENDING".equals(attempt.status())) return false;

        repository.resolveAttempt(attemptId, "REJECTED", resolvedByUserId);
        return true;
    }

    /**
     * Links an RSN straight to a Discord user, bypassing the makeover-mage flow entirely — for an
     * admin who already knows a name is theirs and doesn't need the appearance-comparison dance.
     * Same underlying {@code createLink} as approving a real verification attempt, so it overwrites
     * any existing link for that RSN exactly the same way (see {@code PlayerLinkRepository#createLink}'s
     * {@code ON CONFLICT}).
     */
    public void manualLink(long guildId, long discordUserId, String rsn, long adminUserId) {
        repository.createLink(guildId, discordUserId, rsn, METHOD_ADMIN_MANUAL);
        log.info("RSN '{}' manually linked to {} by admin {}", rsn, discordUserId, adminUserId);
    }

    public List<PlayerLink> getLinksForUser(long guildId, long discordUserId) {
        return repository.getLinksForUser(guildId, discordUserId);
    }

    public PlayerLink getLinkForRsn(long guildId, String rsn) {
        return repository.getLinkForRsn(guildId, rsn);
    }

    public List<PlayerLink> getAllLinks(long guildId) {
        return repository.getAllLinks(guildId);
    }

    public List<PlayerLink> getAllLinksAcrossGuilds() {
        return repository.getAllLinksAcrossGuilds();
    }

    /** Returns false if no such link exists for that user (nothing to unlink). */
    public boolean unlink(long guildId, long discordUserId, long linkId) {
        List<PlayerLink> links = repository.getLinksForUser(guildId, discordUserId);
        boolean owns = links.stream().anyMatch(link -> link.linkId() == linkId);
        if (!owns) return false;

        repository.deleteLink(guildId, linkId);
        return true;
    }
}
