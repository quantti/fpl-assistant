package fi.karivanttinen.fplassistant.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.TreeMap;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.Appearance;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.FixtureEvidence;

/** Compact durable receipt keys for one validated fixture-local live-appearance snapshot. */
public final class IncrementalAppearanceReceipt {

    public static final String SNAPSHOT_PREFIX = "incremental-live-appearance:fixture:";
    public static final String PLAYER_PREFIX = "incremental-live-player:fixture:";
    public static final String LIVE_EVENT_PREFIX = "incremental-live-event:";
    public static final String LIVE_EVENT_SOURCE_PREFIX = "incremental-live-event-source:";
    public static final String SOURCE_FIXTURE_PREFIX = "incremental-source-fixture:";
    public static final String APPEARANCE_STATE_PREFIX = "incremental-live-appearance-state:fixture:";

    private IncrementalAppearanceReceipt() {
    }

    public static String snapshotKey(FixtureRow fixture, int eventId, FixtureEvidence evidence,
                                     CatalogueScheduleParser.ReadInterval read) {
        Objects.requireNonNull(fixture);
        Objects.requireNonNull(evidence);
        if (!Objects.equals(fixture.gameweek(), eventId)) {
            throw new IllegalArgumentException("Live event does not match fixture gameweek");
        }
        if (!Objects.equals(evidence.liveRead(), read)) {
            throw new IllegalArgumentException("Snapshot read interval must match the appearance evidence");
        }
        return SNAPSHOT_PREFIX + fixture.id() + ":e:" + eventId
                + ":h:" + fixture.homeTeamId() + ":a:" + fixture.awayTeamId()
                + ":g:" + value(fixture.gameweek()) + ":k:" + kickoff(fixture.kickoff())
                + ":f:" + bit(fixture.finished()) + ":c:" + bit(evidence.complete())
                + ":p:" + evidence.appearances().size()
                + ":cd:" + fixture.code() + ":st:" + bit(fixture.started())
                + ":pr:" + bit(Boolean.TRUE.equals(fixture.fields().get("finished_provisional")))
                + ":m:" + fixture.fields().get("minutes")
                + ":sh:" + value(fixture.homeScore()) + ":sa:" + value(fixture.awayScore())
                + snapshotReadKey(read);
    }

    public static String liveSourceKey(int eventId, long generation, Instant startedAt, Instant finishedAt) {
        if (eventId <= 0 || generation <= 0 || startedAt == null || finishedAt == null
                || finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("Invalid live source key");
        }
        return LIVE_EVENT_SOURCE_PREFIX + eventId + ":generation:" + generation
                + ":started:" + epochMicros(startedAt) + ":finished:" + epochMicros(finishedAt);
    }

    public static String sourceFixtureKey(int fixtureId) {
        if (fixtureId <= 0) throw new IllegalArgumentException("Invalid source fixture key");
        return SOURCE_FIXTURE_PREFIX + fixtureId;
    }

    public static String appearanceStateKey(int fixtureId) {
        if (fixtureId <= 0) throw new IllegalArgumentException("Invalid appearance state key");
        return APPEARANCE_STATE_PREFIX + fixtureId;
    }

    /** Hash of this fixture's validated source revision, independent of unrelated bootstrap changes. */
    public static String fixtureRevisionFingerprint(FixtureRow fixture, Integer eventId) {
        Objects.requireNonNull(fixture);
        if (!Objects.equals(fixture.gameweek(), eventId)) {
            throw new IllegalArgumentException("Source event does not match fixture gameweek");
        }
        return sha256(FixtureVerification.verifiedHeader(fixture) + "|event:" + value(eventId)
                + "|stats:" + FixtureStatsFingerprint.of(fixture));
    }

    /** Binds the positive-appearance manifest to the exact validated fixture source revision. */
    public static String fingerprint(FixtureRow fixture, int eventId, FixtureEvidence evidence) {
        return sha256(fixtureRevisionFingerprint(fixture, eventId) + ":" + fingerprint(evidence));
    }

    public static String playerKey(int fixtureId, int playerId, Appearance appearance,
                                   CatalogueScheduleParser.ReadInterval read) {
        if (fixtureId <= 0 || playerId <= 0) throw new IllegalArgumentException("Invalid appearance key");
        Objects.requireNonNull(appearance);
        return PLAYER_PREFIX + fixtureId + ":player:" + playerId + ":side:" + appearance.side()
                + ":minutes:" + appearance.minutes() + readKey(read);
    }

    /** Stable digest used to detect a missing or altered appearance receipt row. */
    public static String fingerprint(FixtureEvidence evidence) {
        String canonical = new TreeMap<>(evidence.appearances()).entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue().side() + ":" + entry.getValue().minutes())
                .collect(java.util.stream.Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String value(Integer value) {
        return value == null ? "n" : value.toString();
    }

    private static String kickoff(Instant value) {
        return value == null ? "n" : Long.toString(epochMicros(value));
    }

    private static String snapshotReadKey(CatalogueScheduleParser.ReadInterval read) {
        Objects.requireNonNull(read);
        return ":r:" + epochMicros(read.startedAt()) + ":" + epochMicros(read.completedAt());
    }

    private static String readKey(CatalogueScheduleParser.ReadInterval read) {
        Objects.requireNonNull(read);
        return ":readstart:" + epochMicros(read.startedAt()) + ":readfinish:" + epochMicros(read.completedAt());
    }

    private static long epochMicros(Instant value) {
        return value.getEpochSecond() * 1_000_000L + (value.getNano() + 500L) / 1_000L;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
