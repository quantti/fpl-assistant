package fi.karivanttinen.fplassistant.sync;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Measures projected checkpoint bodies at the JdbcTemplate row-mapper boundary. */
class PayloadReadProbe extends JdbcTemplate {
    private static final Pattern FROM = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_BODY_PROJECTION = Pattern.compile(
            "(?:^|,)\\s*(?:[a-z_][a-z0-9_$]*\\s*\\.\\s*)?body\\s*"
                    + "(?:as\\s+([a-z_][a-z0-9_$]*))?\\s*(?=,|$)", Pattern.CASE_INSENSITIVE);

    private final Map<Family, Counters> counters = new EnumMap<>(Family.class);

    PayloadReadProbe(DataSource dataSource) {
        super(dataSource);
        for (Family family : Family.values()) counters.put(family, new Counters());
    }

    @Override
    public <T> java.util.List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        Projection projection = projection(sql);
        if (projection == null) return super.query(sql, rowMapper, args);

        Counters selected = counters.get(projection.family());
        selected.attempts.increment();
        return super.query(sql, (resultSet, rowNumber) -> {
            selected.rows.increment();
            observeBodies(resultSet, projection.labels(), selected);
            return rowMapper.mapRow(resultSet, rowNumber);
        }, args);
    }

    Snapshot snapshot() {
        Map<Family, FamilyMetrics> result = new EnumMap<>(Family.class);
        counters.forEach((family, value) -> result.put(family,
                new FamilyMetrics(value.attempts.sum(), value.rows.sum(), value.bodyValues.sum(),
                        value.rawBytes.sum())));
        return new Snapshot(Map.copyOf(result));
    }

    void reset() {
        counters.values().forEach(Counters::reset);
    }

    private static Projection projection(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int select = lower.indexOf("select");
        if (select < 0) return null;
        Matcher from = FROM.matcher(lower);
        if (!from.find(select + "select".length())) return null;
        String selectList = lower.substring(select + "select".length(), from.start());
        Matcher columns = RAW_BODY_PROJECTION.matcher(selectList);
        Set<String> labels = new HashSet<>();
        while (columns.find()) {
            String alias = columns.group(1);
            labels.add(alias == null ? "body" : alias.toLowerCase(Locale.ROOT));
        }
        if (labels.isEmpty()) return null;

        Family family;
        if (labels.contains("b_body") && labels.contains("f_body")) {
            family = Family.CANONICAL_PAIR;
        } else if (lower.contains("p.id = ?") && lower.contains("join")) {
            family = Family.GUARDED_SINGLE;
        } else if (!lower.contains("join") && lower.contains("where run_generation")) {
            family = Family.OLD_OWN_CHECKPOINT;
        } else {
            family = Family.OTHER_BODY_SELECT;
        }
        return new Projection(family, Set.copyOf(labels));
    }

    private static void observeBodies(ResultSet resultSet, Set<String> labels, Counters counters)
            throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column).toLowerCase(Locale.ROOT);
            if (!labels.contains(label)) continue;
            byte[] body = resultSet.getBytes(column);
            if (body != null) {
                counters.bodyValues.increment();
                counters.rawBytes.add(body.length);
            }
        }
    }

    enum Family {
        OLD_OWN_CHECKPOINT,
        GUARDED_SINGLE,
        CANONICAL_PAIR,
        OTHER_BODY_SELECT
    }

    record FamilyMetrics(long attempts, long resultRows, long bodyValues, long rawBytes) {
        String counts() {
            return attempts + "/" + resultRows + "/" + bodyValues + "/" + rawBytes;
        }
    }

    record Snapshot(Map<Family, FamilyMetrics> families) {
        FamilyMetrics family(Family family) {
            return families.get(family);
        }

        long attempts() {
            return families.values().stream().mapToLong(FamilyMetrics::attempts).sum();
        }

        long resultRows() {
            return families.values().stream().mapToLong(FamilyMetrics::resultRows).sum();
        }

        long bodyValues() {
            return families.values().stream().mapToLong(FamilyMetrics::bodyValues).sum();
        }

        long rawBytes() {
            return families.values().stream().mapToLong(FamilyMetrics::rawBytes).sum();
        }
    }

    private record Projection(Family family, Set<String> labels) { }

    private static final class Counters {
        private final LongAdder attempts = new LongAdder();
        private final LongAdder rows = new LongAdder();
        private final LongAdder bodyValues = new LongAdder();
        private final LongAdder rawBytes = new LongAdder();

        private void reset() {
            attempts.reset();
            rows.reset();
            bodyValues.reset();
            rawBytes.reset();
        }
    }
}
