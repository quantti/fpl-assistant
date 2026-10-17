package dev.quantti.fplassistant.sync;

import java.util.Collection;
import java.util.Objects;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.CanonicalFixture;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.OwnedSyncStore.Lease;

/** Explicit, inert boundary for strict per-player history parsing and incremental row publication. */
public final class IncrementalPlayerFixturePublisher {

    public static final String PAGE_RECEIPT_PREFIX = "incremental-page:player:";

    private final IncrementalPfsRepository repository;

    public IncrementalPlayerFixturePublisher(IncrementalPfsRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /**
     * Parses the entire source page before opening the writer transaction. Invalid pages therefore
     * cannot publish a valid prefix; each caller page remains independent from other players.
     */
    public IncrementalPfsRepository.PublicationResult publish(Lease lease, Season season, int playerId,
                                                               SourceDocument page,
                                                               Collection<CanonicalFixture> fixtures) {
        HistoryBatch validated = HistoryParser.parse(season, playerId, page, fixtures);
        return repository.publishValidated(lease, validated);
    }

    public static String pageReceiptKey(int playerId) {
        if (playerId <= 0) throw new IllegalArgumentException("playerId must be positive");
        return PAGE_RECEIPT_PREFIX + playerId;
    }
}
