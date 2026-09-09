package fi.karivanttinen.fplassistant.squad;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import fi.karivanttinen.fplassistant.tools.DailyPriceSource;
import fi.karivanttinen.fplassistant.tools.MetricSeasons;
import fi.karivanttinen.fplassistant.tools.RankingMetrics;
import org.springframework.stereotype.Component;

/**
 * Applies the squad purchase policy to the same coherent ranking snapshot used by pick tools.
 * Identity comes from the whole daily capture; metrics and minutes membership come from
 * {@link RankingMetrics}, so the two consumers cannot disagree about gaps or club history.
 */
@Component
public class CandidatePool {

    private final DailyPriceSource daily;
    private final RankingMetrics rankingMetrics;

    public CandidatePool(DailyPriceSource daily, RankingMetrics rankingMetrics) {
        this.daily = daily;
        this.rankingMetrics = rankingMetrics;
    }

    public record Capture(String season, LocalDate date) {
    }

    public record Pool(List<SquadCandidate> candidates, RankingMetrics.Snapshot snapshot) {
        public Pool {
            candidates = List.copyOf(candidates);
        }
    }

    public Optional<Capture> latestCapture() {
        return daily.latestCapture().map(capture -> new Capture(capture.season(), capture.date()));
    }

    /**
     * Fit players remain purchasable even without a rankable record; doubtful players require an
     * underlying record. Every other availability state is excluded from the purchasable pool.
     */
    public Pool load(Capture capture, MetricSeasons metric) {
        if (capture == null) {
            throw new IllegalArgumentException("capture is required");
        }
        RankingMetrics.Snapshot snapshot = rankingMetrics.load(
                new DailyPriceSource.Capture(capture.season(), capture.date()), metric);
        List<SquadCandidate> candidates = new ArrayList<>();
        for (RankingMetrics.Candidate row : snapshot.candidates()) {
            String status = row.capture().status();
            if (!"a".equals(status) && !"d".equals(status)) {
                continue;
            }
            if (row.blended() == null && !"a".equals(status)) {
                continue;
            }
            candidates.add(toCandidate(row));
        }
        return new Pool(candidates, snapshot);
    }

    private static SquadCandidate toCandidate(RankingMetrics.Candidate row) {
        RankingMetrics.CapturePlayer player = row.capture();
        boolean rankable = row.blended() != null;
        Map<String, Object> metrics = new HashMap<>();
        if (rankable) {
            metrics.putAll(row.blended().metrics());
            metrics.put("element_type", player.position());
        }
        return new SquadCandidate(
                player.elementId(), player.webName(), player.fullName(), player.teamName(),
                player.teamShort(), player.position() == null ? 0 : player.position(),
                player.nowCost(), player.ownership() == null ? 0 : player.ownership(),
                player.status(), player.news(), player.teamId() == null ? 0 : player.teamId(),
                rankable && row.blended().exposure() != null
                        ? row.blended().exposure().expectedMinutes() : 0,
                metrics, rankable ? SquadCandidate.Tier.RANKABLE : SquadCandidate.Tier.BODY,
                row.current() == null ? null : row.current().window(),
                rankable ? row.blended().exposure() : null, row.eligibility());
    }
}
