package fi.karivanttinen.fplassistant.sync;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotent schema for durable owned-sync coordination and staging.
 *
 * <p>This class is deliberately not a Spring component. Task 2a only provides an explicit
 * initialization boundary; constructing the application does not create or activate a writer.
 */
public final class OwnedSyncSchema {

    static final int MAX_PAYLOAD_BYTES = 2_000_000;
    static final int MAX_RETAINED_PAYLOAD_BYTES_PER_RUN = 20_000_000;

    private final JdbcTemplate jdbc;
    private final OwnedSyncTables tables;

    public OwnedSyncSchema(JdbcTemplate jdbc, OwnedSyncTables tables) {
        this.jdbc = jdbc;
        this.tables = tables;
    }

    public void initialize() {
        jdbc.execute("CREATE SEQUENCE IF NOT EXISTS %s_generation_seq AS BIGINT".formatted(tables.run()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season VARCHAR(10) NOT NULL,
                    publication_key VARCHAR(200) NOT NULL,
                    latest_generation BIGINT,
                    source_read_started_at TIMESTAMPTZ,
                    source_read_finished_at TIMESTAMPTZ,
                    published_at TIMESTAMPTZ,
                    PRIMARY KEY (season, publication_key),
                    CONSTRAINT ck_%s_read_interval CHECK (
                        source_read_started_at IS NULL OR source_read_finished_at IS NULL
                        OR source_read_started_at <= source_read_finished_at)
                )
                """.formatted(tables.receipt(), tables.receipt()));
        migrateReceiptIdentity();
        for (String column : List.of(
                "source_bootstrap_payload_id BIGINT",
                "source_fixtures_payload_id BIGINT",
                "source_bootstrap_read_started_at TIMESTAMPTZ",
                "source_bootstrap_read_finished_at TIMESTAMPTZ",
                "source_fixtures_read_started_at TIMESTAMPTZ",
                "source_fixtures_read_finished_at TIMESTAMPTZ",
                "source_bootstrap_sha256 VARCHAR(64)",
                "source_fixtures_sha256 VARCHAR(64)",
                "source_pair_fingerprint VARCHAR(64)",
                "source_catalogue JSONB")) {
            jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s".formatted(tables.receipt(), column));
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    generation BIGINT PRIMARY KEY DEFAULT nextval('%s_generation_seq'),
                    kind VARCHAR(40) NOT NULL,
                    producer_key VARCHAR(40) NOT NULL DEFAULT 'LEGACY',
                    season VARCHAR(10) NOT NULL,
                    coalescing_key VARCHAR(200) NOT NULL,
                    publication_key VARCHAR(200) NOT NULL,
                    priority SMALLINT NOT NULL,
                    due_at TIMESTAMPTZ NOT NULL,
                    requested_source_after TIMESTAMPTZ NOT NULL,
                    runtime_mode VARCHAR(16) NOT NULL DEFAULT 'OFF',
                    runtime_epoch BIGINT NOT NULL DEFAULT 0,
                    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    consecutive_fetch_failures INTEGER NOT NULL DEFAULT 0,
                    last_attempt_at TIMESTAMPTZ,
                    lease_owner VARCHAR(128),
                    lease_token UUID,
                    lease_fence BIGINT NOT NULL DEFAULT 0,
                    lease_expires_at TIMESTAMPTZ,
                    source_read_started_at TIMESTAMPTZ,
                    source_read_finished_at TIMESTAMPTZ,
                    last_error VARCHAR(1000),
                    supersede_reason VARCHAR(80),
                    failed_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    CONSTRAINT fk_%s_receipt FOREIGN KEY (season, publication_key)
                        REFERENCES %s(season, publication_key),
                    CONSTRAINT ck_%s_state CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED', 'SUPERSEDED')),
                    CONSTRAINT ck_%s_read_interval CHECK (
                        source_read_started_at IS NULL OR source_read_finished_at IS NULL
                        OR source_read_started_at <= source_read_finished_at),
                    CONSTRAINT ck_%s_lease CHECK (
                        (state = 'LEASED' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                         AND lease_expires_at IS NOT NULL)
                        OR (state <> 'LEASED' AND lease_owner IS NULL AND lease_token IS NULL
                            AND lease_expires_at IS NULL))
                )
                """.formatted(tables.run(), tables.run(), tables.run(), tables.receipt(),
                tables.run(), tables.run(), tables.run()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_state"
                .formatted(tables.run(), tables.run()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_state CHECK (state IN "
                .formatted(tables.run(), tables.run())
                + "('PENDING', 'LEASED', 'COMPLETED', 'FAILED', 'SUPERSEDED'))");
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS supersede_reason VARCHAR(80)"
                .formatted(tables.run()));
        for (OwnedSyncStore.IncrementalSupersedeReason reason
                : OwnedSyncStore.IncrementalSupersedeReason.values()) {
            jdbc.update("UPDATE %s SET supersede_reason=?,last_error=NULL WHERE state='SUPERSEDED'"
                            .formatted(tables.run())
                            + " AND supersede_reason IS NULL AND last_error IN (?,?)",
                    reason.code(), reason.persistedCode(), reason.legacyText());
        }
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_supersede_reason"
                .formatted(tables.run(), tables.run()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_supersede_reason CHECK "
                .formatted(tables.run(), tables.run())
                + "(supersede_reason IS NULL OR (state='SUPERSEDED' AND supersede_reason IN ("
                + OwnedSyncStore.IncrementalSupersedeReason.acceptedSqlValues() + ")))");
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS producer_key VARCHAR(40)"
                .formatted(tables.run()));
        jdbc.execute("UPDATE %s SET producer_key='LEGACY' WHERE producer_key IS NULL"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ALTER COLUMN producer_key SET DEFAULT 'LEGACY'"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ALTER COLUMN producer_key SET NOT NULL"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS requested_source_after TIMESTAMPTZ"
                .formatted(tables.run()));
        jdbc.execute("UPDATE %s SET requested_source_after = GREATEST(created_at, due_at) "
                .formatted(tables.run()) + "WHERE requested_source_after IS NULL");
        jdbc.execute("ALTER TABLE %s ALTER COLUMN requested_source_after SET NOT NULL"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS runtime_mode VARCHAR(16)"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS runtime_epoch BIGINT"
                .formatted(tables.run()));
        jdbc.execute("UPDATE %s SET runtime_mode=COALESCE(runtime_mode, 'OFF'), "
                .formatted(tables.run()) + "runtime_epoch=COALESCE(runtime_epoch, 0)");
        jdbc.execute("ALTER TABLE %s ALTER COLUMN runtime_mode SET DEFAULT 'OFF'".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ALTER COLUMN runtime_epoch SET DEFAULT 0".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ALTER COLUMN runtime_mode SET NOT NULL".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ALTER COLUMN runtime_epoch SET NOT NULL".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS fk_%s_receipt"
                .formatted(tables.run(), tables.run()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT fk_%s_receipt FOREIGN KEY (season, publication_key) "
                .formatted(tables.run(), tables.run())
                + "REFERENCES %s(season, publication_key)".formatted(tables.receipt()));
        jdbc.execute(("CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_active_work "
                + "ON %s(kind, season, coalescing_key) WHERE state IN ('PENDING', 'LEASED')")
                .formatted(tables.run(), tables.run()));
        jdbc.execute(("CREATE INDEX IF NOT EXISTS idx_%s_due "
                + "ON %s(priority, due_at, generation) WHERE state IN ('PENDING', 'LEASED')")
                .formatted(tables.run(), tables.run()));
        jdbc.execute(("CREATE INDEX IF NOT EXISTS idx_%s_epoch_attempts "
                + "ON %s(season, runtime_mode, runtime_epoch) "
                + "INCLUDE (attempt_count, kind, coalescing_key)")
                .formatted(tables.run(), tables.run()));
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_gen_season ON %s(generation, season)"
                .formatted(tables.run(), tables.run()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    run_generation BIGINT NOT NULL REFERENCES %s(generation) ON DELETE CASCADE,
                    endpoint_kind VARCHAR(40) NOT NULL,
                    event_id INTEGER,
                    player_id INTEGER,
                    read_started_at TIMESTAMPTZ NOT NULL,
                    read_finished_at TIMESTAMPTZ NOT NULL,
                    validation_state VARCHAR(20) NOT NULL,
                    validation_error VARCHAR(1000),
                    source_published BOOLEAN NOT NULL DEFAULT FALSE,
                    source_payload_sha256 VARCHAR(64),
                    body_pruned_at TIMESTAMPTZ,
                    body BYTEA NOT NULL,
                    staged_at TIMESTAMPTZ NOT NULL,
                    CONSTRAINT ck_%s_validation CHECK (validation_state IN ('UNCHECKED', 'VALID', 'INVALID')),
                    CONSTRAINT ck_%s_read_interval CHECK (read_started_at <= read_finished_at),
                    CONSTRAINT ck_%s_body_size CHECK (octet_length(body) <= %d)
                )
                """.formatted(tables.payload(), tables.run(), tables.payload(),
                tables.payload(), tables.payload(), MAX_PAYLOAD_BYTES));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS source_payload_sha256 VARCHAR(64)"
                .formatted(tables.payload()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS body_pruned_at TIMESTAMPTZ"
                .formatted(tables.payload()));
        // Kept as an explicit replace so an idempotent initialize also tightens a table made by
        // an earlier Task 2a build whose CHECK allowed SQL NULL to pass.
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_endpoint"
                .formatted(tables.payload(), tables.payload()));
        jdbc.execute("""
                ALTER TABLE %s ADD CONSTRAINT ck_%s_endpoint CHECK (
                    (endpoint_kind IN ('BOOTSTRAP_STATIC', 'FIXTURES')
                        AND event_id IS NULL AND player_id IS NULL)
                    OR (endpoint_kind = 'EVENT_LIVE' AND event_id IS NOT NULL
                        AND event_id > 0 AND player_id IS NULL)
                    OR (endpoint_kind = 'ELEMENT_SUMMARY' AND event_id IS NULL
                        AND player_id IS NOT NULL AND player_id > 0))
                """.formatted(tables.payload(), tables.payload()));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_owned_payload_source_guard()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF TG_OP = 'INSERT' THEN
                        IF NEW.body_pruned_at IS NOT NULL THEN
                            RAISE EXCEPTION 'new source payload cannot start pruned';
                        END IF;
                        NEW.source_payload_sha256 := encode(sha256(NEW.body), 'hex');
                        RETURN NEW;
                    END IF;

                    IF OLD.body_pruned_at IS NOT NULL THEN
                        IF NEW.id IS DISTINCT FROM OLD.id
                            OR NEW.run_generation IS DISTINCT FROM OLD.run_generation
                            OR NEW.endpoint_kind IS DISTINCT FROM OLD.endpoint_kind
                            OR NEW.event_id IS DISTINCT FROM OLD.event_id
                            OR NEW.player_id IS DISTINCT FROM OLD.player_id
                            OR NEW.read_started_at IS DISTINCT FROM OLD.read_started_at
                            OR NEW.read_finished_at IS DISTINCT FROM OLD.read_finished_at
                            OR NEW.validation_state IS DISTINCT FROM OLD.validation_state
                            OR NEW.validation_error IS DISTINCT FROM OLD.validation_error
                            OR NEW.source_published IS DISTINCT FROM OLD.source_published
                            OR NEW.source_payload_sha256 IS DISTINCT FROM OLD.source_payload_sha256
                            OR NEW.body_pruned_at IS DISTINCT FROM OLD.body_pruned_at
                            OR NEW.body IS DISTINCT FROM OLD.body
                            OR NEW.staged_at IS DISTINCT FROM OLD.staged_at THEN
                            RAISE EXCEPTION 'pruned source payload is immutable';
                        END IF;
                        RETURN NEW;
                    END IF;

                    IF NEW.body_pruned_at IS NOT NULL THEN
                        IF NEW.body IS DISTINCT FROM ''::bytea
                            OR NEW.id IS DISTINCT FROM OLD.id
                            OR NEW.run_generation IS DISTINCT FROM OLD.run_generation
                            OR NEW.endpoint_kind IS DISTINCT FROM OLD.endpoint_kind
                            OR NEW.event_id IS DISTINCT FROM OLD.event_id
                            OR NEW.player_id IS DISTINCT FROM OLD.player_id
                            OR NEW.read_started_at IS DISTINCT FROM OLD.read_started_at
                            OR NEW.read_finished_at IS DISTINCT FROM OLD.read_finished_at
                            OR NEW.validation_state IS DISTINCT FROM OLD.validation_state
                            OR NEW.validation_error IS DISTINCT FROM OLD.validation_error
                            OR NEW.source_published IS DISTINCT FROM OLD.source_published
                            OR NEW.source_payload_sha256 IS DISTINCT FROM OLD.source_payload_sha256
                            OR NEW.staged_at IS DISTINCT FROM OLD.staged_at
                            OR OLD.source_payload_sha256 IS NULL
                            OR OLD.source_payload_sha256 !~ '^[0-9a-f]{64}$'
                            OR OLD.source_payload_sha256 IS DISTINCT FROM encode(sha256(OLD.body), 'hex') THEN
                            RAISE EXCEPTION 'source payload body prune does not match its captured proof';
                        END IF;
                        RETURN NEW;
                    END IF;

                    IF (OLD.source_published AND (
                            NEW.run_generation IS DISTINCT FROM OLD.run_generation
                            OR NEW.endpoint_kind IS DISTINCT FROM OLD.endpoint_kind
                            OR NEW.event_id IS DISTINCT FROM OLD.event_id
                            OR NEW.player_id IS DISTINCT FROM OLD.player_id
                            OR NEW.read_started_at IS DISTINCT FROM OLD.read_started_at
                            OR NEW.read_finished_at IS DISTINCT FROM OLD.read_finished_at
                            OR NEW.body IS DISTINCT FROM OLD.body
                            OR NEW.source_payload_sha256 IS DISTINCT FROM OLD.source_payload_sha256
                            OR NEW.validation_state IS DISTINCT FROM 'VALID'
                            OR NEW.source_published IS DISTINCT FROM TRUE))
                        OR NEW.run_generation IS DISTINCT FROM OLD.run_generation
                        OR NEW.endpoint_kind IS DISTINCT FROM OLD.endpoint_kind
                        OR NEW.event_id IS DISTINCT FROM OLD.event_id
                        OR NEW.player_id IS DISTINCT FROM OLD.player_id
                        OR NEW.read_started_at IS DISTINCT FROM OLD.read_started_at
                        OR NEW.read_finished_at IS DISTINCT FROM OLD.read_finished_at
                        OR NEW.body IS DISTINCT FROM OLD.body
                        OR NEW.source_payload_sha256 IS DISTINCT FROM OLD.source_payload_sha256
                        OR (OLD.validation_state='VALID' AND NEW.validation_state IS DISTINCT FROM 'VALID')
                        OR (OLD.validation_state='INVALID' AND NEW.validation_state='VALID') THEN
                        NEW.validation_state := 'INVALID';
                        NEW.source_published := OLD.source_published;
                        NEW.validation_error := 'Source payload was mutated or invalidated';
                    END IF;
                    RETURN NEW;
                END
                $$
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_owned_payload_source_guard ON %s".formatted(tables.payload()));
        jdbc.execute("CREATE TRIGGER trg_owned_payload_source_guard BEFORE INSERT OR UPDATE ON %s "
                .formatted(tables.payload())
                + "FOR EACH ROW EXECUTE FUNCTION fpl_owned_payload_source_guard()");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_run ON %s(run_generation)"
                .formatted(tables.payload(), tables.payload()));
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_id_run ON %s(id, run_generation)"
                .formatted(tables.payload(), tables.payload()));
        // The fixture-readiness anti-join probes each required player and read floor across runs.
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_%s_history_readiness
                ON %s(player_id, read_started_at, run_generation)
                WHERE endpoint_kind='ELEMENT_SUMMARY' AND event_id IS NULL
                  AND validation_state='VALID'
                """.formatted(tables.payload(), tables.payload()));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_sync_required_ids_valid(ids INTEGER[])
                RETURNS BOOLEAN
                LANGUAGE SQL IMMUTABLE PARALLEL SAFE STRICT
                AS $$
                    SELECT cardinality(ids) BETWEEN 1 AND 80
                       AND array_ndims(ids) = 1
                       AND array_lower(ids, 1) = 1
                       AND NOT EXISTS (
                           SELECT 1 FROM generate_subscripts(ids, 1) AS i
                           WHERE ids[i] IS NULL OR ids[i] <= 0
                              OR (i > 1 AND ids[i] <= ids[i - 1])
                       )
                $$
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    run_generation BIGINT PRIMARY KEY,
                    bootstrap_payload_id BIGINT NOT NULL,
                    fixtures_payload_id BIGINT NOT NULL,
                    live_payload_id BIGINT NOT NULL,
                    event_id INTEGER NOT NULL,
                    required_player_ids INTEGER[] NOT NULL,
                    source_floor TIMESTAMPTZ NOT NULL,
                    CONSTRAINT fk_%s_run FOREIGN KEY (run_generation)
                        REFERENCES %s(generation) ON DELETE CASCADE,
                    CONSTRAINT fk_%s_bootstrap FOREIGN KEY (bootstrap_payload_id, run_generation)
                        REFERENCES %s(id, run_generation) ON DELETE CASCADE,
                    CONSTRAINT fk_%s_fixtures FOREIGN KEY (fixtures_payload_id, run_generation)
                        REFERENCES %s(id, run_generation) ON DELETE CASCADE,
                    CONSTRAINT fk_%s_live FOREIGN KEY (live_payload_id, run_generation)
                        REFERENCES %s(id, run_generation) ON DELETE CASCADE,
                    CONSTRAINT ck_%s_required_players CHECK (
                        fpl_sync_required_ids_valid(required_player_ids)),
                    CONSTRAINT ck_%s_event_id CHECK (event_id > 0)
                )
                """.formatted(tables.fixtureDependencyManifest(), tables.fixtureDependencyManifest(),
                tables.run(), tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest(), tables.fixtureDependencyManifest()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS event_id INTEGER"
                .formatted(tables.fixtureDependencyManifest()));
        jdbc.update("UPDATE %s m SET event_id=p.event_id FROM %s p WHERE p.id=m.live_payload_id "
                        .formatted(tables.fixtureDependencyManifest(), tables.payload())
                + "AND p.run_generation=m.run_generation AND m.event_id IS NULL");
        jdbc.execute("ALTER TABLE %s ALTER COLUMN event_id SET NOT NULL"
                .formatted(tables.fixtureDependencyManifest()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_event_id"
                .formatted(tables.fixtureDependencyManifest(), tables.fixtureDependencyManifest()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_event_id CHECK (event_id > 0)"
                .formatted(tables.fixtureDependencyManifest(), tables.fixtureDependencyManifest()));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
                    last_admitted_at TIMESTAMPTZ,
                    cooldown_until TIMESTAMPTZ
                )
                """.formatted(tables.requestState()));
        jdbc.update("INSERT INTO %s(singleton) VALUES (TRUE) ON CONFLICT DO NOTHING"
                .formatted(tables.requestState()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    permit_id UUID PRIMARY KEY,
                    admitted_at TIMESTAMPTZ NOT NULL,
                    permit_expires_at TIMESTAMPTZ NOT NULL,
                    completed_at TIMESTAMPTZ,
                    hold_until_expiry BOOLEAN NOT NULL DEFAULT FALSE,
                    CONSTRAINT ck_%s_permit_interval CHECK (admitted_at < permit_expires_at),
                    CONSTRAINT ck_%s_completion CHECK (completed_at IS NULL OR completed_at >= admitted_at)
                )
                """.formatted(tables.requestAttempt(), tables.requestAttempt(), tables.requestAttempt()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS hold_until_expiry BOOLEAN NOT NULL DEFAULT FALSE"
                .formatted(tables.requestAttempt()));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_admitted ON %s(admitted_at)"
                .formatted(tables.requestAttempt(), tables.requestAttempt()));
        jdbc.execute("DROP INDEX IF EXISTS idx_%s_live".formatted(tables.requestAttempt()));
        jdbc.execute("CREATE INDEX idx_%s_live ON %s(permit_expires_at) "
                        .formatted(tables.requestAttempt(), tables.requestAttempt())
                        + "WHERE completed_at IS NULL OR hold_until_expiry");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season_id INTEGER NOT NULL REFERENCES season(id),
                    season_code VARCHAR(10) NOT NULL,
                    fixture_id INTEGER NOT NULL,
                    state VARCHAR(24) NOT NULL,
                    observation JSONB,
                    observation_generation BIGINT REFERENCES %s(generation),
                    current_verified_generation BIGINT REFERENCES %s(generation),
                    last_verified_generation BIGINT REFERENCES %s(generation),
                    verified_header VARCHAR(64),
                    verified_stats_fingerprint VARCHAR(64),
                    last_verified_at TIMESTAMPTZ,
                    first_verified_at TIMESTAMPTZ,
                    first_finished_observed_at TIMESTAMPTZ,
                    finished_parent_generation BIGINT REFERENCES %s(generation),
                    finished_parent_publication_generation BIGINT REFERENCES %s(generation),
                    data_checked_transition_at TIMESTAMPTZ,
                    last_data_checked BOOLEAN NOT NULL DEFAULT FALSE,
                    expected_participants INTEGER,
                    received_participants INTEGER,
                    missing_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    validation_failures TEXT[] NOT NULL DEFAULT '{}',
                    source_read_started_at TIMESTAMPTZ,
                    source_read_finished_at TIMESTAMPTZ,
                    next_due_at TIMESTAMPTZ,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    last_error VARCHAR(1000),
                    alert_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, fixture_id),
                    CONSTRAINT ck_%s_state CHECK (state IN
                        ('SCHEDULED', 'IN_PLAY', 'WAITING_FINISH', 'WAITING_STATS', 'VERIFYING', 'VERIFIED')),
                    CONSTRAINT ck_%s_counts CHECK (
                        (expected_participants IS NULL OR expected_participants >= 0)
                        AND (received_participants IS NULL OR received_participants >= 0)),
                    CONSTRAINT ck_%s_read_interval CHECK (
                        source_read_started_at IS NULL OR source_read_finished_at IS NULL
                        OR source_read_started_at <= source_read_finished_at),
                    CONSTRAINT ck_%s_verified CHECK (
                        (state = 'VERIFIED') = (current_verified_generation IS NOT NULL))
                )
                """.formatted(tables.fixtureSync(), tables.run(), tables.run(), tables.run(), tables.run(),
                tables.run(), tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS source_published BOOLEAN NOT NULL DEFAULT FALSE"
                .formatted(tables.payload()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS verified_header VARCHAR(64)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS verified_stats_fingerprint VARCHAR(64)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS first_verified_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS first_finished_observed_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_generation BIGINT REFERENCES %s(generation)"
                .formatted(tables.fixtureSync(), tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_publication_generation BIGINT REFERENCES %s(generation)"
                .formatted(tables.fixtureSync(), tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_bootstrap_payload_id BIGINT"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_fixtures_payload_id BIGINT"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_source_fingerprint VARCHAR(64)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_fixture_fingerprint VARCHAR(64)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS finished_parent_finished BOOLEAN"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS data_checked_transition_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS last_data_checked BOOLEAN NOT NULL DEFAULT FALSE"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS missing_player_ids INTEGER[] NOT NULL DEFAULT '{}'"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS next_due_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS recovery_day DATE"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS recovery_started_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS recovery_until TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS due_reason VARCHAR(16)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS transport_retry_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS dependency_retry_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_recovery_episode"
                .formatted(tables.fixtureSync(), tables.fixtureSync()));
        jdbc.execute("""
                ALTER TABLE %s ADD CONSTRAINT ck_%s_recovery_episode CHECK (
                    (recovery_day IS NULL AND recovery_started_at IS NULL AND recovery_until IS NULL)
                    OR (recovery_day IS NOT NULL AND recovery_started_at IS NOT NULL
                        AND recovery_until IS NOT NULL AND recovery_started_at < recovery_until
                        AND recovery_until <= recovery_started_at + INTERVAL '12 hours'
                        AND recovery_day = CASE
                            WHEN (recovery_started_at AT TIME ZONE 'Europe/London')::time < TIME '03:00'
                            THEN (recovery_started_at AT TIME ZONE 'Europe/London')::date - 1
                            ELSE (recovery_started_at AT TIME ZONE 'Europe/London')::date
                        END)
                )
                """.formatted(tables.fixtureSync(), tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_due_reason"
                .formatted(tables.fixtureSync(), tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_due_reason CHECK "
                .formatted(tables.fixtureSync(), tables.fixtureSync())
                + "(due_reason IS NULL OR due_reason IN ('POLICY', 'DEPENDENCY', 'TRANSPORT'))");
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS consecutive_fetch_failures INTEGER NOT NULL DEFAULT 0"
                .formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS last_error VARCHAR(1000)"
                .formatted(tables.fixtureSync()));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_due ON %s(next_due_at)"
                .formatted(tables.fixtureSync(), tables.fixtureSync()));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season_id INTEGER NOT NULL,
                    season_code VARCHAR(10) NOT NULL,
                    player_id INTEGER NOT NULL,
                    fixture_id INTEGER NOT NULL,
                    fingerprint VARCHAR(64) NOT NULL,
                    source_read_finished_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, player_id, fixture_id)
                )
                """.formatted(tables.historyFingerprint()));
        for (String constraint : jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid=?::regclass AND contype='f'
                """, String.class, tables.historyFingerprint())) {
            jdbc.execute("ALTER TABLE %s DROP CONSTRAINT %s"
                    .formatted(tables.historyFingerprint(), constraint));
        }

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season_id INTEGER NOT NULL REFERENCES season(id),
                    season_code VARCHAR(10) NOT NULL,
                    fixture_id INTEGER NOT NULL,
                    player_id INTEGER NOT NULL,
                    generation BIGINT NOT NULL REFERENCES %s(generation),
                    component_presence BIGINT NOT NULL,
                    source_read_started_at TIMESTAMPTZ NOT NULL,
                    source_read_finished_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, fixture_id, player_id, generation),
                    CONSTRAINT ck_%s_identity CHECK (
                        length(season_code) > 0
                        AND fixture_id > 0 AND player_id > 0 AND generation > 0),
                    CONSTRAINT ck_%s_components CHECK (
                        component_presence >= 0 AND component_presence <= %d),
                    CONSTRAINT ck_%s_read_interval CHECK (
                        source_read_started_at <= source_read_finished_at)
                )
                """.formatted(tables.playerFixtureEvidence(), tables.run(),
                tables.playerFixtureEvidence(), tables.playerFixtureEvidence(),
                HistoryParser.Component.allMask(), tables.playerFixtureEvidence()));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_validate_evidence_season() RETURNS trigger AS $$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM season
                                   WHERE id = NEW.season_id AND code = NEW.season_code) THEN
                        RAISE EXCEPTION 'player-fixture evidence season id/code mismatch';
                    END IF;
                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_validate_evidence_season ON "
                + tables.playerFixtureEvidence());
        jdbc.execute("CREATE TRIGGER trg_validate_evidence_season BEFORE INSERT ON "
                + tables.playerFixtureEvidence()
                + " FOR EACH ROW EXECUTE FUNCTION fpl_validate_evidence_season()");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_reject_evidence_mutation() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'player-fixture evidence is immutable; publish a new generation';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_immutable_evidence ON "
                + tables.playerFixtureEvidence());
        jdbc.execute("CREATE TRIGGER trg_immutable_evidence BEFORE UPDATE OR DELETE ON "
                + tables.playerFixtureEvidence()
                + " FOR EACH ROW EXECUTE FUNCTION fpl_reject_evidence_mutation()");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season_id INTEGER NOT NULL REFERENCES season(id),
                    season_code VARCHAR(10) NOT NULL,
                    fixture_id INTEGER NOT NULL,
                    player_id INTEGER NOT NULL,
                    row_state VARCHAR(12) NOT NULL,
                    source_run_generation BIGINT NOT NULL,
                    evidence_generation BIGINT,
                    row_fingerprint VARCHAR(64),
                    physical_fingerprint VARCHAR(64),
                    source_read_started_at TIMESTAMPTZ NOT NULL,
                    source_read_finished_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, fixture_id, player_id),
                    CONSTRAINT fk_%s_source_run_season FOREIGN KEY (source_run_generation, season_code)
                        REFERENCES %s(generation, season),
                    CONSTRAINT fk_%s_evidence FOREIGN KEY
                        (season_id, fixture_id, player_id, evidence_generation)
                        REFERENCES %s(season_id, fixture_id, player_id, generation),
                    CONSTRAINT ck_%s_state CHECK (row_state IN ('VALID', 'WITHDRAWN')),
                    CONSTRAINT ck_%s_pointer CHECK (
                        (row_state='VALID' AND evidence_generation IS NOT NULL
                            AND evidence_generation=source_run_generation
                            AND row_fingerprint IS NOT NULL
                            AND row_fingerprint ~ '^[0-9a-f]{64}$')
                        OR (row_state='WITHDRAWN' AND evidence_generation IS NULL
                            AND row_fingerprint IS NULL)),
                    CONSTRAINT ck_%s_read_interval CHECK (
                        source_read_started_at <= source_read_finished_at)
                )
                """.formatted(tables.playerFixtureCurrent(), tables.playerFixtureCurrent(), tables.run(),
                tables.playerFixtureCurrent(), tables.playerFixtureEvidence(), tables.playerFixtureCurrent(),
                tables.playerFixtureCurrent(), tables.playerFixtureCurrent()));
        // Existing pointers have no proof tying their source fingerprint to the physical PFS row;
        // leave this NULL so the reader fails closed rather than retroactively certifying them.
        jdbc.execute("ALTER TABLE " + tables.playerFixtureCurrent()
                + " ADD COLUMN IF NOT EXISTS physical_fingerprint VARCHAR(64)");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_validate_current_season ON " + tables.playerFixtureCurrent());
        jdbc.execute("CREATE TRIGGER trg_validate_current_season BEFORE INSERT OR UPDATE ON "
                + tables.playerFixtureCurrent()
                + " FOR EACH ROW EXECUTE FUNCTION fpl_validate_evidence_season()");

        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_owned_sync_checkpoint_ids_valid(ids INTEGER[])
                RETURNS BOOLEAN
                LANGUAGE SQL IMMUTABLE PARALLEL SAFE STRICT
                AS $$
                    SELECT cardinality(ids) <= %d
                       AND (cardinality(ids) = 0 OR
                            (array_ndims(ids) = 1 AND array_lower(ids, 1) = 1))
                       AND NOT EXISTS (
                           SELECT 1 FROM generate_subscripts(ids, 1) AS i
                           WHERE ids[i] IS NULL OR ids[i] <= 0
                              OR (i > 1 AND ids[i] <= ids[i - 1])
                       )
                $$
                """.formatted(GameweekFinality.MAX_CHECKPOINT_PLAYER_IDS));
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_owned_sync_optional_coverage_valid(coverage JSONB)
                RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE STRICT AS $$
                DECLARE
                    entry JSONB;
                    source_text TEXT;
                    present_text TEXT;
                    source_rows BIGINT;
                    present_rows BIGINT;
                BEGIN
                    IF jsonb_typeof(coverage) <> 'object'
                        OR NOT (coverage ?& ARRAY[
                            'EXPECTED_GOALS','EXPECTED_ASSISTS','EXPECTED_GOAL_INVOLVEMENTS',
                            'EXPECTED_GOALS_CONCEDED','INFLUENCE','CREATIVITY','THREAT','ICT_INDEX',
                            'CLEARANCES_BLOCKS_INTERCEPTIONS','RECOVERIES','TACKLES',
                            'DEFENSIVE_CONTRIBUTION'])
                        OR coverage - ARRAY[
                            'EXPECTED_GOALS','EXPECTED_ASSISTS','EXPECTED_GOAL_INVOLVEMENTS',
                            'EXPECTED_GOALS_CONCEDED','INFLUENCE','CREATIVITY','THREAT','ICT_INDEX',
                            'CLEARANCES_BLOCKS_INTERCEPTIONS','RECOVERIES','TACKLES',
                            'DEFENSIVE_CONTRIBUTION'] <> '{}'::jsonb THEN
                        RETURN FALSE;
                    END IF;
                    FOR entry IN SELECT value FROM jsonb_each(coverage) LOOP
                        IF jsonb_typeof(entry) <> 'object'
                            OR NOT (entry ?& ARRAY['sourceRows','presentRows','supported'])
                            OR entry - ARRAY['sourceRows','presentRows','supported'] <> '{}'::jsonb
                            OR jsonb_typeof(entry->'sourceRows') IS DISTINCT FROM 'number'
                            OR jsonb_typeof(entry->'presentRows') IS DISTINCT FROM 'number'
                            OR jsonb_typeof(entry->'supported') IS DISTINCT FROM 'boolean' THEN
                            RETURN FALSE;
                        END IF;
                        source_text := entry->>'sourceRows';
                        present_text := entry->>'presentRows';
                        IF source_text !~ '^(0|[1-9][0-9]*)$' OR length(source_text) > 10
                            OR present_text !~ '^(0|[1-9][0-9]*)$' OR length(present_text) > 10 THEN
                            RETURN FALSE;
                        END IF;
                        source_rows := source_text::BIGINT;
                        present_rows := present_text::BIGINT;
                        IF source_rows > 2147483647 OR present_rows > source_rows
                            OR present_rows > 2147483647
                            OR (entry->>'supported')::BOOLEAN IS FALSE AND present_rows <> 0 THEN
                            RETURN FALSE;
                        END IF;
                    END LOOP;
                    RETURN TRUE;
                END
                $$
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    season_id INTEGER NOT NULL REFERENCES season(id),
                    season_code VARCHAR(10) NOT NULL,
                    gameweek INTEGER NOT NULL,
                    state VARCHAR(20) NOT NULL DEFAULT 'OPEN',
                    event_finished BOOLEAN,
                    data_checked BOOLEAN,
                    fixture_manifest_complete BOOLEAN NOT NULL DEFAULT FALSE,
                    all_assigned_fixtures_finished BOOLEAN NOT NULL DEFAULT FALSE,
                    source_signature VARCHAR(64),
                    first_checked_at TIMESTAMPTZ,
                    current_invalidation_revision BIGINT NOT NULL DEFAULT 0,
                    current_invalidation_floor TIMESTAMPTZ,
                    final_attempt_source_signature VARCHAR(64),
                    final_attempt_source_floor TIMESTAMPTZ,
                    final_attempt_invalidation_revision BIGINT,
                    final_attempt_population_fingerprint VARCHAR(64),
                    final_attempt_population_manifest JSONB,
                    final_attempt_source_read_started_at TIMESTAMPTZ,
                    final_attempt_initial_source_read_finished_at TIMESTAMPTZ,
                    final_attempt_source_read_finished_at TIMESTAMPTZ,
                    final_attempt_required_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_completed_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_outstanding_player_ids INTEGER[] NOT NULL DEFAULT '{}',
                    final_attempt_page_read_started_at TIMESTAMPTZ,
                    final_attempt_page_read_finished_at TIMESTAMPTZ,
                    finalized_source_signature VARCHAR(64),
                    finalized_population_fingerprint VARCHAR(64),
                    finalized_row_revision_fingerprint VARCHAR(64),
                    finalized_source_floor TIMESTAMPTZ,
                    finalized_validation_read_started_at TIMESTAMPTZ,
                    finalized_validation_read_finished_at TIMESTAMPTZ,
                    finalized_source_read_started_at TIMESTAMPTZ,
                    finalized_source_read_finished_at TIMESTAMPTZ,
                    finalized_invalidation_revision BIGINT,
                    finalized_run_generation BIGINT,
                    finalized_page_evidence JSONB,
                    finalized_at TIMESTAMPTZ,
                    finalized_mandatory_manifest_complete BOOLEAN,
                    finalized_optional_component_coverage JSONB,
                    legacy_finality_evidence JSONB,
                    updated_at TIMESTAMPTZ NOT NULL,
                    PRIMARY KEY (season_id, gameweek),
                    CONSTRAINT fk_%s_final_run_season FOREIGN KEY (finalized_run_generation, season_code)
                        REFERENCES %s(generation, season),
                    CONSTRAINT ck_%s_gameweek CHECK (gameweek BETWEEN 1 AND 38),
                    CONSTRAINT ck_%s_state CHECK (state IN ('OPEN', 'FINAL_PENDING', 'FINALIZED')),
                    CONSTRAINT ck_%s_invalidation CHECK (current_invalidation_revision >= 0
                        AND (current_invalidation_revision = 0 OR current_invalidation_floor IS NOT NULL)
                        AND (final_attempt_invalidation_revision IS NULL
                            OR final_attempt_invalidation_revision >= 0)
                        AND (finalized_invalidation_revision IS NULL OR finalized_invalidation_revision >= 0)),
                    CONSTRAINT ck_%s_signatures CHECK (
                        (source_signature IS NULL OR source_signature ~ '^[0-9a-f]{64}$')
                        AND (final_attempt_source_signature IS NULL
                            OR final_attempt_source_signature ~ '^[0-9a-f]{64}$')
                        AND (finalized_source_signature IS NULL
                            OR finalized_source_signature ~ '^[0-9a-f]{64}$')
                        AND (finalized_row_revision_fingerprint IS NULL
                            OR finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$')),
                    CONSTRAINT ck_%s_manifest_state CHECK (
                        fixture_manifest_complete OR NOT all_assigned_fixtures_finished),
                    CONSTRAINT ck_%s_receipt CHECK (
                        (finalized_source_signature IS NULL AND finalized_row_revision_fingerprint IS NULL
                            AND finalized_source_floor IS NULL AND finalized_invalidation_revision IS NULL
                            AND finalized_run_generation IS NULL AND finalized_at IS NULL
                            AND finalized_mandatory_manifest_complete IS NULL
                            AND finalized_optional_component_coverage IS NULL)
                        OR (finalized_source_signature IS NOT NULL
                            AND finalized_row_revision_fingerprint IS NOT NULL
                            AND finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'
                            AND finalized_source_floor IS NOT NULL
                            AND finalized_invalidation_revision IS NOT NULL
                            AND finalized_run_generation IS NOT NULL AND finalized_page_evidence IS NOT NULL
                            AND jsonb_typeof(finalized_page_evidence)='object' AND finalized_at IS NOT NULL
                            AND finalized_source_floor <= finalized_at
                            AND finalized_mandatory_manifest_complete IS TRUE
                            AND fpl_owned_sync_optional_coverage_valid(
                                finalized_optional_component_coverage) IS TRUE)),
                    CONSTRAINT ck_%s_final_pending CHECK (
                        state <> 'FINAL_PENDING' OR (event_finished IS TRUE AND data_checked IS TRUE
                            AND fixture_manifest_complete AND all_assigned_fixtures_finished
                            AND source_signature IS NOT NULL)),
                    CONSTRAINT ck_%s_checkpoint CHECK (
                        (final_attempt_source_signature IS NULL) = (final_attempt_source_floor IS NULL)
                        AND (final_attempt_source_signature IS NULL)
                            = (final_attempt_invalidation_revision IS NULL)
                        AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_required_player_ids)
                        AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_completed_player_ids)
                        AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_outstanding_player_ids)
                        AND final_attempt_completed_player_ids <@ final_attempt_required_player_ids
                        AND final_attempt_outstanding_player_ids <@ final_attempt_required_player_ids
                        AND NOT (final_attempt_completed_player_ids && final_attempt_outstanding_player_ids)
                        AND cardinality(final_attempt_completed_player_ids)
                            + cardinality(final_attempt_outstanding_player_ids)
                            = cardinality(final_attempt_required_player_ids)),
                    CONSTRAINT ck_%s_finalized CHECK (
                        state <> 'FINALIZED' OR (event_finished IS TRUE AND data_checked IS TRUE
                            AND fixture_manifest_complete AND all_assigned_fixtures_finished
                            AND source_signature IS NOT NULL
                            AND final_attempt_source_signature = source_signature
                            AND final_attempt_source_floor IS NOT NULL
                            AND final_attempt_invalidation_revision = current_invalidation_revision
                            AND (current_invalidation_floor IS NULL
                                OR final_attempt_source_floor >= current_invalidation_floor)
                            AND finalized_source_signature = source_signature
                            AND finalized_row_revision_fingerprint IS NOT NULL
                            AND finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'
                            AND finalized_source_floor = final_attempt_source_floor
                            AND finalized_invalidation_revision = current_invalidation_revision
                            AND finalized_run_generation IS NOT NULL AND finalized_at IS NOT NULL
                            AND finalized_mandatory_manifest_complete IS TRUE
                            AND cardinality(final_attempt_outstanding_player_ids) = 0
                            AND final_attempt_completed_player_ids = final_attempt_required_player_ids))
                )
                """.formatted(tables.gameweekStatSync(), tables.gameweekStatSync(), tables.run(),
                tables.gameweekStatSync(), tables.gameweekStatSync(), tables.gameweekStatSync(),
                tables.gameweekStatSync(), tables.gameweekStatSync(), tables.gameweekStatSync(),
                tables.gameweekStatSync(), tables.gameweekStatSync(), tables.gameweekStatSync()));
        for (String column : List.of(
                "final_attempt_population_fingerprint VARCHAR(64)",
                "final_attempt_population_manifest JSONB",
                "final_attempt_source_read_started_at TIMESTAMPTZ",
                "final_attempt_initial_source_read_finished_at TIMESTAMPTZ",
                "final_attempt_source_read_finished_at TIMESTAMPTZ",
                "final_attempt_page_read_started_at TIMESTAMPTZ",
                "final_attempt_page_read_finished_at TIMESTAMPTZ",
                "finalized_population_fingerprint VARCHAR(64)",
                "finalized_validation_read_started_at TIMESTAMPTZ",
                "finalized_validation_read_finished_at TIMESTAMPTZ",
                "finalized_source_read_started_at TIMESTAMPTZ",
                "finalized_source_read_finished_at TIMESTAMPTZ",
                "finalized_page_evidence JSONB",
                "legacy_finality_evidence JSONB")) {
            jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS %s"
                    .formatted(tables.gameweekStatSync(), column));
        }
        retireUnsupportedGameweekFinality();
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_signatures"
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_signatures CHECK ("
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync())
                + "(source_signature IS NULL OR source_signature ~ '^[0-9a-f]{64}$')"
                + " AND (final_attempt_source_signature IS NULL OR final_attempt_source_signature ~ '^[0-9a-f]{64}$')"
                + " AND (final_attempt_population_fingerprint IS NULL OR final_attempt_population_fingerprint ~ '^[0-9a-f]{64}$')"
                + " AND (finalized_source_signature IS NULL OR finalized_source_signature ~ '^[0-9a-f]{64}$')"
                + " AND (finalized_population_fingerprint IS NULL OR finalized_population_fingerprint ~ '^[0-9a-f]{64}$')"
                + " AND (finalized_row_revision_fingerprint IS NULL OR finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'))");
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_checkpoint"
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_checkpoint CHECK ("
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync())
                + "(final_attempt_source_signature IS NULL) = (final_attempt_source_floor IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_invalidation_revision IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_population_fingerprint IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_population_manifest IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_source_read_started_at IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_initial_source_read_finished_at IS NULL)"
                + " AND (final_attempt_source_signature IS NULL) = (final_attempt_source_read_finished_at IS NULL)"
                + " AND ((final_attempt_source_read_started_at IS NULL) = (final_attempt_source_read_finished_at IS NULL))"
                + " AND (final_attempt_source_read_started_at IS NULL OR"
                + " (final_attempt_source_read_started_at <= final_attempt_initial_source_read_finished_at"
                + " AND final_attempt_initial_source_read_finished_at <= final_attempt_source_read_finished_at))"
                + " AND (final_attempt_population_manifest IS NULL OR"
                + " (jsonb_typeof(final_attempt_population_manifest)='object'"
                + " AND final_attempt_population_manifest ?& ARRAY['bootstrapFingerprint','catalogue',"
                + " 'participants','known','fixtureIdentities']))"
                + " AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_required_player_ids)"
                + " AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_completed_player_ids)"
                + " AND fpl_owned_sync_checkpoint_ids_valid(final_attempt_outstanding_player_ids)"
                + " AND final_attempt_completed_player_ids <@ final_attempt_required_player_ids"
                + " AND final_attempt_outstanding_player_ids <@ final_attempt_required_player_ids"
                + " AND NOT (final_attempt_completed_player_ids && final_attempt_outstanding_player_ids)"
                + " AND cardinality(final_attempt_completed_player_ids)"
                + " + cardinality(final_attempt_outstanding_player_ids) = cardinality(final_attempt_required_player_ids)"
                + " AND ((final_attempt_page_read_started_at IS NULL) = (final_attempt_page_read_finished_at IS NULL))"
                + " AND (final_attempt_source_signature IS NOT NULL OR final_attempt_page_read_started_at IS NULL)"
                + " AND (final_attempt_page_read_started_at IS NULL OR"
                + " (final_attempt_page_read_started_at <= final_attempt_page_read_finished_at"
                + " AND final_attempt_page_read_started_at >= final_attempt_source_floor"
                + " AND final_attempt_page_read_started_at >= final_attempt_source_read_started_at"
                + " AND final_attempt_page_read_finished_at <= final_attempt_source_read_finished_at)))");
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_receipt"
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync()));
        jdbc.execute("""
                ALTER TABLE %s ADD CONSTRAINT ck_%s_receipt CHECK (
                    (finalized_source_signature IS NULL AND finalized_population_fingerprint IS NULL
                        AND finalized_row_revision_fingerprint IS NULL AND finalized_source_floor IS NULL
                        AND finalized_source_read_started_at IS NULL AND finalized_source_read_finished_at IS NULL
                        AND finalized_validation_read_started_at IS NULL
                        AND finalized_validation_read_finished_at IS NULL
                        AND finalized_invalidation_revision IS NULL AND finalized_run_generation IS NULL
                        AND finalized_page_evidence IS NULL AND finalized_at IS NULL
                        AND finalized_mandatory_manifest_complete IS NULL
                        AND finalized_optional_component_coverage IS NULL)
                    OR (finalized_source_signature IS NOT NULL
                        AND finalized_population_fingerprint IS NOT NULL
                        AND finalized_row_revision_fingerprint IS NOT NULL
                        AND finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'
                        AND finalized_source_floor IS NOT NULL
                        AND finalized_source_read_started_at IS NOT NULL
                        AND finalized_source_read_finished_at IS NOT NULL
                        AND finalized_source_read_started_at <= finalized_source_read_finished_at
                        AND finalized_source_read_started_at = final_attempt_source_read_started_at
                        AND finalized_source_read_finished_at = final_attempt_source_read_finished_at
                        AND finalized_validation_read_started_at IS NOT NULL
                        AND finalized_validation_read_finished_at IS NOT NULL
                        AND finalized_validation_read_started_at <= finalized_validation_read_finished_at
                        AND finalized_source_read_started_at <= finalized_validation_read_started_at
                        AND finalized_source_read_finished_at >= finalized_validation_read_finished_at
                        AND (final_attempt_page_read_started_at IS NULL OR
                            (finalized_source_read_started_at <= final_attempt_page_read_started_at
                                AND finalized_source_read_finished_at >= final_attempt_page_read_finished_at))
                        AND finalized_invalidation_revision IS NOT NULL
                        AND finalized_run_generation IS NOT NULL
                        AND finalized_page_evidence IS NOT NULL
                        AND jsonb_typeof(finalized_page_evidence)='object' AND finalized_at IS NOT NULL
                        AND finalized_source_floor <= finalized_at
                        AND finalized_validation_read_started_at >= finalized_source_floor
                        AND finalized_validation_read_finished_at <= finalized_at
                        AND finalized_mandatory_manifest_complete IS TRUE
                        AND fpl_owned_sync_optional_coverage_valid(
                            finalized_optional_component_coverage) IS TRUE))
                """.formatted(tables.gameweekStatSync(), tables.gameweekStatSync()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_final_pending CHECK (state <> 'FINAL_PENDING' OR "
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync())
                + "(event_finished IS TRUE AND data_checked IS TRUE AND fixture_manifest_complete "
                + "AND all_assigned_fixtures_finished AND source_signature IS NOT NULL))");
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_finalized"
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_finalized CHECK (state <> 'FINALIZED' OR ("
                .formatted(tables.gameweekStatSync(), tables.gameweekStatSync())
                + "event_finished IS TRUE AND data_checked IS TRUE AND fixture_manifest_complete"
                + " AND all_assigned_fixtures_finished AND source_signature IS NOT NULL"
                + " AND final_attempt_source_signature = source_signature"
                + " AND final_attempt_source_floor IS NOT NULL"
                + " AND final_attempt_invalidation_revision = current_invalidation_revision"
                + " AND (current_invalidation_floor IS NULL OR final_attempt_source_floor >= current_invalidation_floor)"
                + " AND finalized_source_signature = source_signature"
                + " AND finalized_row_revision_fingerprint ~ '^[0-9a-f]{64}$'"
                + " AND finalized_source_floor = final_attempt_source_floor"
                + " AND finalized_invalidation_revision = current_invalidation_revision"
                + " AND finalized_run_generation IS NOT NULL AND finalized_page_evidence IS NOT NULL"
                + " AND jsonb_typeof(finalized_page_evidence)='object' AND finalized_at IS NOT NULL"
                + " AND finalized_validation_read_started_at >= final_attempt_source_floor"
                + " AND finalized_validation_read_finished_at <= finalized_at"
                + " AND finalized_source_read_started_at = final_attempt_source_read_started_at"
                + " AND finalized_source_read_finished_at = final_attempt_source_read_finished_at"
                + " AND finalized_source_read_started_at <= finalized_validation_read_started_at"
                + " AND finalized_source_read_finished_at >= finalized_validation_read_finished_at"
                + " AND (final_attempt_page_read_started_at IS NULL OR"
                + " (finalized_source_read_started_at <= final_attempt_page_read_started_at"
                + " AND finalized_source_read_finished_at >= final_attempt_page_read_finished_at))"
                + " AND (final_attempt_page_read_finished_at IS NULL OR"
                + " finalized_validation_read_started_at >= final_attempt_page_read_finished_at)"
                + " AND finalized_mandatory_manifest_complete IS TRUE"
                + " AND cardinality(final_attempt_outstanding_player_ids) = 0"
                + " AND final_attempt_completed_player_ids = final_attempt_required_player_ids))");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fpl_owned_sync_invalidation_guard() RETURNS trigger AS $$
                BEGIN
                    IF NEW.current_invalidation_revision < OLD.current_invalidation_revision
                        OR (OLD.current_invalidation_floor IS NOT NULL
                            AND (NEW.current_invalidation_floor IS NULL
                                OR NEW.current_invalidation_floor < OLD.current_invalidation_floor)) THEN
                        RAISE EXCEPTION 'gameweek invalidation fence cannot move backwards';
                    END IF;
                    RETURN NEW;
                END
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("DROP TRIGGER IF EXISTS trg_validate_gameweek_sync_season ON "
                + tables.gameweekStatSync());
        jdbc.execute("CREATE TRIGGER trg_validate_gameweek_sync_season BEFORE INSERT OR UPDATE ON "
                + tables.gameweekStatSync()
                + " FOR EACH ROW EXECUTE FUNCTION fpl_validate_evidence_season()");
        jdbc.execute("DROP TRIGGER IF EXISTS trg_gameweek_invalidation_guard ON "
                + tables.gameweekStatSync());
        jdbc.execute("CREATE TRIGGER trg_gameweek_invalidation_guard BEFORE UPDATE ON "
                + tables.gameweekStatSync()
                + " FOR EACH ROW EXECUTE FUNCTION fpl_owned_sync_invalidation_guard()");

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    slot_id VARCHAR(300) PRIMARY KEY,
                    season VARCHAR(10) NOT NULL,
                    kind VARCHAR(40) NOT NULL,
                    due_at TIMESTAMPTZ NOT NULL,
                    fixture_ids INTEGER[] NOT NULL,
                    player_ids INTEGER[] NOT NULL,
                    reason VARCHAR(40) NOT NULL,
                    estimated_requests INTEGER NOT NULL,
                    enqueued_at TIMESTAMPTZ NOT NULL,
                    CONSTRAINT ck_%s_estimate CHECK (estimated_requests >= 0)
                )
                """.formatted(tables.plannerSlot(), tables.plannerSlot()));
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_season_due ON %s(season, due_at)"
                .formatted(tables.plannerSlot(), tables.plannerSlot()));
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    instance_id VARCHAR(128) PRIMARY KEY,
                    started_at TIMESTAMPTZ NOT NULL,
                    heartbeat_at TIMESTAMPTZ NOT NULL,
                    last_error VARCHAR(1000),
                    last_error_at TIMESTAMPTZ,
                    runtime_mode VARCHAR(16),
                    runtime_epoch BIGINT NOT NULL DEFAULT 0,
                    activation_boundary TIMESTAMPTZ,
                    CONSTRAINT ck_%s_error CHECK ((last_error IS NULL) = (last_error_at IS NULL))
                )
                """.formatted(tables.plannerRuntime(), tables.plannerRuntime()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS runtime_mode VARCHAR(16)"
                .formatted(tables.plannerRuntime()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS runtime_epoch BIGINT NOT NULL DEFAULT 0"
                .formatted(tables.plannerRuntime()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS activation_boundary TIMESTAMPTZ"
                .formatted(tables.plannerRuntime()));
        // A failed planner tick is not a successful heartbeat. Older schemas required a value;
        // watchdog liveness needs to represent "started but never succeeded" explicitly.
        jdbc.execute("ALTER TABLE %s ALTER COLUMN heartbeat_at DROP NOT NULL"
                .formatted(tables.plannerRuntime()));

        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGSERIAL PRIMARY KEY,
                    incident_key VARCHAR(240) NOT NULL,
                    occurrence INTEGER NOT NULL,
                    event_type VARCHAR(12) NOT NULL,
                    category VARCHAR(40) NOT NULL,
                    severity VARCHAR(12) NOT NULL,
                    season VARCHAR(10),
                    fixture_id INTEGER,
                    payload JSONB NOT NULL,
                    delivery_state VARCHAR(16) NOT NULL,
                    delivery_attempts INTEGER NOT NULL DEFAULT 0,
                    next_delivery_at TIMESTAMPTZ,
                    delivery_token UUID,
                    delivery_lease_until TIMESTAMPTZ,
                    last_delivery_error VARCHAR(1000),
                    created_at TIMESTAMPTZ NOT NULL,
                    delivered_at TIMESTAMPTZ,
                    CONSTRAINT ck_%s_event CHECK (event_type IN ('INCIDENT', 'RECOVERY', 'REDRIVE')),
                    CONSTRAINT ck_%s_delivery CHECK (delivery_state IN
                        ('PENDING', 'DELIVERING', 'DELIVERED', 'FAILED', 'DISABLED', 'UNCONFIGURED', 'SUPERSEDED', 'EXTERNAL')),
                    CONSTRAINT ck_%s_attempts CHECK (delivery_attempts BETWEEN 0 AND 3),
                    CONSTRAINT ck_%s_delivery_lease CHECK (
                        (delivery_state='DELIVERING' AND delivery_token IS NOT NULL
                         AND delivery_lease_until IS NOT NULL)
                        OR (delivery_state<>'DELIVERING' AND delivery_token IS NULL
                            AND delivery_lease_until IS NULL))
                )
                """.formatted(tables.alert(), tables.alert(), tables.alert(),
                tables.alert(), tables.alert()));
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS %s_incident_key_occurrence_event_type_key"
                .formatted(tables.alert(), tables.alert()));
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_incident_event ON %s"
                .formatted(tables.alert(), tables.alert())
                + "(incident_key,occurrence,event_type) WHERE event_type<>'REDRIVE'");
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_delivery"
                .formatted(tables.alert(), tables.alert()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_delivery CHECK (delivery_state IN "
                .formatted(tables.alert(), tables.alert())
                + "('PENDING','DELIVERING','DELIVERED','FAILED','DISABLED','UNCONFIGURED','SUPERSEDED','EXTERNAL'))");
        jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_event"
                .formatted(tables.alert(), tables.alert()));
        jdbc.execute("ALTER TABLE %s ADD CONSTRAINT ck_%s_event CHECK "
                .formatted(tables.alert(), tables.alert())
                + "(event_type IN ('INCIDENT', 'RECOVERY', 'REDRIVE'))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_delivery ON %s(delivery_state, next_delivery_at)"
                .formatted(tables.alert(), tables.alert()));

        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.payload()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.receipt()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.requestState()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.requestAttempt()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.fixtureDependencyManifest()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.historyFingerprint()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.playerFixtureEvidence()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.playerFixtureCurrent()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.gameweekStatSync()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.plannerSlot()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.plannerRuntime()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.alert()));
    }

    /** Retires old proofs that predate population and actual source-read interval evidence. */
    private void retireUnsupportedGameweekFinality() {
        String table = tables.gameweekStatSync();
        for (String constraint : List.of("checkpoint", "receipt", "finalized", "final_pending")) {
            jdbc.execute("ALTER TABLE %s DROP CONSTRAINT IF EXISTS ck_%s_%s"
                    .formatted(table, table, constraint));
        }
        jdbc.update("""
                UPDATE %s AS g SET
                    legacy_finality_evidence=COALESCE(g.legacy_finality_evidence,
                        to_jsonb(g) - 'legacy_finality_evidence'),
                    state=CASE WHEN g.event_finished IS TRUE AND g.data_checked IS TRUE
                        AND g.fixture_manifest_complete AND g.all_assigned_fixtures_finished
                        AND g.source_signature IS NOT NULL THEN 'FINAL_PENDING' ELSE 'OPEN' END,
                    final_attempt_source_signature=NULL,
                    final_attempt_source_floor=NULL,
                    final_attempt_invalidation_revision=NULL,
                    final_attempt_population_fingerprint=NULL,
                    final_attempt_population_manifest=NULL,
                    final_attempt_source_read_started_at=NULL,
                    final_attempt_initial_source_read_finished_at=NULL,
                    final_attempt_source_read_finished_at=NULL,
                    final_attempt_required_player_ids='{}',
                    final_attempt_completed_player_ids='{}',
                    final_attempt_outstanding_player_ids='{}',
                    final_attempt_page_read_started_at=NULL,
                    final_attempt_page_read_finished_at=NULL,
                    finalized_source_signature=NULL,
                    finalized_population_fingerprint=NULL,
                    finalized_row_revision_fingerprint=NULL,
                    finalized_source_floor=NULL,
                    finalized_validation_read_started_at=NULL,
                    finalized_validation_read_finished_at=NULL,
                    finalized_source_read_started_at=NULL,
                    finalized_source_read_finished_at=NULL,
                    finalized_invalidation_revision=NULL,
                    finalized_run_generation=NULL,
                    finalized_page_evidence=NULL,
                    finalized_at=NULL,
                    finalized_mandatory_manifest_complete=NULL,
                    finalized_optional_component_coverage=NULL
                WHERE (g.final_attempt_source_signature IS NOT NULL AND (
                        g.final_attempt_population_fingerprint IS NULL
                        OR g.final_attempt_population_manifest IS NULL
                        OR g.final_attempt_source_read_started_at IS NULL
                        OR g.final_attempt_initial_source_read_finished_at IS NULL
                        OR g.final_attempt_source_read_finished_at IS NULL))
                   OR (g.state='FINALIZED' AND (
                        g.final_attempt_source_signature IS NULL
                        OR g.final_attempt_population_fingerprint IS NULL
                        OR g.final_attempt_population_manifest IS NULL
                        OR g.final_attempt_source_read_started_at IS NULL
                        OR g.final_attempt_initial_source_read_finished_at IS NULL
                        OR g.final_attempt_source_read_finished_at IS NULL
                        OR g.finalized_population_fingerprint IS NULL
                        OR g.finalized_validation_read_started_at IS NULL
                        OR g.finalized_validation_read_finished_at IS NULL
                        OR g.finalized_source_read_started_at IS NULL
                        OR g.finalized_source_read_finished_at IS NULL
                        OR g.finalized_page_evidence IS NULL))
                   OR (g.state='FINALIZED' AND NOT COALESCE(
                        g.final_attempt_population_manifest ? 'sourceCatalogueMembershipRequired', FALSE))
                """.formatted(table));
    }

    /** Upgrades the pre-review publication-key-only receipt without deleting runs or evidence. */
    private void migrateReceiptIdentity() {
        Integer seasonColumns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'season'
                """, Integer.class, tables.receipt());
        if (seasonColumns != null && seasonColumns > 0) return;

        Boolean runExists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, tables.run());
        if (Boolean.TRUE.equals(runExists)) {
            for (String constraint : jdbc.queryForList("""
                    SELECT conname FROM pg_constraint
                    WHERE conrelid = ?::regclass AND confrelid = ?::regclass AND contype = 'f'
                    """, String.class, tables.run(), tables.receipt())) {
                jdbc.execute("ALTER TABLE %s DROP CONSTRAINT %s".formatted(tables.run(), constraint));
            }
        }
        jdbc.execute("ALTER TABLE %s ADD COLUMN season VARCHAR(10)".formatted(tables.receipt()));
        if (Boolean.TRUE.equals(runExists)) {
            jdbc.execute("""
                    UPDATE %s receipt SET season = COALESCE(
                        (SELECT run.season FROM %s run
                         WHERE run.generation = receipt.latest_generation),
                        (SELECT MIN(run.season) FROM %s run
                         WHERE run.publication_key = receipt.publication_key),
                        'legacy')
                    """.formatted(tables.receipt(), tables.run(), tables.run()));
        } else {
            jdbc.execute("UPDATE %s SET season = 'legacy'".formatted(tables.receipt()));
        }
        for (String constraint : jdbc.queryForList("""
                SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'p'
                """, String.class, tables.receipt())) {
            jdbc.execute("ALTER TABLE %s DROP CONSTRAINT %s".formatted(tables.receipt(), constraint));
        }
        jdbc.execute("ALTER TABLE %s ALTER COLUMN season SET NOT NULL".formatted(tables.receipt()));
        jdbc.execute("ALTER TABLE %s ADD PRIMARY KEY (season, publication_key)".formatted(tables.receipt()));
        if (Boolean.TRUE.equals(runExists)) {
            jdbc.execute("""
                    INSERT INTO %s(season, publication_key)
                    SELECT DISTINCT season, publication_key FROM %s
                    ON CONFLICT DO NOTHING
                    """.formatted(tables.receipt(), tables.run()));
        }
    }
}
