package dev.quantti.fplassistant.tools;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import dev.quantti.fplassistant.sync.GameweekFinality;
import dev.quantti.fplassistant.sync.GameweekFinality.ComponentCoverage;
import dev.quantti.fplassistant.sync.GameweekFinalityReader;

/** Scoped wording for incremental rows; a gameweek receipt never labels legacy or other-season rows. */
public final class GameweekFinalityFormatter {

    private GameweekFinalityFormatter() {
    }

    public static String summary(String seasonCode,
                                 Map<Integer, PfsReadSource.IncrementalRows> rowsByGameweek,
                                 Map<Integer, GameweekFinalityReader.Result> finalityByGameweek,
                                 Map<Integer, Set<Integer>> fixtureIdsByGameweek) {
        List<String> lines = rowsByGameweek.entrySet().stream()
                .filter(entry -> entry.getValue().incrementalRows() > 0)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> line(entry.getKey(), entry.getValue(),
                        finalityByGameweek.get(entry.getKey()),
                        fixtureIdsByGameweek.getOrDefault(entry.getKey(), Set.of())))
                .toList();
        if (lines.isEmpty()) return "";
        return "Gameweek finality for season " + seasonCode
                + " metric rows in this returned fixture window (per gameweek; not a season-wide claim):\n"
                + String.join("\n", lines);
    }

    private static String line(int gameweek, PfsReadSource.IncrementalRows rows,
                              GameweekFinalityReader.Result result, Set<Integer> fixtureIds) {
        String fixtures = fixtureIds.isEmpty() ? "fixture IDs unavailable"
                : "fixtures " + fixtureIds.stream().sorted().map(String::valueOf)
                        .collect(Collectors.joining(", "));
        String prefix = "  GW" + gameweek + " " + fixtures + ": ";
        String status;
        if (result != null && result.state() == GameweekFinality.State.FINALIZED) {
            GameweekFinality.FinalReceipt receipt = result.receipt();
            status = "FPL checked; final import completed at " + exact(receipt.completedAt())
                    + " (source reads " + exact(receipt.sourceReadInterval().startedAt()) + " to "
                    + exact(receipt.sourceReadInterval().finishedAt()) + ").";
        } else if (result != null && result.state() == GameweekFinality.State.FINAL_PENDING) {
            status = "FPL checked; final import pending/incomplete"
                    + (result.firstCheckedAt() == null ? "." : " (first observed checked at "
                        + exact(result.firstCheckedAt()) + ").");
        } else if (result != null && result.dataChecked()) {
            status = result.eventFinished()
                    ? "FPL marked the event checked/finished, but not all assigned fixtures are finished;"
                        + " final import is not eligible."
                    : "FPL data_checked=true, but the event is not finished; final import is not eligible.";
        } else {
            status = "provisional incremental rows; FPL has not supplied a checked-gameweek final import.";
        }
        String count = " Incremental rows in the shown fixture window: " + rows.incrementalRows() + ".";
        if (rows.otherRows() > 0) {
            count += " " + rows.otherRows() + " other/non-incremental row(s) are outside this finality receipt.";
        }
        String coverage = result != null && result.receipt() != null
                ? " " + optionalCoverage(result.receipt().optionalCoverage()) : "";
        return prefix + status + count + coverage;
    }

    private static String optionalCoverage(Map<dev.quantti.fplassistant.sync.HistoryParser.Component,
            ComponentCoverage> coverage) {
        String counts = coverage.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey().name().toLowerCase(Locale.ROOT) + " "
                        + coverage(entry.getValue()))
                .collect(Collectors.joining("; "));
        return "Final-import optional-component presence (counts, not metric values): " + counts + ".";
    }

    private static String coverage(ComponentCoverage coverage) {
        Objects.requireNonNull(coverage);
        if (!coverage.supported()) return "unsupported";
        if (coverage.sourceRows() == 0) return "no source rows";
        return coverage.presentRows() + "/" + coverage.sourceRows() + " present";
    }

    private static String exact(Instant instant) {
        return instant.toString();
    }
}
