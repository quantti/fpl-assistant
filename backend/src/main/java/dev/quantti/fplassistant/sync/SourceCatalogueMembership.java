package dev.quantti.fplassistant.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.PlayerRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import dev.quantti.fplassistant.sync.OwnedSyncStore.SourceRead;

/** Compact membership from one parser-validated bootstrap, bound to its published source pair. */
record SourceCatalogueMembership(int seasonId, String seasonCode, long sourceGeneration,
                                SourceRead sourceRead, String sourcePairFingerprint,
                                String membershipFingerprint, List<PlayerClub> players) {

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    SourceCatalogueMembership {
        if (seasonId <= 0 || seasonCode == null || seasonCode.isBlank() || sourceGeneration <= 0
                || !validHash(sourcePairFingerprint) || !validHash(membershipFingerprint)) {
            throw new IllegalArgumentException("Invalid source catalogue membership provenance");
        }
        Objects.requireNonNull(sourceRead);
        players = List.copyOf(players);
        if (players.isEmpty()) throw new IllegalArgumentException("Source catalogue membership is empty");
    }

    static String from(Snapshot snapshot, long sourceGeneration, String sourcePairFingerprint,
                       SourceRead sourceRead) {
        Objects.requireNonNull(snapshot);
        Instant expectedStarted = snapshot.bootstrapRead().startedAt().isBefore(snapshot.fixturesRead().startedAt())
                ? snapshot.bootstrapRead().startedAt() : snapshot.fixturesRead().startedAt();
        Instant expectedFinished = snapshot.bootstrapRead().completedAt().isAfter(snapshot.fixturesRead().completedAt())
                ? snapshot.bootstrapRead().completedAt() : snapshot.fixturesRead().completedAt();
        if (snapshot.season().id() <= 0 || sourceGeneration <= 0
                || !validHash(sourcePairFingerprint)
                || !sourceRead.equals(new SourceRead(expectedStarted, expectedFinished))) {
            throw new IllegalArgumentException("Invalid source catalogue publication proof");
        }
        List<PlayerClub> players = snapshot.players().stream()
                .map(player -> new PlayerClub(player.id(), player.teamId()))
                .sorted(Comparator.comparingInt(PlayerClub::playerId)).toList();
        String membershipFingerprint = fingerprint(players);

        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("seasonId", snapshot.season().id());
        root.put("seasonCode", snapshot.season().code());
        root.put("sourceGeneration", sourceGeneration);
        root.put("sourceReadStartedAt", sourceRead.startedAt().toString());
        root.put("sourceReadFinishedAt", sourceRead.finishedAt().toString());
        root.put("sourcePairFingerprint", sourcePairFingerprint);
        root.put("membershipFingerprint", membershipFingerprint);
        ArrayNode entries = root.putArray("players");
        for (PlayerClub player : players) {
            ArrayNode entry = entries.addArray();
            entry.add(player.playerId());
            entry.add(player.teamId());
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not encode source catalogue membership", impossible);
        }
    }

    static Optional<SourceCatalogueMembership> parse(String json, int expectedSeasonId,
                                                      String expectedSeasonCode, long expectedGeneration,
                                                      SourceRead expectedRead, String expectedPairFingerprint) {
        if (json == null) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isObject() || root.path("version").intValue() != VERSION
                    || root.path("seasonId").intValue() != expectedSeasonId
                    || !root.path("seasonCode").asText().equals(expectedSeasonCode)
                    || root.path("sourceGeneration").longValue() != expectedGeneration
                    || !Instant.parse(root.path("sourceReadStartedAt").asText()).equals(expectedRead.startedAt())
                    || !Instant.parse(root.path("sourceReadFinishedAt").asText()).equals(expectedRead.finishedAt())
                    || !root.path("sourcePairFingerprint").asText().equals(expectedPairFingerprint)
                    || !validHash(root.path("membershipFingerprint").asText())) return Optional.empty();
            JsonNode sourcePlayers = root.path("players");
            if (!sourcePlayers.isArray() || sourcePlayers.isEmpty()) return Optional.empty();
            List<PlayerClub> players = new ArrayList<>(sourcePlayers.size());
            Set<Integer> ids = new HashSet<>();
            int previousId = 0;
            for (JsonNode entry : sourcePlayers) {
                if (!entry.isArray() || entry.size() != 2
                        || !entry.get(0).canConvertToInt() || !entry.get(1).canConvertToInt()) {
                    return Optional.empty();
                }
                int playerId = entry.get(0).intValue();
                int teamId = entry.get(1).intValue();
                if (playerId <= previousId || teamId <= 0 || !ids.add(playerId)) return Optional.empty();
                previousId = playerId;
                players.add(new PlayerClub(playerId, teamId));
            }
            String fingerprint = fingerprint(players);
            if (!fingerprint.equals(root.path("membershipFingerprint").asText())) return Optional.empty();
            return Optional.of(new SourceCatalogueMembership(expectedSeasonId, expectedSeasonCode,
                    expectedGeneration, expectedRead, expectedPairFingerprint, fingerprint, players));
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    static List<PlayerRow> asPlayerRows(SourceCatalogueMembership membership) {
        return membership.players().stream().map(player -> new PlayerRow(membership.seasonId(),
                membership.seasonCode(), player.playerId(), player.teamId(), "", 1, 1, java.util.Map.of())).toList();
    }

    private static String fingerprint(List<PlayerClub> players) {
        String canonical = players.stream().sorted(Comparator.comparingInt(PlayerClub::playerId))
                .map(player -> player.playerId() + ":" + player.teamId())
                .collect(java.util.stream.Collectors.joining("|"));
        return OwnedSyncStore.sha256(canonical);
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    record PlayerClub(int playerId, int teamId) {
        PlayerClub {
            if (playerId <= 0 || teamId <= 0) throw new IllegalArgumentException("Invalid player club");
        }
    }
}
