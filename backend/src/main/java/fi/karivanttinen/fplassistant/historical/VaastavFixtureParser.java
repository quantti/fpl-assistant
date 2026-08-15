package fi.karivanttinen.fplassistant.historical;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Parses vaastav {@code fixtures.csv} content into {@link VaastavFixtureRow}s.
 *
 * <p>Two things verified against the real files before writing this: the column order changes
 * between seasons (2019-20 puts {@code stats} before the team columns, 2025-26 after — this
 * parses by header name, never by position), and the {@code stats} column is a quoted Python-dict
 * blob containing hundreds of commas and single quotes that a hand-rolled {@code split(",")}
 * would corrupt every row on — hence the RFC 4180 reader.
 */
public final class VaastavFixtureParser {

    private VaastavFixtureParser() {
    }

    /** Every row in the file, finished or not — the caller decides what to keep. */
    public static List<VaastavFixtureRow> parse(String csvContent, String season) {
        List<VaastavFixtureRow> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(csvContent))) {
            for (CSVRecord record : parser) {
                rows.add(parseRow(record, season));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(season + ": failed to parse fixtures.csv", e);
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException(season + ": fixtures.csv parsed to zero rows");
        }
        return rows;
    }

    private static VaastavFixtureRow parseRow(CSVRecord record, String season) {
        boolean finished = Boolean.parseBoolean(required(record, "finished", season));
        int teamH = parseInt(required(record, "team_h", season), "team_h", record, season);
        int teamA = parseInt(required(record, "team_a", season), "team_a", record, season);
        return new VaastavFixtureRow(
                finished,
                teamH,
                teamA,
                nullableInt(record, "team_h_score"),
                nullableInt(record, "team_a_score"),
                nullableInt(record, "event"),
                nullableInt(record, "team_h_difficulty"),
                nullableInt(record, "team_a_difficulty"),
                optional(record, "kickoff_time"));
    }

    private static String required(CSVRecord record, String column, String season) {
        String value = optional(record, column);
        if (value == null) {
            throw new IllegalStateException(season + ": fixtures.csv row " + record.getRecordNumber()
                    + " is missing required column '" + column + "'");
        }
        return value;
    }

    private static String optional(CSVRecord record, String column) {
        if (!record.isSet(column)) {
            return null;
        }
        String value = record.get(column);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Integer nullableInt(CSVRecord record, String column) {
        String value = optional(record, column);
        return value == null ? null : parseInt(value, column, record, null);
    }

    private static int parseInt(String value, String column, CSVRecord record, String season) {
        try {
            // vaastav sometimes writes whole-number floats ("1.0") in numeric columns.
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException((season != null ? season + ": " : "") + "fixtures.csv row "
                    + record.getRecordNumber() + " column '" + column + "' is not a number: '" + value + "'", e);
        }
    }
}
