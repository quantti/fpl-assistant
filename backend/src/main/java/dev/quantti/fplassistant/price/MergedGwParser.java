package dev.quantti.fplassistant.price;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Parses vaastav {@code gws/merged_gw.csv} content into {@link MergedGwRow}s. The file has
 * dozens of columns; only {@code element}, {@code round} (the gameweek), {@code value} (the
 * price in 0.1m units) and {@code name} (web name, kept as a fallback when the tapas
 * {@code player} join has no row for a departed player) are read, by header name.
 *
 * <p>Uses the same RFC 4180 reader as {@code VaastavFixtureParser}: a hand-rolled split would
 * corrupt rows whose fields contain commas.
 */
public final class MergedGwParser {

    private MergedGwParser() {
    }

    public static List<MergedGwRow> parse(String csvContent, String season) {
        List<MergedGwRow> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(csvContent))) {
            for (CSVRecord record : parser) {
                rows.add(parseRow(record, season));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(season + ": failed to parse merged_gw.csv", e);
        }
        return rows;
    }

    private static MergedGwRow parseRow(CSVRecord record, String season) {
        int element = parseInt(required(record, "element", season), "element", record, season);
        int gameweek = parseInt(required(record, "round", season), "round", record, season);
        int price = parseInt(required(record, "value", season), "value", record, season);
        String name = optional(record, "name");
        return new MergedGwRow(element, gameweek, price, name);
    }

    private static String required(CSVRecord record, String column, String season) {
        String value = optional(record, column);
        if (value == null) {
            throw new IllegalStateException(season + ": merged_gw.csv row " + record.getRecordNumber()
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

    private static int parseInt(String value, String column, CSVRecord record, String season) {
        try {
            double parsed = Double.parseDouble(value);
            if (parsed != Math.rint(parsed)) {
                throw new IllegalStateException(season + ": merged_gw.csv row " + record.getRecordNumber()
                        + " column '" + column + "' has a non-integer value: '" + value + "'");
            }
            return (int) parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(season + ": merged_gw.csv row " + record.getRecordNumber()
                    + " column '" + column + "' is not a number: '" + value + "'", e);
        }
    }
}
