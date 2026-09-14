package fi.karivanttinen.fplassistant.sync;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.Observation;
import fi.karivanttinen.fplassistant.sync.FixtureVerification.SourceReads;

/** Stable JSON representation of the observation needed by the two-read verification rule. */
final class FixtureObservationCodec {

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private FixtureObservationCodec() {
    }

    static String encode(Observation observation) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("seasonId", observation.seasonId());
        root.put("seasonCode", observation.seasonCode());
        root.put("fixtureId", observation.fixtureId());
        root.put("finished", observation.finished());
        root.put("coherent", observation.coherent());
        root.put("fingerprint", observation.fingerprint());
        ObjectNode reads = root.putObject("reads");
        interval(reads.putObject("fixture"), observation.reads().fixture());
        interval(reads.putObject("live"), observation.reads().live());
        ObjectNode histories = reads.putObject("histories");
        observation.reads().histories().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> interval(histories.putObject(Integer.toString(entry.getKey())), entry.getValue()));
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize fixture observation", e);
        }
    }

    static Observation decode(String value) {
        try {
            JsonNode root = JSON.readTree(value);
            if (root.path("version").intValue() != VERSION) {
                throw new IllegalArgumentException("Unsupported fixture observation version");
            }
            JsonNode reads = root.required("reads");
            Map<Integer, ReadInterval> histories = new LinkedHashMap<>();
            reads.required("histories").fields().forEachRemaining(entry ->
                    histories.put(Integer.parseInt(entry.getKey()), interval(entry.getValue())));
            return new Observation(root.required("seasonId").intValue(),
                    root.required("seasonCode").textValue(), root.required("fixtureId").intValue(),
                    root.required("finished").booleanValue(), root.required("coherent").booleanValue(),
                    root.required("fingerprint").textValue(), new SourceReads(
                            interval(reads.required("fixture")), interval(reads.required("live")), histories));
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Invalid stored fixture observation", e);
        }
    }

    private static void interval(ObjectNode node, ReadInterval interval) {
        node.put("startedAt", interval.startedAt().toString());
        node.put("completedAt", interval.completedAt().toString());
    }

    private static ReadInterval interval(JsonNode node) {
        return new ReadInterval(Instant.parse(node.required("startedAt").textValue()),
                Instant.parse(node.required("completedAt").textValue()));
    }
}
