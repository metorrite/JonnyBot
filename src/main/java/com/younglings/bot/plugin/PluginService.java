package com.younglings.bot.plugin;

import com.younglings.bot.plugin.PluginPayload.Reject;
import com.younglings.bot.plugin.PluginRepository.Token;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.PlayerLinkRepository.PlayerAccount;
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
    private final SecureRandom random = new SecureRandom();

    public PluginService(PluginRepository repository, PlayerLinkRepository links, List<PluginEventSink> sinks) {
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
            return new IngestResult(0, 0, List.of(), Set.of(), "That RuneScape name isn't linked to your Discord account in JonnyBot. Link it with /rs first.");
        }
        Set<PluginCategory> consented = repository.consent(userId);
        int accepted = 0;
        int duplicates = 0;
        List<PluginPayload.Event> stored = new ArrayList<>();
        List<Reject> rejected = new ArrayList<>(batch.rejects());
        for (PluginPayload.Event event : batch.events()) {
            if (!consented.contains(event.type().category())) {
                rejected.add(new Reject(event.key(), "not_consented: the player has not allowed " + event.type().category().name()));
            } else if (repository.insertEvent(userId, account.rsn(), event, batch.client())) {
                accepted++;
                stored.add(event);
            } else {
                duplicates++;
            }
        }
        if (!stored.isEmpty()) {
            for (PluginEventSink sink : sinks) {
                try {
                    sink.accepted(userId, account.rsn(), stored);
                } catch (RuntimeException e) {
                    // what is done with the events never decides whether the upload worked, nor whether the next consumer hears of them
                    log.warn("A consumer of plugin events failed for user {}", userId, e);
                }
            }
        }
        return new IngestResult(accepted, duplicates, rejected, consented, null);
    }
}
