package dev.quantti.fplassistant.sync;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

/** Measures projected checkpoint bodies at the JdbcTemplate result-set boundary. */
class PayloadReadProbe extends JdbcTemplate {
    private static final Pattern FROM = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_BODY_PROJECTION = Pattern.compile(
            "(?:^|,)\\s*(?:[a-z_][a-z0-9_$]*\\s*\\.\\s*)?body\\s*"
                    + "(?:as\\s+([a-z_][a-z0-9_$]*))?\\s*(?=,|$)", Pattern.CASE_INSENSITIVE);

    private final Map<Family, Counters> counters = new EnumMap<>(Family.class);
    private final Map<BodyFamily, Counters> logicalBodies = new EnumMap<>(BodyFamily.class);

    PayloadReadProbe(DataSource dataSource) {
        super(dataSource);
        for (Family family : Family.values()) counters.put(family, new Counters());
        for (BodyFamily family : BodyFamily.values()) logicalBodies.put(family, new Counters());
    }

    @Override
    public <T> T query(PreparedStatementCreator creator, ResultSetExtractor<T> extractor) {
        if (!(creator instanceof OwnedSyncStore.SqlPreparedStatementCreator statementCreator)) {
            return super.query(creator, extractor);
        }
        Projection projection = projection(statementCreator.sql());
        if (projection == null) return super.query(creator, extractor);
        Counters selected = counters.get(projection.family());
        Counters logical = logicalBodies.get(bodyFamily(statementCreator.sql(), new Object[0], projection.labels()));
        selected.attempts.increment();
        logical.attempts.increment();
        AtomicLong queryBytes = new AtomicLong();
        T result = super.query(creator, (ResultSetExtractor<T>) resultSet -> extractor.extractData(observeResultSet(
                resultSet, projection.labels(), selected, logical, queryBytes)));
        selected.maxQueryBytes.accumulateAndGet(queryBytes.get(), Math::max);
        logical.maxQueryBytes.accumulateAndGet(queryBytes.get(), Math::max);
        return result;
    }

    @Override
    public <T> java.util.List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
        Projection projection = projection(sql);
        if (projection == null) return super.query(sql, rowMapper, args);

        Counters selected = counters.get(projection.family());
        Counters logical = logicalBodies.get(bodyFamily(sql, args, projection.labels()));
        selected.attempts.increment();
        logical.attempts.increment();
        AtomicLong queryBytes = new AtomicLong();
        java.util.List<T> rows = super.query(sql, (resultSet, rowNumber) -> {
            selected.rows.increment();
            logical.rows.increment();
            queryBytes.addAndGet(observeBodies(resultSet, projection.labels(), selected, logical));
            return rowMapper.mapRow(resultSet, rowNumber);
        }, args);
        selected.maxQueryBytes.accumulateAndGet(queryBytes.get(), Math::max);
        logical.maxQueryBytes.accumulateAndGet(queryBytes.get(), Math::max);
        return rows;
    }

    Snapshot snapshot() {
        Map<Family, FamilyMetrics> result = new EnumMap<>(Family.class);
        counters.forEach((family, value) -> result.put(family,
                new FamilyMetrics(value.attempts.sum(), value.rows.sum(), value.bodyValues.sum(),
                        value.rawBytes.sum())));
        Map<BodyFamily, FamilyMetrics> logical = new EnumMap<>(BodyFamily.class);
        Map<BodyFamily, Long> maximumQueryBytes = new EnumMap<>(BodyFamily.class);
        logicalBodies.forEach((family, value) -> {
            logical.put(family, new FamilyMetrics(value.attempts.sum(), value.rows.sum(), value.bodyValues.sum(),
                    value.rawBytes.sum()));
            maximumQueryBytes.put(family, value.maxQueryBytes.get());
        });
        return new Snapshot(Map.copyOf(result), Map.copyOf(logical), Map.copyOf(maximumQueryBytes));
    }

    void reset() {
        counters.values().forEach(Counters::reset);
        logicalBodies.values().forEach(Counters::reset);
    }

    static boolean projectsBody(String sql) {
        return projection(sql) != null;
    }

    private static Projection projection(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        int select = lower.indexOf("select");
        Matcher from = FROM.matcher(lower);
        while (select >= 0) {
            if (!from.find(select + "select".length())) return null;
            String selectList = lower.substring(select + "select".length(), from.start());
            Matcher columns = RAW_BODY_PROJECTION.matcher(selectList);
            Set<String> labels = new HashSet<>();
            while (columns.find()) {
                String alias = columns.group(1);
                labels.add(alias == null ? "body" : alias.toLowerCase(Locale.ROOT));
            }
            if (!labels.isEmpty()) {
                Family family;
                if (labels.contains("source_body_digest")) {
                    family = Family.PUBLISHED_SOURCE_DIGEST;
                } else if (labels.contains("b_body") && labels.contains("f_body")) {
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
            select = lower.indexOf("select", from.end());
        }
        return null;
    }

    private static BodyFamily bodyFamily(String sql, Object[] args, Set<String> labels) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (labels.contains("source_body_digest")) return BodyFamily.PUBLISHED_SOURCE_DIGESTS;
        if (labels.contains("b_body") && labels.contains("f_body")) return BodyFamily.CANONICAL_PAIRS;
        if (lower.contains("'element_summary'")
                || java.util.Arrays.stream(args).anyMatch("ELEMENT_SUMMARY"::equals)) {
            return BodyFamily.CROSS_RUN_HISTORIES;
        }
        if (java.util.Arrays.stream(args).anyMatch(value ->
                "BOOTSTRAP_STATIC".equals(value) || "FIXTURES".equals(value) || "EVENT_LIVE".equals(value))) {
            return BodyFamily.OWN_ANCHORS;
        }
        return lower.contains("fixture_dependency_manifest")
                ? BodyFamily.OWN_ANCHORS : BodyFamily.OTHER;
    }

    private static ResultSet observeResultSet(ResultSet resultSet, Set<String> labels,
                                              Counters selected, Counters logical, AtomicLong queryBytes) {
        return (ResultSet) Proxy.newProxyInstance(PayloadReadProbe.class.getClassLoader(),
                new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                    try {
                        Object value = method.invoke(resultSet, args);
                        if ("next".equals(method.getName()) && Boolean.TRUE.equals(value)) {
                            long rowBytes = observeBodies(resultSet, labels, selected, logical);
                            selected.rows.increment();
                            logical.rows.increment();
                            queryBytes.addAndGet(rowBytes);
                        }
                        return value;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static long observeBodies(ResultSet resultSet, Set<String> labels, Counters... counters)
            throws SQLException {
        long rowBytes = 0;
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int column = 1; column <= metadata.getColumnCount(); column++) {
            String label = metadata.getColumnLabel(column).toLowerCase(Locale.ROOT);
            if (!labels.contains(label)) continue;
            byte[] body = resultSet.getBytes(column);
            if (body != null) {
                rowBytes += body.length;
                for (Counters counter : counters) {
                    counter.bodyValues.increment();
                    counter.rawBytes.add(body.length);
                }
            }
        }
        return rowBytes;
    }

    enum Family {
        OLD_OWN_CHECKPOINT,
        GUARDED_SINGLE,
        CANONICAL_PAIR,
        PUBLISHED_SOURCE_DIGEST,
        OTHER_BODY_SELECT
    }

    enum BodyFamily { OWN_ANCHORS, CROSS_RUN_HISTORIES, CANONICAL_PAIRS, PUBLISHED_SOURCE_DIGESTS, OTHER }

    record FamilyMetrics(long attempts, long resultRows, long bodyValues, long rawBytes) {
        String counts() {
            return attempts + "/" + resultRows + "/" + bodyValues + "/" + rawBytes;
        }
    }

    record Snapshot(Map<Family, FamilyMetrics> families, Map<BodyFamily, FamilyMetrics> logicalBodies,
                    Map<BodyFamily, Long> maximumQueryBytes) {
        FamilyMetrics family(Family family) {
            return families.get(family);
        }

        FamilyMetrics bodyFamily(BodyFamily family) {
            return logicalBodies.get(family);
        }

        long maxBodyBytesInSingleQuery(BodyFamily family) {
            return maximumQueryBytes.get(family);
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
        private final AtomicLong maxQueryBytes = new AtomicLong();

        private void reset() {
            attempts.reset();
            rows.reset();
            bodyValues.reset();
            rawBytes.reset();
            maxQueryBytes.set(0);
        }
    }
}
