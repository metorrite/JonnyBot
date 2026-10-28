package com.runeclans.bot.plugin;

import com.runeclans.bot.plugin.PluginPayload.Reject;
import com.runeclans.bot.plugin.PluginRepository.Token;
import com.runeclans.bot.runescape.PlayerLinkRepository;
import com.runeclans.bot.runescape.PlayerLinkRepository.PlayerAccount;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.runeclans.bot.config.BotBrand;

/**
 * What the plugin API does with a player's data: issue and recognise tokens, and accept a batch of events only for a RuneScape name the
 * token's owner has linked, and only in the categories the player has switched on.
 */
@BService
public class PluginService {
    private static final Logger log = LoggerFactory.getLogger(PluginService.class);
    /** A token's "last used" time is rewritten at most this often, so a busy plugin does not write on every request. */
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);

    /** Why something couldn't be done, in words for the person who asked. */
    public static final class PluginException extends RuntimeException {
        public PluginException(String message) {
            super(message);
        }
    }

    /** A newly made token: the only time its secret is ever available. */
    public record NewToken(long id, String token, String preview, String label) {}

    /** The outcome of a batch. {@code forbidden} is set when the name isn't the token owner's, in which case nothing was stored. */
    public record IngestResult(int accepted, int duplicates, List<Reject> rejected, Set<PluginCategory> consented, String forbidden) {}

    private final PluginRepository repository;
    private final PlayerLinkRepository links;
    private final List<PluginEventSink> sinks;
    private final PluginEnrichment enrichment;
    private final SecureRandom random = new SecureRandom();

    public PluginService(PluginRepository repository, PlayerLinkRepository links, List<PluginEventSink> sinks, PluginEnrichment enrichment) {
        this.enrichment = enrichment;
        this.repository = repository;
        this.links = links;
        this.sinks = List.copyOf(sinks);
    }

    // ---------- tokens ----------

    public NewToken createToken(long userId, String label) {
        if (repository.activeTokenCount(userId) >= PluginTokens.MAX_ACTIVE_PER_USER) {
            throw new PluginException("You already have " + PluginTokens.MAX_ACTIVE_PER_USER + " active tokens. Revoke one you no longer use first.");
        }
        String token = PluginTokens.generate(random);
        String cleaned = PluginTokens.cleanLabel(label);
        long id = repository.createToken(userId, PluginTokens.hash(token), PluginTokens.preview(token), cleaned);
        log.info("User {} created plugin token {}", userId, id);
        return new NewToken(id, token, PluginTokens.preview(token), cleaned);
    }

    public boolean revokeToken(long userId, long tokenId) {
        boolean revoked = repository.revoke(userId, tokenId);
        if (revoked) log.info("User {} revoked plugin token {}", userId, tokenId);
        return revoked;
    }

    /** The token behind the secret the plugin presented, if it is ours and not revoked. */
    public Optional<Token> authenticate(String presented) {
        if (!PluginTokens.looksLikeToken(presented)) return Optional.empty();
        Optional<Token> token = repository.activeByHash(PluginTokens.hash(presented));
        token.filter(t -> t.lastUsedAt() == null || t.lastUsedAt().isBefore(Instant.now().minus(TOUCH_EVERY))).ifPresent(t -> repository.touch(t.id()));
        return token;
    }

    // ---------- a player's own view ----------

    public List<Token> tokens(long userId) {
        return repository.tokens(userId);
    }

    public Set<PluginCategory> consent(long userId) {
        return repository.consent(userId);
    }

    public void setConsent(long userId, Set<PluginCategory> categories) {
        repository.setConsent(userId, categories);
    }

    public int storedEvents(long userId) {
        return repository.eventCount(userId);
    }

    /** The RuneScape names this player has linked in JonnyBot: the ones the plugin may report for. */
    public List<String> reportableNames(long userId) {
        return links.getAccountsForUser(userId).stream().map(PlayerAccount::rsn).toList();
    }

    /** Removes everything the plugin reported for the player, their tokens and their choices. */
    public int deleteAllData(long userId) {
        int events = repository.deleteAllFor(userId);
        log.info("User {} deleted their plugin data ({} events)", userId, events);
        return events;
    }

    // ---------- ingest ----------

    public IngestResult ingest(long userId, PluginPayload.Parsed batch) {
        PlayerAccount account = links.getAccountForRsn(batch.rsn());
        if (account == null || account.discordUserId() != userId) {
            return new IngestResult(0, 0, List.of(), Set.of(), "That RuneScape name isn't linked to your Discord account in " + BotBrand.NAME + ". Link it with /rs first.");
        }
        return store(userId, account.rsn(), batch);
    }

    /**
     * A batch signed through RuneLink (RUNESYNC-DESIGN.md section 3): the link says this Discord account owns {@code linkedRsn}, which counts
     * for ingest only (whether it also makes a JonnyBot player link is T-121's call). The batch's name must be that name, compared the
     * way the game compares names; otherwise it is refused like the token path's "not your name".
     */
    public IngestResult ingestVerified(long userId, String linkedRsn, PluginPayload.Parsed batch) {
        if (linkedRsn == null || !sameName(linkedRsn, batch.rsn())) {
            return new IngestResult(0, 0, List.of(), Set.of(), "That RuneScape name isn't the one linked through RuneLink. Report only for the character you are logged in as.");
        }
        return store(userId, batch.rsn(), batch);
    }

    /** Whether two RuneScape names are the same name: case, and space, _ and - count as equal (as in the game). */
    static boolean sameName(String a, String b) {
        String x = PluginPayload.normaliseRsn(a);
        String y = PluginPayload.normaliseRsn(b);
        return x != null && y != null && lookup(x).equals(lookup(y));
    }

    private static String lookup(String rsn) {
        return rsn.toLowerCase(java.util.Locale.ROOT).replace('_', ' ').replace('-', ' ');
    }

    private IngestResult store(long userId, String rsn, PluginPayload.Parsed batch) {
        Set<PluginCategory> consented = repository.consent(userId);
        int accepted = 0;
        int duplicates = 0;
        List<PluginPayload.Event> stored = new ArrayList<>();
        List<Reject> rejected = new ArrayList<>(batch.rejects());
        PluginEnrichment.Budget priceLookups = enrichment.budget();
        for (PluginPayload.Event original : batch.events()) {
            PluginPayload.Event event = original;
            if (!consented.contains(event.type().category())) {
                rejected.add(new Reject(event.key(), "not_consented: the player has not allowed " + event.type().category().name()));
            } else if (repository.insertEvent(userId, rsn, event = enrichment.enrich(event, priceLookups), batch.client())) {
                accepted++;
                stored.add(event);
            } else {
                duplicates++;
            }
        }
        if (!stored.isEmpty()) {
            for (PluginEventSink sink : sinks) {
                try {
                    sink.accepted(userId, rsn, stored);
                } catch (RuntimeException e) {
                    // what is done with the events never decides whether the upload worked, nor whether the next consumer hears of them
                    log.warn("A consumer of plugin events failed for user {}", userId, e);
                }
            }
        }
        return new IngestResult(accepted, duplicates, rejected, consented, null);
    }
}
