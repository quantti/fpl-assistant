package fi.karivanttinen.fplassistant.sync;

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
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    generation BIGINT PRIMARY KEY DEFAULT nextval('%s_generation_seq'),
                    kind VARCHAR(40) NOT NULL,
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
                    failed_at TIMESTAMPTZ,
                    completed_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL,
                    CONSTRAINT fk_%s_receipt FOREIGN KEY (season, publication_key)
                        REFERENCES %s(season, publication_key),
                    CONSTRAINT ck_%s_state CHECK (state IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED')),
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
                    body BYTEA NOT NULL,
                    staged_at TIMESTAMPTZ NOT NULL,
                    CONSTRAINT ck_%s_validation CHECK (validation_state IN ('UNCHECKED', 'VALID', 'INVALID')),
                    CONSTRAINT ck_%s_read_interval CHECK (read_started_at <= read_finished_at),
                    CONSTRAINT ck_%s_body_size CHECK (octet_length(body) <= %d)
                )
                """.formatted(tables.payload(), tables.run(), tables.payload(),
                tables.payload(), tables.payload(), MAX_PAYLOAD_BYTES));
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
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_%s_run ON %s(run_generation)"
                .formatted(tables.payload(), tables.payload()));
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_%s_id_run ON %s(id, run_generation)"
                .formatted(tables.payload(), tables.payload()));
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
                        fpl_sync_required_ids_valid(required_player_ids))
                )
                """.formatted(tables.fixtureDependencyManifest(), tables.fixtureDependencyManifest(),
                tables.run(), tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest(), tables.payload(),
                tables.fixtureDependencyManifest()));

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
                """.formatted(tables.fixtureSync(), tables.run(), tables.run(), tables.run(),
                tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync(), tables.fixtureSync()));
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
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS data_checked_transition_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS last_data_checked BOOLEAN NOT NULL DEFAULT FALSE"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS missing_player_ids INTEGER[] NOT NULL DEFAULT '{}'"
                .formatted(tables.fixtureSync()));
        jdbc.execute("ALTER TABLE %s ADD COLUMN IF NOT EXISTS next_due_at TIMESTAMPTZ"
                .formatted(tables.fixtureSync()));
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
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.plannerSlot()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.plannerRuntime()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.alert()));
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
