package fi.karivanttinen.fplassistant.historical;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Resolves a vaastav {@code fixtures.csv} row's season-scoped {@code team_h`/`team_a} integer
 * into a persistent FPL team {@link TeamIdentity} (code + season name), from each season's
 * {@code teams.csv}.
 *
 * <p>This mapping is {@code (season, vaastav team id) → code}, not a name lookup: vaastav's
 * {@code fixtures.csv} carries numeric ids, and {@code teams.csv} supplies the {@code code} for
 * them directly. Every season from 2019-20 onward has a {@code teams.csv} — the backfill range
 * was chosen so this class never needs a name-based fallback. If it ever throws "no teams.csv
 * loaded for season", the season range has been widened past what this class supports.
 */
public final class VaastavTeamMapper {

    private final Map<String, Map<Integer, TeamIdentity>> bySeasonThenVaastavId;

    private VaastavTeamMapper(Map<String, Map<Integer, TeamIdentity>> bySeasonThenVaastavId) {
        this.bySeasonThenVaastavId = bySeasonThenVaastavId;
    }

    /**
     * @param teamsCsvBySeason each season's raw {@code teams.csv} content, keyed by season code
     *                         (e.g. {@code "2019-20"})
     */
    public static VaastavTeamMapper from(Map<String, String> teamsCsvBySeason) {
        Map<String, Map<Integer, TeamIdentity>> result = new LinkedHashMap<>();
        teamsCsvBySeason.forEach((season, csv) -> result.put(season, parseSeason(csv, season)));
        return new VaastavTeamMapper(result);
    }

    /**
     * @throws IllegalStateException naming the season and id when either is unresolvable —
     *         ingestion must fail loudly rather than drop the match or invent a code
     */
    public TeamIdentity resolve(String season, int vaastavTeamId) {
        Map<Integer, TeamIdentity> teams = bySeasonThenVaastavId.get(season);
        if (teams == null) {
            throw new IllegalStateException(
                    "No teams.csv loaded for season " + season + " — cannot resolve team id " + vaastavTeamId);
        }
        TeamIdentity identity = teams.get(vaastavTeamId);
        if (identity == null) {
            throw new IllegalStateException(
                    season + ": unknown vaastav team id " + vaastavTeamId
                            + " — not present in that season's teams.csv");
        }
        return identity;
    }

    /** Pure parsing, unit-testable without a database or network access. */
    static Map<Integer, TeamIdentity> parseSeason(String csvContent, String season) {
        Map<Integer, TeamIdentity> result = new LinkedHashMap<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(csvContent))) {
            for (CSVRecord record : parser) {
                int id = parseRequiredInt(record, "id", season);
                int code = parseRequiredInt(record, "code", season);
                String name = requiredField(record, "name", season);
                TeamIdentity previous = result.put(id, new TeamIdentity(code, name));
                if (previous != null) {
                    throw new IllegalStateException(
                            season + ": duplicate vaastav team id " + id + " in teams.csv");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(season + ": failed to parse teams.csv", e);
        }
        if (result.isEmpty()) {
            throw new IllegalStateException(season + ": teams.csv parsed to zero rows");
        }
        return result;
    }

    private static String requiredField(CSVRecord record, String column, String season) {
        String value = record.isSet(column) ? record.get(column) : null;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(season + ": teams.csv row " + record.getRecordNumber()
                    + " is missing required column '" + column + "'");
        }
        return value.trim();
    }

    private static int parseRequiredInt(CSVRecord record, String column, String season) {
        String value = requiredField(record, column, season);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(season + ": teams.csv row " + record.getRecordNumber()
                    + " column '" + column + "' is not an integer: '" + value + "'", e);
        }
    }
}
