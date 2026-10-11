package dev.quantti.fplassistant.sync;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryBatch;
import dev.quantti.fplassistant.sync.HistoryParser.HistoryRow;

/** Pure, fixture-scoped post-finish verification. It performs no publication or scheduling. */
public final class FixtureVerification {

    private static final Duration STABILITY = Duration.ofMinutes(10);
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FixtureVerification() {
    }

    /**
     * Evaluates one independently fetched source observation. The returned observation is the only
     * value a caller should retain as the baseline for the next decision.
     */
    public static Decision verify(Season season, FixtureRow fixture, ReadInterval fixtureRead,
                                  SourceDocument liveDocument, Collection<HistoryBatch> histories,
                                  Observation priorObservation, Instant asOf) {
        return verify(season, fixture, fixtureRead, liveDocument, histories, priorObservation,
                asOf, Set.of());
    }

    static Decision verify(Season season, FixtureRow fixture, ReadInterval fixtureRead,
                           SourceDocument liveDocument, Collection<HistoryBatch> histories,
                           Observation priorObservation, Instant asOf, Set<Reason> externalReasons) {
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(fixtureRead, "fixtureRead");
        Objects.requireNonNull(liveDocument, "liveDocument");
        Objects.requireNonNull(histories, "histories");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(externalReasons, "externalReasons");

        requireCanonicalIdentity(season, fixture);
        LiveManifest live = parseLive(liveDocument);
        FixtureManifest manifest = parseFixtureManifest(fixture);
        SourceReads reads = sourceReads(fixtureRead, liveDocument.readInterval(), histories);
        EnumSet<Reason> reasons = externalReasons.isEmpty()
                ? EnumSet.noneOf(Reason.class) : EnumSet.copyOf(externalReasons);
        if (manifest.stats().isEmpty() || !manifest.participantSides().containsValue(Side.HOME)
                || !manifest.participantSides().containsValue(Side.AWAY)) {
            reasons.add(Reason.MISSING_OR_INCOMPLETE_FIXTURE_MANIFEST);
        }
        validateSourceTimes(reads, asOf, reasons);
        validateFixtureState(fixture, reasons);

        Map<Integer, HistoryRow> history = fixtureHistory(season, fixture, histories, reasons);
        Map<Integer, LivePlayer> livePlayers = live.forFixture(fixture.id());
        if (livePlayers.isEmpty()) reasons.add(Reason.MISSING_LIVE_MANIFEST);
        validateCoverage(fixture, livePlayers, history, manifest, reasons);
        Set<Integer> expectedParticipants = new HashSet<>(manifest.participantSides().keySet());
        livePlayers.values().stream().filter(LivePlayer::appeared)
                .map(LivePlayer::playerId).forEach(expectedParticipants::add);
        Set<Integer> missingParticipants = new HashSet<>(expectedParticipants);
        missingParticipants.removeIf(id -> history.containsKey(id) && history.get(id).minutes() > 0);
        int receivedParticipants = expectedParticipants.size() - missingParticipants.size();

        String fingerprint = fingerprint(fixture, manifest, livePlayers, history);
        if (priorObservation != null && priorObservation.finished() && !fixture.finished()) {
            reasons.add(Reason.FINISHED_REGRESSION);
        }
        boolean coherent = reasons.isEmpty();
        Observation current = new Observation(fixture.seasonId(), fixture.seasonCode(), fixture.id(),
                fixture.finished(), coherent, fingerprint, reads);
        if (!coherent) return new Decision(State.PENDING, reasons, current,
                expectedParticipants.size(), receivedParticipants, missingParticipants);

        if (priorObservation == null) {
            reasons.add(Reason.NEEDS_SECOND_OBSERVATION);
        } else {
            requireSameFixture(current, priorObservation);
            if (!priorObservation.finished() || !priorObservation.coherent()) {
                reasons.add(Reason.NEEDS_SECOND_OBSERVATION);
            } else if (!current.reads().startedAt().isAfter(priorObservation.reads().completedAt())) {
                reasons.add(Reason.SOURCE_READ_ORDER);
            } else if (!current.fingerprint().equals(priorObservation.fingerprint())) {
                reasons.add(Reason.CHANGED_FINGERPRINT);
            } else if (current.reads().startedAt()
                    .isBefore(priorObservation.reads().completedAt().plus(STABILITY))) {
                reasons.add(Reason.STABILITY_WINDOW);
            }
        }
        return new Decision(reasons.isEmpty() ? State.VERIFIED : State.PENDING, reasons, current,
                expectedParticipants.size(), receivedParticipants, missingParticipants);
    }

    private static void requireCanonicalIdentity(Season season, FixtureRow fixture) {
        if (fixture.seasonId() != season.id() || !fixture.seasonCode().equals(season.code())
                || fixture.id() <= 0 || fixture.gameweek() == null || fixture.gameweek() <= 0
                || fixture.homeTeamId() <= 0 || fixture.awayTeamId() <= 0
                || fixture.homeTeamId() == fixture.awayTeamId()) {
            throw invalid("fixture is not a canonical fixture in configured season " + season.code());
        }
    }

    private static void validateFixtureState(FixtureRow fixture, Set<Reason> reasons) {
        Object provisional = fixture.fields().get("finished_provisional");
        Object minutes = fixture.fields().get("minutes");
        if (!(provisional instanceof Boolean) || !(minutes instanceof Integer value)
                || value < 0 || value > 130 || (fixture.homeScore() == null) != (fixture.awayScore() == null)
                || fixture.homeScore() != null && (fixture.homeScore() < 0 || fixture.awayScore() < 0)
                || fixture.finished() && (!fixture.started() || fixture.homeScore() == null || value < 90)
                || !fixture.started() && (fixture.finished() || Boolean.TRUE.equals(provisional)
                        || value != 0 || fixture.homeScore() != null)) {
            reasons.add(Reason.FIXTURE_STATE_CONTRADICTION);
        }
        // finished_provisional is retained in the fingerprint, but never satisfies this gate.
        if (!fixture.finished()) reasons.add(Reason.FIXTURE_NOT_FINISHED);
    }

    private static Map<Integer, HistoryRow> fixtureHistory(Season season, FixtureRow fixture,
                                                            Collection<HistoryBatch> batches,
                                                            Set<Reason> reasons) {
        Map<Integer, HistoryRow> rows = new HashMap<>();
        Set<Integer> batchesSeen = new HashSet<>();
        for (HistoryBatch batch : batches) {
            Objects.requireNonNull(batch, "history batch");
            if (batch.seasonId() != season.id() || !batch.seasonCode().equals(season.code())) {
                reasons.add(Reason.HISTORY_IDENTITY_CONTRADICTION);
                continue;
            }
            if (!batchesSeen.add(batch.playerId())) reasons.add(Reason.DUPLICATE_HISTORY);
            for (HistoryRow row : batch.rows()) {
                if (row.fixtureId() != fixture.id()) continue;
                int expectedOpponent = row.wasHome() ? fixture.awayTeamId() : fixture.homeTeamId();
                int expectedTeam = row.wasHome() ? fixture.homeTeamId() : fixture.awayTeamId();
                if (row.playerId() != batch.playerId() || row.seasonId() != season.id()
                        || !row.seasonCode().equals(season.code()) || row.gameweek() != fixture.gameweek()
                        || row.playerTeamId() != expectedTeam || row.opponentTeamId() != expectedOpponent
                        || !Objects.equals(row.kickoffTime(), fixture.kickoff())) {
                    reasons.add(Reason.HISTORY_IDENTITY_CONTRADICTION);
                }
                if (rows.putIfAbsent(row.playerId(), row) != null) reasons.add(Reason.DUPLICATE_HISTORY);
            }
        }
        return Map.copyOf(rows);
    }

    private static void validateCoverage(FixtureRow fixture, Map<Integer, LivePlayer> live,
                                         Map<Integer, HistoryRow> history, FixtureManifest manifest,
                                         Set<Reason> reasons) {
        boolean home = false;
        boolean away = false;
        for (LivePlayer player : live.values()) {
            HistoryRow row = history.get(player.playerId());
            if (player.minutes() > 0) {
                if (row == null) {
                    reasons.add(Reason.MISSING_HISTORY);
                    continue;
                }
                if (row.minutes() != player.minutes()) reasons.add(Reason.LIVE_HISTORY_MINUTES_MISMATCH);
                home |= row.playerTeamId() == fixture.homeTeamId();
                away |= row.playerTeamId() == fixture.awayTeamId();
            }
        }
        for (HistoryRow row : history.values()) {
            LivePlayer player = live.get(row.playerId());
            if (row.minutes() > 0 && player == null) reasons.add(Reason.MISSING_LIVE_PLAYER);
            if (row.minutes() > 0 && player != null && player.minutes() <= 0) {
                reasons.add(Reason.LIVE_HISTORY_MINUTES_MISMATCH);
            }
        }
        if (!home || !away) reasons.add(Reason.BOTH_SIDES_NOT_EVIDENCED);

        for (Map.Entry<Integer, Side> participant : manifest.participantSides().entrySet()) {
            LivePlayer livePlayer = live.get(participant.getKey());
            HistoryRow row = history.get(participant.getKey());
            if (livePlayer == null || row == null) {
                reasons.add(Reason.FIXTURE_STAT_PARTICIPANT_MISSING);
                continue;
            }
            Side historySide = row.playerTeamId() == fixture.homeTeamId() ? Side.HOME : Side.AWAY;
            if (historySide != participant.getValue()) reasons.add(Reason.FIXTURE_STAT_SIDE_MISMATCH);
        }
    }

    /** Player ids explicitly present in this fixture's own source stats. */
    public static Set<Integer> fixtureStatParticipantIds(FixtureRow fixture) {
        Objects.requireNonNull(fixture);
        return Set.copyOf(parseFixtureManifest(fixture).participantSides().keySet());
    }

    /**
     * Builds fixture-local live-minute and fixture-stat side evidence. Complete requires the live
     * positive-minute roster to equal the positive-stat contributors, every zero-only stats identity
     * to have an explicit zero-minute live explanation, mapped sides on both clubs, and a nonempty
     * fixture-stats capture. It does not certify API-wide or league-wide population completeness.
     */
    public static Map<Integer, FixtureEvidence> fixtureEvidenceByFixture(
            Collection<FixtureRow> fixtures, SourceDocument liveDocument) {
        Objects.requireNonNull(fixtures);
        Objects.requireNonNull(liveDocument);
        LiveManifest live = parseLive(liveDocument);
        Map<Integer, FixtureEvidence> result = new HashMap<>();
        for (FixtureRow fixture : fixtures) {
            Objects.requireNonNull(fixture);
            FixtureManifest stats = parseFixtureManifest(fixture);
            Map<Integer, Appearance> appearances = new HashMap<>();
            Map<Integer, LivePlayer> livePlayers = live.forFixture(fixture.id());
            Set<Integer> positivePlayers = new HashSet<>();
            Set<Integer> positiveStatsPlayers = new HashSet<>();
            for (FixtureStat stat : stats.stats()) {
                if (stat.value() > 0) positiveStatsPlayers.add(stat.playerId());
            }
            for (LivePlayer player : livePlayers.values()) {
                if (!player.appeared()) continue;
                positivePlayers.add(player.playerId());
                Side side = stats.participantSides().get(player.playerId());
                if (side != null) appearances.put(player.playerId(), new Appearance(side, player.minutes()));
            }
            boolean sourceSides = stats.participantSides().containsValue(Side.HOME)
                    && stats.participantSides().containsValue(Side.AWAY);
            boolean bothLiveSides = appearances.values().stream().anyMatch(row -> row.side() == Side.HOME)
                    && appearances.values().stream().anyMatch(row -> row.side() == Side.AWAY);
            boolean zeroOnlyExplained = stats.participantSides().keySet().stream().allMatch(playerId -> {
                LivePlayer player = livePlayers.get(playerId);
                return player != null && (positiveStatsPlayers.contains(playerId)
                        ? player.appeared() : !player.appeared());
            });
            boolean complete = !stats.stats().isEmpty() && sourceSides && bothLiveSides && zeroOnlyExplained
                    && appearances.keySet().equals(positivePlayers)
                    && positiveStatsPlayers.equals(positivePlayers);
            FixtureEvidence evidence = new FixtureEvidence(complete, positivePlayers, appearances,
                    liveDocument.readInterval());
            if (result.putIfAbsent(fixture.id(), evidence) != null) {
                throw invalid("duplicate fixture " + fixture.id() + " in event participant selection");
            }
        }
        return Map.copyOf(result);
    }

    /** Player ids from fixture stats and positive fixture-specific live explanations. */
    public static Map<Integer, Set<Integer>> participantIdsByFixture(
            Collection<FixtureRow> fixtures, SourceDocument liveDocument) {
        Objects.requireNonNull(fixtures);
        Objects.requireNonNull(liveDocument);
        LiveManifest live = parseLive(liveDocument);
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (FixtureRow fixture : fixtures) {
            Objects.requireNonNull(fixture);
            Set<Integer> ids = new HashSet<>(parseFixtureManifest(fixture).participantSides().keySet());
            live.forFixture(fixture.id()).values().stream().filter(LivePlayer::appeared)
                    .map(LivePlayer::playerId).forEach(ids::add);
            if (result.putIfAbsent(fixture.id(), Set.copyOf(ids)) != null) {
                throw invalid("duplicate fixture " + fixture.id() + " in event participant selection");
            }
        }
        return Map.copyOf(result);
    }

    /** Player ids explicitly present for this fixture in live explanations or fixture stats. */
    public static Set<Integer> participantIds(FixtureRow fixture, SourceDocument liveDocument) {
        Objects.requireNonNull(fixture);
        Objects.requireNonNull(liveDocument);
        Set<Integer> ids = new HashSet<>(parseFixtureManifest(fixture).participantSides().keySet());
        ids.addAll(parseLive(liveDocument).forFixture(fixture.id()).keySet());
        return Set.copyOf(ids);
    }

    private static LiveManifest parseLive(SourceDocument document) {
        JsonNode root = read(document.bytes(), "live event");
        if (!root.isObject()) throw invalid("live event root must be an object");
        JsonNode elements = requiredArray(root, "elements", "live event");
        Map<Integer, Map<Integer, LivePlayer>> fixtures = new HashMap<>();
        Set<Integer> players = new HashSet<>();
        for (JsonNode element : elements) {
            int playerId = positiveInt(element, "id", "live element");
            if (!players.add(playerId)) throw invalid("duplicate live element " + playerId);
            JsonNode explanations = requiredArray(element, "explain", "live element " + playerId);
            Set<Integer> playerFixtures = new HashSet<>();
            for (JsonNode explanation : explanations) {
                int fixtureId = positiveInt(explanation, "fixture", "live element " + playerId);
                if (!playerFixtures.add(fixtureId)) {
                    throw invalid("duplicate fixture " + fixtureId + " for live element " + playerId);
                }
                List<RawLiveStat> stats = parseLiveStats(explanation, playerId, fixtureId);
                int minutes = stats.stream().filter(stat -> stat.identifier().equals("minutes"))
                        .findFirst().orElseThrow(() -> invalid("missing minutes for live element "
                                + playerId + " fixture " + fixtureId)).value();
                if (minutes < 0 || minutes > 130) throw invalid("minutes out of range for live element " + playerId);
                fixtures.computeIfAbsent(fixtureId, ignored -> new HashMap<>())
                        .put(playerId, new LivePlayer(playerId, minutes, stats));
            }
        }
        Map<Integer, Map<Integer, LivePlayer>> immutable = new HashMap<>();
        fixtures.forEach((id, rows) -> immutable.put(id, Map.copyOf(rows)));
        return new LiveManifest(Map.copyOf(immutable));
    }

    private static List<RawLiveStat> parseLiveStats(JsonNode explanation, int playerId, int fixtureId) {
        JsonNode nodes = requiredArray(explanation, "stats", "live explanation");
        Set<String> identifiers = new HashSet<>();
        List<RawLiveStat> result = new ArrayList<>();
        for (JsonNode stat : nodes) {
            if (!stat.isObject()) throw invalid("live explanation stat must be an object");
            String identifier = requiredText(stat, "identifier", "live explanation stat");
            if (!identifiers.add(identifier)) throw invalid("duplicate " + identifier + " for live element "
                    + playerId + " fixture " + fixtureId);
            result.add(new RawLiveStat(identifier, exactInt(stat, "value", identifier),
                    exactInt(stat, "points", identifier),
                    exactInt(stat, "points_modification", identifier)));
        }
        result.sort(Comparator.comparing(RawLiveStat::identifier));
        return List.copyOf(result);
    }

    private static FixtureManifest parseFixtureManifest(FixtureRow fixture) {
        Object raw = fixture.fields().get("stats");
        if (!(raw instanceof String json)) throw invalid("fixture stats manifest is missing");
        JsonNode stats = read(json.getBytes(StandardCharsets.UTF_8), "fixture stats manifest");
        if (!stats.isArray()) throw invalid("fixture stats manifest must be an array");
        Set<String> identifiers = new HashSet<>();
        Map<Integer, Side> participants = new HashMap<>();
        List<FixtureStat> result = new ArrayList<>();
        for (JsonNode stat : stats) {
            String identifier = requiredText(stat, "identifier", "fixture stat");
            if (!identifiers.add(identifier)) throw invalid("duplicate fixture stat " + identifier);
            for (Side side : Side.values()) {
                JsonNode values = requiredArray(stat, side == Side.HOME ? "h" : "a", "fixture " + identifier);
                Set<Integer> sideSeen = new HashSet<>();
                for (JsonNode value : values) {
                    int player = positiveInt(value, "element", "fixture " + identifier);
                    int amount = exactInt(value, "value", "fixture " + identifier);
                    if (!sideSeen.add(player)) throw invalid("duplicate fixture stat participant");
                    Side previous = participants.putIfAbsent(player, side);
                    if (previous != null && previous != side) throw invalid("fixture stat participant appears on both sides");
                    result.add(new FixtureStat(identifier, side, player, amount));
                }
            }
        }
        result.sort(Comparator.comparing(FixtureStat::identifier).thenComparing(FixtureStat::side)
                .thenComparingInt(FixtureStat::playerId));
        return new FixtureManifest(Map.copyOf(participants), List.copyOf(result));
    }

    private static SourceReads sourceReads(ReadInterval fixture, ReadInterval live,
                                           Collection<HistoryBatch> histories) {
        Map<Integer, ReadInterval> history = new LinkedHashMap<>();
        for (HistoryBatch batch : histories) {
            ReadInterval previous = history.putIfAbsent(batch.playerId(), batch.sourceRead());
            if (previous != null && !previous.equals(batch.sourceRead())) {
                // Kept detectable by fixtureHistory; use the widest interval without hiding either read.
                history.put(batch.playerId(), combined(previous, batch.sourceRead()));
            }
        }
        return new SourceReads(fixture, live, history);
    }

    private static void validateSourceTimes(SourceReads reads, Instant asOf, Set<Reason> reasons) {
        if (reads.completedAt().isAfter(asOf)) reasons.add(Reason.SOURCE_FROM_FUTURE);
        if (!reads.live().startedAt().isAfter(reads.fixture().completedAt())
                || reads.histories().values().stream()
                        .anyMatch(read -> !read.startedAt().isAfter(reads.fixture().completedAt()))) {
            reasons.add(Reason.EVIDENCE_NOT_POST_FINISH);
        }
    }

    /** Stable header for invalidating verified evidence before a schedule correction is written. */
    public static String verifiedHeader(FixtureRow fixture) {
        Objects.requireNonNull(fixture, "fixture");
        return verifiedHeader(fixture.seasonId(), fixture.seasonCode(), fixture.id(), fixture.code(),
                fixture.gameweek(), fixture.homeTeamId(), fixture.awayTeamId(), fixture.kickoff(),
                fixture.started(), fixture.finished(), fixture.fields().get("finished_provisional"),
                fixture.fields().get("minutes"), fixture.homeScore(), fixture.awayScore());
    }

    static String verifiedHeader(int seasonId, String seasonCode, int id, int code, Integer gameweek,
                                 int homeTeamId, int awayTeamId, Instant kickoff, Object started,
                                 Object finished, Object provisional, Object minutes,
                                 Integer homeScore, Integer awayScore) {
        return new Digest().add(seasonId).add(seasonCode).add(id).add(code).add(gameweek)
                .add(homeTeamId).add(awayTeamId).add(kickoff).add(started).add(finished)
                .add(provisional).add(minutes).add(homeScore).add(awayScore).hex();
    }

    private static String fingerprint(FixtureRow fixture, FixtureManifest manifest,
                                      Map<Integer, LivePlayer> live, Map<Integer, HistoryRow> history) {
        Digest digest = new Digest();
        digest.add(fixture.seasonId()).add(fixture.seasonCode()).add(fixture.id()).add(fixture.code())
                .add(fixture.gameweek()).add(fixture.homeTeamId()).add(fixture.awayTeamId())
                .add(fixture.kickoff()).add(fixture.started()).add(fixture.finished())
                .add(fixture.fields().get("finished_provisional")).add(fixture.fields().get("minutes"))
                .add(fixture.homeScore()).add(fixture.awayScore());
        manifest.stats().forEach(stat -> digest.add(stat.identifier()).add(stat.side())
                .add(stat.playerId()).add(stat.value()));
        live.values().stream().sorted(Comparator.comparingInt(LivePlayer::playerId)).forEach(player -> {
            digest.add(player.playerId()).add(player.minutes());
            player.rawStats().forEach(stat -> digest.add(stat.identifier()).add(stat.value())
                    .add(stat.points()).add(stat.pointsModification()));
        });
        history.values().stream().sorted(Comparator.comparingInt(HistoryRow::playerId))
                .forEach(row -> addHistory(digest, row));
        return digest.hex();
    }

    private static void addHistory(Digest d, HistoryRow r) {
        d.add(r.seasonId()).add(r.seasonCode()).add(r.fixtureId()).add(r.playerId()).add(r.gameweek())
                .add(r.playerTeamId()).add(r.opponentTeamId()).add(r.wasHome()).add(r.kickoffTime())
                .add(r.minutes()).add(r.totalPoints()).add(r.bonus()).add(r.bps()).add(r.goalsScored())
                .add(r.assists()).add(r.expectedGoals()).add(r.expectedAssists())
                .add(r.expectedGoalInvolvements()).add(r.cleanSheets()).add(r.goalsConceded())
                .add(r.ownGoals()).add(r.penaltiesSaved()).add(r.penaltiesMissed()).add(r.saves())
                .add(r.expectedGoalsConceded()).add(r.yellowCards()).add(r.redCards())
                .add(r.influence()).add(r.creativity()).add(r.threat()).add(r.ictIndex())
                .add(r.value()).add(r.selected()).add(r.transfersIn()).add(r.transfersOut()).add(r.starts())
                .add(r.clearancesBlocksInterceptions()).add(r.recoveries()).add(r.tackles())
                .add(r.defensiveContribution()).add(r.presenceMask());
    }

    private static void requireSameFixture(Observation current, Observation prior) {
        Objects.requireNonNull(prior, "priorObservation");
        if (current.seasonId() != prior.seasonId() || !current.seasonCode().equals(prior.seasonCode())
                || current.fixtureId() != prior.fixtureId()) {
            throw new IllegalArgumentException("Prior observation belongs to another fixture");
        }
    }

    private static ReadInterval combined(ReadInterval first, ReadInterval second) {
        Instant start = first.startedAt().isBefore(second.startedAt()) ? first.startedAt() : second.startedAt();
        Instant end = first.completedAt().isAfter(second.completedAt()) ? first.completedAt() : second.completedAt();
        return new ReadInterval(start, end);
    }

    private static JsonNode read(byte[] bytes, String context) {
        if (bytes.length == 0) throw invalid(context + " body is empty");
        try {
            JsonNode value = JSON.readTree(bytes);
            if (value == null || value.isNull()) throw invalid(context + " root is empty");
            return value;
        } catch (IOException e) {
            throw invalid("malformed " + context + " JSON", e);
        }
    }

    private static JsonNode requiredArray(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) throw invalid("missing or non-array " + field + " in " + context);
        return value;
    }

    private static int positiveInt(JsonNode node, String field, String context) {
        int value = exactInt(node, field, context);
        if (value <= 0) throw invalid(field + " must be positive in " + context);
        return value;
    }

    private static int exactInt(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid("missing or non-integer " + field + " in " + context);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field, String context) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid("missing or non-text " + field + " in " + context);
        }
        return value.textValue();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid FPL fixture verification payload: " + message);
    }

    private static IllegalArgumentException invalid(String message, Exception cause) {
        return new IllegalArgumentException("Invalid FPL fixture verification payload: " + message, cause);
    }

    public enum State { PENDING, VERIFIED }

    public enum Reason {
        FIXTURE_NOT_FINISHED,
        FIXTURE_STATE_CONTRADICTION,
        SOURCE_FROM_FUTURE,
        SOURCE_READ_ORDER,
        EVIDENCE_NOT_POST_FINISH,
        MISSING_LIVE_MANIFEST,
        MISSING_HISTORY,
        DUPLICATE_HISTORY,
        HISTORY_IDENTITY_CONTRADICTION,
        MISSING_LIVE_PLAYER,
        LIVE_HISTORY_MINUTES_MISMATCH,
        BOTH_SIDES_NOT_EVIDENCED,
        FIXTURE_STAT_PARTICIPANT_MISSING,
        FIXTURE_STAT_SIDE_MISMATCH,
        NEEDS_SECOND_OBSERVATION,
        STABILITY_WINDOW,
        CHANGED_FINGERPRINT,
        FINISHED_REGRESSION,
        MISSING_OR_INCOMPLETE_FIXTURE_MANIFEST,
        MISSING_DATABASE_FIXTURE,
        DATABASE_FIXTURE_MISMATCH,
        UNKNOWN_DATABASE_PLAYER,
        UNRESOLVED_PARENT
    }

    public record RawLiveStat(String identifier, int value, int points, int pointsModification) {
        public RawLiveStat { Objects.requireNonNull(identifier); }
    }

    public record LivePlayer(int playerId, int minutes, List<RawLiveStat> rawStats) {
        public LivePlayer { rawStats = List.copyOf(rawStats); }
        public boolean appeared() { return minutes > 0; }
    }

    public record SourceReads(ReadInterval fixture, ReadInterval live,
                              Map<Integer, ReadInterval> histories) {
        public SourceReads {
            Objects.requireNonNull(fixture);
            Objects.requireNonNull(live);
            histories = Map.copyOf(histories);
        }
        public Instant startedAt() {
            Instant result = fixture.startedAt().isBefore(live.startedAt()) ? fixture.startedAt() : live.startedAt();
            for (ReadInterval interval : histories.values()) if (interval.startedAt().isBefore(result)) result = interval.startedAt();
            return result;
        }
        public Instant completedAt() {
            Instant result = fixture.completedAt().isAfter(live.completedAt()) ? fixture.completedAt() : live.completedAt();
            for (ReadInterval interval : histories.values()) if (interval.completedAt().isAfter(result)) result = interval.completedAt();
            return result;
        }
    }

    public record Observation(int seasonId, String seasonCode, int fixtureId, boolean finished,
                              boolean coherent, String fingerprint, SourceReads reads) {
        public Observation {
            if (seasonId <= 0 || seasonCode == null || fixtureId <= 0 || fingerprint == null
                    || fingerprint.length() != 64) throw new IllegalArgumentException("Invalid observation");
            Objects.requireNonNull(reads);
        }
    }

    public record Decision(State state, Set<Reason> reasons, Observation observation,
                           int expectedParticipants, int receivedParticipants,
                           Set<Integer> missingParticipantIds) {
        public Decision {
            Objects.requireNonNull(state);
            reasons = Set.copyOf(reasons);
            Objects.requireNonNull(observation);
            missingParticipantIds = Set.copyOf(missingParticipantIds);
            if ((state == State.VERIFIED) != reasons.isEmpty()
                    || expectedParticipants < 0 || receivedParticipants < 0
                    || receivedParticipants > expectedParticipants
                    || missingParticipantIds.size() != expectedParticipants - receivedParticipants
                    || state == State.VERIFIED && !missingParticipantIds.isEmpty()) {
                throw new IllegalArgumentException("Verified decision, reasons or participant counts disagree");
            }
        }
    }

    public enum Side { HOME, AWAY }

    public record Appearance(Side side, int minutes) {
        public Appearance {
            Objects.requireNonNull(side);
            if (minutes <= 0 || minutes > 130) throw new IllegalArgumentException("Invalid appeared minutes");
        }
    }

    public record FixtureEvidence(boolean complete, Set<Integer> positivePlayerIds,
                                  Map<Integer, Appearance> appearances, ReadInterval liveRead) {
        public FixtureEvidence {
            positivePlayerIds = Set.copyOf(positivePlayerIds);
            appearances = Map.copyOf(appearances);
            Objects.requireNonNull(liveRead);
            if (positivePlayerIds.stream().anyMatch(id -> id == null || id <= 0)
                    || !positivePlayerIds.containsAll(appearances.keySet())
                    || complete && !appearances.keySet().equals(positivePlayerIds)) {
                throw new IllegalArgumentException("Invalid fixture appearance evidence");
            }
        }
    }

    private record FixtureStat(String identifier, Side side, int playerId, int value) { }
    private record FixtureManifest(Map<Integer, Side> participantSides, List<FixtureStat> stats) { }
    private record LiveManifest(Map<Integer, Map<Integer, LivePlayer>> fixtures) {
        Map<Integer, LivePlayer> forFixture(int fixtureId) { return fixtures.getOrDefault(fixtureId, Map.of()); }
    }

    private static final class Digest {
        private final MessageDigest digest;
        private Digest() {
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
        private Digest add(Object value) {
            if (value instanceof java.math.BigDecimal decimal) value = decimal.stripTrailingZeros().toPlainString();
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
            return this;
        }
        private String hex() { return java.util.HexFormat.of().formatHex(digest.digest()); }
    }
}
