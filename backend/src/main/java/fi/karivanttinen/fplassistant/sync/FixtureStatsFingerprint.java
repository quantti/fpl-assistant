package fi.karivanttinen.fplassistant.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.karivanttinen.fplassistant.sync.CatalogueScheduleParser.FixtureRow;

/** Semantic fingerprint of the fixture source's unordered stats manifest. */
final class FixtureStatsFingerprint {

    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private FixtureStatsFingerprint() {
    }

    static String of(FixtureRow fixture) {
        Object raw = fixture.fields().get("stats");
        if (!(raw instanceof String value)) {
            throw new IllegalArgumentException("fixture stats manifest is missing");
        }
        return of(value);
    }

    static String of(String value) {
        try {
            JsonNode stats = JSON.readTree(value);
            if (stats == null || !stats.isArray()) {
                throw new IllegalArgumentException("fixture stats manifest must be an array");
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical(stats).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("invalid fixture stats manifest", e);
        }
    }

    private static String canonical(JsonNode node) throws JsonProcessingException {
        if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
            fields.sort(Map.Entry.comparingByKey());
            List<String> encoded = new ArrayList<>(fields.size());
            for (Map.Entry<String, JsonNode> field : fields) {
                encoded.add(JSON.writeValueAsString(field.getKey()) + ":" + canonical(field.getValue()));
            }
            return "{" + String.join(",", encoded) + "}";
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>(node.size());
            for (JsonNode value : node) values.add(canonical(value));
            values.sort(Comparator.naturalOrder());
            return "[" + String.join(",", values) + "]";
        }
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        return JSON.writeValueAsString(node);
    }
}
