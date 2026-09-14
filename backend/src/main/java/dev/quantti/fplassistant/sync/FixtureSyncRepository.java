package dev.quantti.fplassistant.sync;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.FixtureVerification.Decision;
import dev.quantti.fplassistant.sync.FixtureVerification.Observation;
import dev.quantti.fplassistant.sync.FixtureVerification.Reason;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;

/** Database-only fixture verification state. Callers supply the surrounding transaction/fence. */
public final class FixtureSyncRepository {

    private final JdbcTemplate jdbc;
    private final OwnedSyncTables tables;

    public FixtureSyncRepository(JdbcTemplate jdbc, OwnedSyncTables tables) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tables = Objects.requireNonNull(tables);
    }

    /** Locks and validates the explicitly configured playing season; no resolver fallback is used. */
    public void lockAndValidatePlayingSeason(CatalogueScheduleParser.Season season) {
        jdbc.execute("LOCK TABLE season IN SHARE MODE");
        List<MapSeason> rows = jdbc.query("""
                SELECT id, code, start_date, end_date FROM season WHERE is_current=true FOR UPDATE
                """, (rs, ignored) -> new MapSeason(rs.getInt("id"), rs.getString("code"),
                rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date") == null ? null : rs.getDate("end_date").toLocalDate()));
        if (rows.size() != 1) throw new IllegalStateException("Expected exactly one playing season");
        MapSeason row = rows.getFirst();
        if (row.id() != season.id() || !row.code().equals(season.code())
                || !Objects.equals(row.start(), season.startsOn())
                || !Objects.equals(row.end(), season.endsOn())) {
            throw new IllegalStateException("Configured season does not match the playing database season");
        }
    }

    /** Creates then locks the fixture state, serializing two concurrent first observations. */
    public Optional<StoredObservation> lockObservation(CatalogueScheduleParser.Season season, int fixtureId,
                                                       Instant updatedAt) {
        jdbc.update("""
                INSERT INTO %s(season_id, season_code, fixture_id, state, updated_at)
                VALUES (?, ?, ?, 'SCHEDULED', ?) ON CONFLICT DO NOTHING
                """.formatted(tables.fixtureSync()), season.id(), season.code(), fixtureId,
                timestamp(updatedAt));
        List<StoredObservation> observations = jdbc.query("SELECT observation::text, observation_generation FROM "
                        + tables.fixtureSync() + " WHERE season_id = ? AND fixture_id = ? FOR UPDATE",
                (rs, row) -> {
                    String encoded = rs.getString(1);
                    Long generation = rs.getObject(2, Long.class);
                    if ((encoded == null) != (generation == null)) {
                        throw new IllegalStateException("Fixture observation and generation must be stored together");
                    }
                    return encoded == null ? null
                            : new StoredObservation(FixtureObservationCodec.decode(encoded), generation);
                }, season.id(), fixtureId);
        if (observations.isEmpty() || observations.getFirst() == null) return Optional.empty();
        return Optional.of(observations.getFirst());
    }

    /** Compatibility entry retaining the fixture publisher's season-validated call shape. */
    public Instant recordFailedAttempt(Season season, int fixtureId, Instant minimumDue,
                                       String error, Instant updatedAt) {
        Objects.requireNonNull(season);
        return recordFailedAttempt(season.code(), fixtureId, minimumDue, error, updatedAt);
    }

    /** Records a real failed attempt and applies the same durable retry ladder as pending results. */
    public Instant recordFailedAttempt(String seasonCode, int fixtureId, Instant minimumDue,
                                       String error, Instant updatedAt) {
        Objects.requireNonNull(seasonCode);
        Objects.requireNonNull(minimumDue);
        Objects.requireNonNull(updatedAt);
        List<Integer> seasonIds = jdbc.queryForList("SELECT season_id FROM " + tables.fixtureSync()
                + " WHERE season_code=? AND fixture_id=?", Integer.class, seasonCode, fixtureId);
        if (seasonIds.isEmpty()) return minimumDue;
        int seasonId = seasonIds.getFirst();
        String boundedError = error == null ? null
                : error.substring(0, Math.min(error.length(), OwnedSyncStore.MAX_ERROR_CHARS));
        jdbc.update("UPDATE " + tables.fixtureSync()
                        + " SET attempt_count=attempt_count + 1, last_error=?, updated_at=?"
                        + " WHERE season_id=? AND season_code=? AND fixture_id=?",
                boundedError, timestamp(updatedAt), seasonId, seasonCode, fixtureId);
        Instant adaptive = scheduleNextAttempt(seasonId, seasonCode, fixtureId, updatedAt);
        if (minimumDue.isAfter(adaptive)) {
            jdbc.update("UPDATE " + tables.fixtureSync() + " SET next_due_at=? WHERE season_id=? AND fixture_id=?",
                    timestamp(minimumDue), seasonId, fixtureId);
            return minimumDue;
        }
        return adaptive;
    }

    /**
     * Refuses an already-queued hourly attempt once the 12-hour window has elapsed. The first
     * due run at the persisted daily boundary is admitted; all later pending outcomes remain daily.
     */
    public AttemptAdmission admitAttempt(CatalogueScheduleParser.Season season, int fixtureId,
                                         Instant now) {
        List<AttemptState> rows = jdbc.query("SELECT state, first_finished_observed_at, next_due_at, alert_state FROM "
                        + tables.fixtureSync() + " WHERE season_id=? AND season_code=? AND fixture_id=? FOR UPDATE",
                (rs, row) -> new AttemptState(rs.getString(1), instant(rs.getTimestamp(2)),
                        instant(rs.getTimestamp(3)), rs.getString(4)), season.id(), season.code(), fixtureId);
        if (rows.isEmpty()) return new AttemptAdmission(true, null);
        AttemptState state = rows.getFirst();
        if ("VERIFIED".equals(state.state()) || state.firstFinished() == null
                || now.isBefore(state.firstFinished().plusSeconds(43_200))) {
            return new AttemptAdmission(state.nextDue() == null || !now.isBefore(state.nextDue()), state.nextDue());
        }
        if ("DAILY_ONLY".equals(state.alertState()) && state.nextDue() != null
                && !now.isBefore(state.nextDue())) return new AttemptAdmission(true, state.nextDue());
        Instant daily = nextDaily(now);
        jdbc.update("UPDATE " + tables.fixtureSync()
                        + " SET alert_state='DAILY_ONLY', next_due_at=?, updated_at=?"
                        + " WHERE season_id=? AND season_code=? AND fixture_id=?",
                timestamp(daily), timestamp(now), season.id(), season.code(), fixtureId);
        return new AttemptAdmission(false, daily);
    }

    public Instant scheduleNextAttempt(CatalogueScheduleParser.Season season, int fixtureId, Instant now) {
        return scheduleNextAttempt(season.id(), season.code(), fixtureId, now);
    }

    private Instant scheduleNextAttempt(int seasonId, String seasonCode, int fixtureId, Instant now) {
        RetryState state = jdbc.queryForObject("SELECT first_finished_observed_at, attempt_count, alert_state FROM "
                        + tables.fixtureSync() + " WHERE season_id=? AND season_code=? AND fixture_id=? FOR UPDATE",
                (rs, row) -> new RetryState(instant(rs.getTimestamp(1)), rs.getInt(2), rs.getString(3)),
                seasonId, seasonCode, fixtureId);
        Instant due;
        String alert = state.alertState();
        if (state.firstFinished() != null
                && (!now.isBefore(state.firstFinished().plusSeconds(43_200)) || "DAILY_ONLY".equals(alert))) {
            due = nextDaily(now);
            alert = "DAILY_ONLY";
        } else {
            long seconds = switch (state.attempts()) {
                case 0 -> 0;
                case 1 -> 600;
                case 2 -> 1_200;
                case 3 -> 2_400;
                default -> 3_600;
            };
            due = now.plusSeconds(seconds);
        }
        jdbc.update("UPDATE " + tables.fixtureSync() + " SET next_due_at=?, alert_state=?, updated_at=?"
                        + " WHERE season_id=? AND season_code=? AND fixture_id=?",
                timestamp(due), alert, timestamp(now), seasonId, seasonCode, fixtureId);
        return due;
    }

    /** Atomically advances per-row history fingerprints and returns only changed fixtures. */
    public Set<Integer> reconcileHistoryFingerprints(HistoryBatch batch, Instant updatedAt) {
        Set<Integer> changed = new HashSet<>();
        Set<Integer> previousFixtures = new HashSet<>(jdbc.queryForList(
                "SELECT fixture_id FROM " + tables.historyFingerprint()
                        + " WHERE season_id=? AND player_id=? FOR UPDATE",
                Integer.class, batch.seasonId(), batch.playerId()));
        for (HistoryRow row : batch.rows()) {
            previousFixtures.remove(row.fixtureId());
            String fingerprint = fingerprint(row);
            List<String> prior = jdbc.queryForList("SELECT fingerprint FROM " + tables.historyFingerprint()
                            + " WHERE season_id=? AND player_id=? AND fixture_id=?",
                    String.class, batch.seasonId(), batch.playerId(), row.fixtureId());
            if (prior.isEmpty() || !prior.getFirst().equals(fingerprint)) changed.add(row.fixtureId());
            jdbc.update("INSERT INTO " + tables.historyFingerprint()
                            + "(season_id, season_code, player_id, fixture_id, fingerprint, source_read_finished_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (season_id, player_id, fixture_id)"
                            + " DO UPDATE SET fingerprint=EXCLUDED.fingerprint,"
                            + " source_read_finished_at=EXCLUDED.source_read_finished_at, updated_at=EXCLUDED.updated_at",
                    batch.seasonId(), batch.seasonCode(), batch.playerId(), row.fixtureId(), fingerprint,
                    timestamp(batch.sourceRead().completedAt()), timestamp(updatedAt));
        }
        if (!previousFixtures.isEmpty()) {
            changed.addAll(previousFixtures);
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("DELETE FROM "
                        + tables.historyFingerprint()
                        + " WHERE season_id=? AND player_id=? AND fixture_id=ANY(?)");
                statement.setInt(1, batch.seasonId());
                statement.setInt(2, batch.playerId());
                statement.setArray(3, connection.createArrayOf("integer", previousFixtures.toArray(Integer[]::new)));
                return statement;
            });
        }
        return Set.copyOf(changed);
    }

    /** Parent checks are explicit; sparse PFS rows are never used as an FK probe. */
    public Set<Reason> missingParents(CatalogueScheduleParser.Season season, FixtureRow fixture,
                                      Collection<Integer> playerIds, Set<Integer> cataloguePlayerIds) {
        EnumSet<Reason> reasons = EnumSet.noneOf(Reason.class);
        List<StoredFixture> databaseFixtures = jdbc.query("""
                SELECT id, season_id, gameweek, code, team_h, team_a, team_h_score, team_a_score,
                       kickoff_time, started, finished, finished_provisional, minutes, stats::text
                FROM fixture WHERE season_id=? AND id=?
                """, (rs, ignored) -> new StoredFixture(FixtureVerification.verifiedHeader(
                rs.getInt("season_id"), season.code(), rs.getInt("id"), rs.getInt("code"),
                rs.getObject("gameweek", Integer.class), rs.getInt("team_h"), rs.getInt("team_a"),
                rs.getTimestamp("kickoff_time") == null ? null
                        : rs.getTimestamp("kickoff_time").toInstant(),
                rs.getObject("started", Boolean.class), rs.getObject("finished", Boolean.class),
                rs.getObject("finished_provisional", Boolean.class),
                rs.getObject("minutes", Integer.class), rs.getObject("team_h_score", Integer.class),
                rs.getObject("team_a_score", Integer.class)), rs.getString("stats")),
                season.id(), fixture.id());
        if (databaseFixtures.isEmpty()) {
            reasons.add(Reason.MISSING_DATABASE_FIXTURE);
        } else {
            StoredFixture stored = databaseFixtures.getFirst();
            if (!FixtureVerification.verifiedHeader(fixture).equals(stored.header())
                    || stored.stats() == null
                    || !FixtureStatsFingerprint.of(fixture).equals(
                            FixtureStatsFingerprint.of(stored.stats()))) {
                reasons.add(Reason.DATABASE_FIXTURE_MISMATCH);
            }
        }
        if (!playerIds.isEmpty()) {
            Integer[] ids = playerIds.toArray(Integer[]::new);
            List<Integer> present = jdbc.query(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM player WHERE season_id=? AND id=ANY(?)");
                statement.setInt(1, season.id());
                statement.setArray(2, connection.createArrayOf("integer", ids));
                return statement;
            }, (rs, ignored) -> rs.getInt(1));
            Set<Integer> missing = new HashSet<>(playerIds);
            missing.removeAll(present);
            if (!missing.isEmpty()) {
                if (cataloguePlayerIds == null || cataloguePlayerIds.containsAll(missing)) {
                    reasons.add(Reason.UNKNOWN_DATABASE_PLAYER);
                } else {
                    reasons.add(Reason.UNRESOLVED_PARENT);
                    if (cataloguePlayerIds.stream().anyMatch(missing::contains)) {
                        reasons.add(Reason.UNKNOWN_DATABASE_PLAYER);
                    }
                }
            }
        }
        return Set.copyOf(reasons);
    }

    public void saveDecision(long generation, FixtureRow fixture, Decision decision,
                             Instant updatedAt, Instant nextDueAt, String error) {
        Objects.requireNonNull(decision);
        Objects.requireNonNull(updatedAt);
        Observation observation = decision.observation();
        String state = state(decision);
        String[] failures = decision.reasons().stream().map(Reason::name).sorted().toArray(String[]::new);
        boolean verified = decision.state() == FixtureVerification.State.VERIFIED;
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO %s(season_id, season_code, fixture_id, state, observation,
                        observation_generation, current_verified_generation, last_verified_generation,
                        verified_header, verified_stats_fingerprint, last_verified_at, first_verified_at,
                        expected_participants, received_participants, missing_player_ids,
                        validation_failures, source_read_started_at, source_read_finished_at, next_due_at,
                        attempt_count, last_error, updated_at)
                    SELECT ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?
                    FROM season WHERE id = ? AND code = ?
                    ON CONFLICT (season_id, fixture_id) DO UPDATE SET
                        season_code=EXCLUDED.season_code, state=EXCLUDED.state,
                        observation=EXCLUDED.observation,
                        observation_generation=EXCLUDED.observation_generation,
                        current_verified_generation=EXCLUDED.current_verified_generation,
                        last_verified_generation=COALESCE(EXCLUDED.last_verified_generation,
                            %s.last_verified_generation),
                        verified_header=COALESCE(EXCLUDED.verified_header, %s.verified_header),
                        verified_stats_fingerprint=COALESCE(EXCLUDED.verified_stats_fingerprint,
                            %s.verified_stats_fingerprint),
                        last_verified_at=COALESCE(EXCLUDED.last_verified_at, %s.last_verified_at),
                        first_verified_at=COALESCE(%s.first_verified_at, EXCLUDED.first_verified_at),
                        expected_participants=EXCLUDED.expected_participants,
                        received_participants=EXCLUDED.received_participants,
                        missing_player_ids=EXCLUDED.missing_player_ids,
                        validation_failures=EXCLUDED.validation_failures,
                        source_read_started_at=EXCLUDED.source_read_started_at,
                        source_read_finished_at=EXCLUDED.source_read_finished_at,
                        next_due_at=EXCLUDED.next_due_at,
                        attempt_count=%s.attempt_count + 1,
                        last_error=EXCLUDED.last_error,
                        updated_at=EXCLUDED.updated_at
                    """.formatted(tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync(),
                    tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync()));
            int index = 1;
            statement.setInt(index++, observation.seasonId());
            statement.setString(index++, observation.seasonCode());
            statement.setInt(index++, observation.fixtureId());
            statement.setString(index++, state);
            statement.setString(index++, FixtureObservationCodec.encode(observation));
            statement.setLong(index++, generation);
            if (verified) statement.setLong(index++, generation); else statement.setObject(index++, null);
            if (verified) statement.setLong(index++, generation); else statement.setObject(index++, null);
            statement.setString(index++, verified ? FixtureVerification.verifiedHeader(fixture) : null);
            statement.setString(index++, verified ? FixtureStatsFingerprint.of(fixture) : null);
            statement.setTimestamp(index++, verified ? Timestamp.from(updatedAt) : null);
            statement.setTimestamp(index++, verified ? Timestamp.from(updatedAt) : null);
            statement.setInt(index++, decision.expectedParticipants());
            statement.setInt(index++, decision.receivedParticipants());
            Array missing = connection.createArrayOf("integer",
                    decision.missingParticipantIds().stream().sorted().toArray(Integer[]::new));
            statement.setArray(index++, missing);
            Array array = connection.createArrayOf("text", failures);
            statement.setArray(index++, array);
            statement.setTimestamp(index++, Timestamp.from(observation.reads().startedAt()));
            statement.setTimestamp(index++, Timestamp.from(observation.reads().completedAt()));
            statement.setTimestamp(index++, nextDueAt == null ? null : Timestamp.from(nextDueAt));
            statement.setString(index++, error);
            statement.setTimestamp(index++, Timestamp.from(updatedAt));
            statement.setInt(index++, observation.seasonId());
            statement.setString(index, observation.seasonCode());
            return statement;
        });
        Integer present = jdbc.queryForObject("SELECT count(*) FROM " + tables.fixtureSync()
                + " WHERE season_id=? AND fixture_id=? AND observation_generation=?", Integer.class,
                observation.seasonId(), observation.fixtureId(), generation);
        if (present == null || present != 1) throw new IllegalStateException("Configured season id/code mismatch");
    }

    /**
     * Revokes current verification when a schedule publication changes fixture identity/state.
     * This method starts no transaction and is intended to run before the core fixture upsert in
     * {@link CatalogueScheduleWriter}'s already-fenced publication callback.
     */
    public int invalidateCorrections(Collection<FixtureRow> incoming, Instant updatedAt) {
        int invalidated = 0;
        for (FixtureRow row : incoming) {
            List<StoredFixture> baselines = jdbc.query("""
                    SELECT f.id, f.season_id, f.gameweek, f.code, f.team_h, f.team_a,
                           f.team_h_score, f.team_a_score, f.kickoff_time, f.started, f.finished,
                           f.finished_provisional, f.minutes, f.stats::text
                    FROM %s s JOIN fixture f
                      ON f.season_id=s.season_id AND f.id=s.fixture_id
                    WHERE s.season_id=? AND s.fixture_id=?
                      AND (s.observation IS NOT NULL OR s.current_verified_generation IS NOT NULL)
                    FOR UPDATE OF s
                    """.formatted(tables.fixtureSync()), (rs, ignored) -> new StoredFixture(
                    FixtureVerification.verifiedHeader(rs.getInt("season_id"), row.seasonCode(),
                            rs.getInt("id"), rs.getInt("code"),
                            rs.getObject("gameweek", Integer.class), rs.getInt("team_h"),
                            rs.getInt("team_a"), rs.getTimestamp("kickoff_time") == null ? null
                                    : rs.getTimestamp("kickoff_time").toInstant(),
                            rs.getObject("started", Boolean.class), rs.getObject("finished", Boolean.class),
                            rs.getObject("finished_provisional", Boolean.class),
                            rs.getObject("minutes", Integer.class),
                            rs.getObject("team_h_score", Integer.class),
                            rs.getObject("team_a_score", Integer.class)), rs.getString("stats")),
                    row.seasonId(), row.id());
            boolean changed = !baselines.isEmpty()
                    && (!FixtureVerification.verifiedHeader(row).equals(baselines.getFirst().header())
                    || baselines.getFirst().stats() == null
                    || !FixtureStatsFingerprint.of(row).equals(
                            FixtureStatsFingerprint.of(baselines.getFirst().stats())));
            if (changed) {
                invalidated += jdbc.update("UPDATE " + tables.fixtureSync() + " SET state=?, "
                                + "observation=NULL, observation_generation=NULL, "
                                + "current_verified_generation=NULL, first_verified_at=NULL, "
                                + "expected_participants=NULL, received_participants=NULL, "
                                + "source_read_started_at=NULL, source_read_finished_at=NULL, "
                                + "validation_failures=ARRAY['SOURCE_CORRECTION'], next_due_at=?, "
                                + "updated_at=? WHERE season_id=? AND fixture_id=?",
                        correctionState(row), timestamp(updatedAt), timestamp(updatedAt),
                        row.seasonId(), row.id());
            }
        }
        return invalidated;
    }

    private static String correctionState(FixtureRow row) {
        if (!row.started()) return "SCHEDULED";
        return row.finished() ? "VERIFYING" : "WAITING_FINISH";
    }

    private static String state(Decision decision) {
        if (decision.state() == FixtureVerification.State.VERIFIED) return "VERIFIED";
        if (!decision.observation().finished()) return "WAITING_FINISH";
        if (!decision.observation().coherent()) return "WAITING_STATS";
        return "VERIFYING";
    }

    private static String fingerprint(HistoryRow row) {
        try {
            StringBuilder canonical = new StringBuilder();
            for (var component : HistoryRow.class.getRecordComponents()) {
                Object value = component.getAccessor().invoke(row);
                if (value instanceof Set<?>) value = row.presenceMask();
                if (value instanceof BigDecimal decimal) value = decimal.stripTrailingZeros().toPlainString();
                String text = value == null ? "<null>" : value.toString();
                canonical.append(component.getName()).append('=').append(text.length())
                        .append(':').append(text).append(';');
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString()
                    .getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        } catch (IllegalAccessException | InvocationTargetException reflectionFailure) {
            throw new IllegalStateException("Could not fingerprint validated history row", reflectionFailure);
        }
    }

    private static Instant nextDaily(Instant now) {
        var date = now.atZone(AdaptiveMatchdayPlanner.UK).toLocalDate();
        Instant candidate = date.atTime(3, 0).atZone(AdaptiveMatchdayPlanner.UK).toInstant();
        return candidate.isAfter(now) ? candidate
                : date.plusDays(1).atTime(3, 0).atZone(AdaptiveMatchdayPlanner.UK).toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record AttemptAdmission(boolean admitted, Instant nextDueAt) { }
    private record AttemptState(String state, Instant firstFinished, Instant nextDue,
                                String alertState) { }
    private record RetryState(Instant firstFinished, int attempts, String alertState) { }

    public record StoredObservation(Observation observation, long generation) {
        public StoredObservation { Objects.requireNonNull(observation); }
    }

    private record StoredFixture(String header, String stats) { }
    private record MapSeason(int id, String code, java.time.LocalDate start, java.time.LocalDate end) { }
}
