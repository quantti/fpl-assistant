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
                    state VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                    attempt_count INTEGER NOT NULL DEFAULT 0,
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

        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.run()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.payload()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.receipt()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.requestState()));
        jdbc.execute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(tables.requestAttempt()));
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
