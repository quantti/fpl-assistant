package dev.quantti.fplassistant.sync;

/** Shared SQL proof that a current PFS pointer still matches its physical row and page watermark. */
public final class IncrementalPfsEvidenceSql {

    private IncrementalPfsEvidenceSql() { }

    public static String currentRowProof(String pointer, String pfs, String season,
                                         String evidence, String pageReceiptTable) {
        String prefix = IncrementalPlayerFixturePublisher.PAGE_RECEIPT_PREFIX.replace("'", "''");
        String receipt = "EXISTS (SELECT 1 FROM " + pageReceiptTable + " page_receipt"
                + " WHERE page_receipt.season=" + season + ".code AND page_receipt.publication_key='"
                + prefix + "' || " + pfs + ".player_id"
                + " AND page_receipt.latest_generation>=" + pointer + ".source_run_generation"
                + " AND page_receipt.source_read_started_at IS NOT NULL"
                + " AND page_receipt.source_read_finished_at IS NOT NULL"
                + " AND page_receipt.source_read_started_at<=page_receipt.source_read_finished_at"
                + " AND page_receipt.source_read_finished_at>=" + pointer + ".source_read_finished_at"
                + " AND (page_receipt.latest_generation>" + pointer + ".source_run_generation"
                + " OR (page_receipt.source_read_started_at=" + pointer + ".source_read_started_at"
                + " AND page_receipt.source_read_finished_at=" + pointer + ".source_read_finished_at))"
                + " AND (page_receipt.latest_generation=" + pointer + ".source_run_generation"
                + " OR page_receipt.source_read_started_at>=" + pointer + ".source_read_finished_at))";
        String physical = pointer + ".physical_fingerprint="
                + PlayerFixtureFingerprint.physicalSqlExpression(pfs, season, evidence + ".component_presence");
        return "(" + pointer + ".fixture_id IS NOT NULL AND " + pointer + ".row_state='VALID'"
                + " AND " + pointer + ".season_code=" + season + ".code"
                + " AND " + pointer + ".evidence_generation IS NOT NULL"
                + " AND " + pointer + ".evidence_generation=" + pointer + ".source_run_generation"
                + " AND " + pointer + ".source_read_started_at=" + evidence + ".source_read_started_at"
                + " AND " + pointer + ".source_read_finished_at=" + evidence + ".source_read_finished_at"
                + " AND " + evidence + ".generation=" + pointer + ".evidence_generation"
                + " AND " + receipt + " AND " + physical + ")";
    }
}
