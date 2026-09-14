package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import dev.quantti.fplassistant.sync.CatalogueScheduleParser.FixtureRow;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.ReadInterval;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Season;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.Snapshot;
import dev.quantti.fplassistant.sync.CatalogueScheduleParser.SourceDocument;

class CatalogueScheduleParserTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Season SEASON = new Season(27, "2026-27",
            LocalDate.of(2026, 8, 21), null);
    private static final ReadInterval FIXTURE_READ = new ReadInterval(
            Instant.parse("2026-09-13T17:45:06.445472Z"),
            Instant.parse("2026-09-13T17:45:06.593102Z"));
    private static final ReadInterval BOOTSTRAP_READ = new ReadInterval(
            Instant.parse("2026-09-13T17:45:07.693745Z"),
            Instant.parse("2026-09-13T17:45:07.896876Z"));

    @Test
    void mapsCapturedCatalogueAndCompleteScheduleToCompatibilityAndCaptureRows() throws IOException {
        Snapshot snapshot = captured();

        assertThat(snapshot.season()).isEqualTo(SEASON);
        assertThat(snapshot.teams()).hasSize(20);
        assertThat(snapshot.players()).hasSize(657);
        assertThat(snapshot.dailyPrices()).hasSize(657);
        assertThat(snapshot.gameweeks()).hasSize(38);
        assertThat(snapshot.fixtures()).hasSize(380);
        assertThat(snapshot.gameweeks()).extracting(row -> row.id()).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 38).boxed().toList());
        assertThat(snapshot.gameweeks()).filteredOn(row -> row.current()).hasSize(1);

        var player = snapshot.players().stream().filter(row -> row.id() == 1).findFirst().orElseThrow();
        assertThat(player.seasonId()).isEqualTo(27);
        assertThat(player.seasonCode()).isEqualTo("2026-27");
        assertThat(player.fields()).containsKeys("total_points", "expected_goals", "ict_index", "form");
        assertThat(player.fields().get("expected_goals")).isInstanceOf(BigDecimal.class);
        assertThat(snapshot.dailyPrices().stream().filter(row -> row.elementId() == 1).findFirst().orElseThrow()
                .sourceRead()).isEqualTo(BOOTSTRAP_READ);

        FixtureRow finished = snapshot.fixtures().stream().filter(row -> row.id() == 1)
                .findFirst().orElseThrow();
        assertThat(finished.finished()).isTrue();
        assertThat(finished.homeScore()).isEqualTo(3);
        assertThat(finished.fields().get("finished_provisional")).isEqualTo(true);
        assertThat((String) finished.fields().get("stats")).contains("\"identifier\":\"goals_scored\"");
        assertThat(snapshot.bootstrapRead()).isEqualTo(BOOTSTRAP_READ);
        assertThat(snapshot.fixturesRead()).isEqualTo(FIXTURE_READ);
    }

    @Test
    void optionalNullOrMissingValuesStayNullWhileSourceZeroStaysZero() throws IOException {
        ObjectNode bootstrap = bootstrap();
        ObjectNode element = (ObjectNode) bootstrap.withArray("elements").get(0);
        element.remove("expected_goals");
        element.putNull("news_added");
        element.put("form", "0.0");

        Snapshot snapshot = parse(bytes(bootstrap), fixtureBytes(), SEASON);
        var mapped = snapshot.players().getFirst();

        assertThat(mapped.fields()).containsEntry("expected_goals", null)
                .containsEntry("news_added", null)
                .containsEntry("form", new BigDecimal("0.0"));
    }

    @Test
    void acceptsAssignedUndatedFixtureWithoutInferringStatus() throws IOException {
        ArrayNode fixtures = fixtures();
        ((ObjectNode) fixtures.get(379)).putNull("kickoff_time");

        FixtureRow mapped = parse(bootstrapBytes(), bytes(fixtures), SEASON).fixtures().get(379);

        assertThat(mapped.gameweek()).isEqualTo(38);
        assertThat(mapped.kickoff()).isNull();
        assertThat(mapped.started()).isFalse();
        assertThat(mapped.finished()).isFalse();
    }

    @Test
    void acceptsUnassignedDatedFixtureWithoutInferringStatus() throws IOException {
        ArrayNode fixtures = fixtures();
        ((ObjectNode) fixtures.get(379)).putNull("event");

        FixtureRow mapped = parse(bootstrapBytes(), bytes(fixtures), SEASON).fixtures().get(379);

        assertThat(mapped.gameweek()).isNull();
        assertThat(mapped.kickoff()).isEqualTo(Instant.parse("2027-05-30T15:00:00Z"));
        assertThat(mapped.started()).isFalse();
        assertThat(mapped.finished()).isFalse();
    }

    @Test
    void rejectsDuplicateFieldsTrailingJsonAndWrongRootTypes() throws IOException {
        assertInvalid("{\"events\":[],\"events\":[]}".getBytes(), fixtureBytes(),
                "malformed bootstrap-static JSON");
        assertInvalid(concat(bootstrapBytes(), " []"), fixtureBytes(),
                "malformed bootstrap-static JSON");
        assertInvalid("[]".getBytes(), fixtureBytes(), "bootstrap-static root must be an object");
        assertInvalid(bootstrapBytes(), "{}".getBytes(), "fixtures root must be an array");
    }

    @Test
    void rejectsMissingMandatoryFieldsMalformedTypesAndOutOfCapacityDecimals() throws IOException {
        ObjectNode missing = bootstrap();
        ((ObjectNode) missing.withArray("elements").get(0)).remove("total_points");
        assertInvalid(bytes(missing), fixtureBytes(), "missing or non-integer total_points");

        ObjectNode malformed = bootstrap();
        ((ObjectNode) malformed.withArray("elements").get(0)).put("now_cost", "45");
        assertInvalid(bytes(malformed), fixtureBytes(), "non-integer now_cost");

        ObjectNode integerCapacity = bootstrap();
        ((ObjectNode) integerCapacity.withArray("elements").get(0))
                .put("expected_goals", "10000");
        assertInvalid(bytes(integerCapacity), fixtureBytes(), "exceeds NUMERIC(6,2)");

        ObjectNode exponentCapacity = bootstrap();
        ((ObjectNode) exponentCapacity.withArray("elements").get(0))
                .put("expected_goals", "1E4");
        assertInvalid(bytes(exponentCapacity), fixtureBytes(), "exceeds NUMERIC(6,2)");

        ObjectNode fractionalCapacity = bootstrap();
        ((ObjectNode) fractionalCapacity.withArray("elements").get(0))
                .put("expected_goals", "1.234");
        assertInvalid(bytes(fractionalCapacity), fixtureBytes(), "exceeds NUMERIC(6,2)");
    }

    @Test
    void acceptsNumericCapacityEdgesAndInsignificantFractionalZeros() throws IOException {
        ObjectNode bootstrap = bootstrap();
        ObjectNode element = (ObjectNode) bootstrap.withArray("elements").get(0);
        element.put("expected_goals", "9999.99");
        element.put("expected_assists", "1.2300");

        var fields = parse(bytes(bootstrap), fixtureBytes(), SEASON).players().getFirst().fields();

        assertThat(fields).containsEntry("expected_goals", new BigDecimal("9999.99"))
                .containsEntry("expected_assists", new BigDecimal("1.2300"));
    }

    @Test
    void rejectsPartialLeagueDuplicatePairingsAndUnknownCurrentTeamReferences() throws IOException {
        ArrayNode partial = fixtures();
        partial.remove(379);
        assertInvalid(bootstrapBytes(), bytes(partial), "INVALID_FIXTURE_COUNT");

        ArrayNode duplicatePairing = fixtures();
        ObjectNode last = (ObjectNode) duplicatePairing.get(379);
        JsonNode first = duplicatePairing.get(0);
        last.put("team_h", first.get("team_h").intValue());
        last.put("team_a", first.get("team_a").intValue());
        assertInvalid(bootstrapBytes(), bytes(duplicatePairing), "DUPLICATE_PAIRING");

        ObjectNode unknownClub = bootstrap();
        ((ObjectNode) unknownClub.withArray("elements").get(0)).put("team", 999);
        assertInvalid(bytes(unknownClub), fixtureBytes(), "references unknown team");
    }

    @Test
    void preservesPositiveDepartedPlayerIdsInFixtureStatsAndHistoricalRankings() throws IOException {
        ArrayNode fixtures = fixtures();
        ((ObjectNode) fixtures.get(0).withArray("stats").get(0).withArray("h").get(0))
                .put("element", 999999);
        ObjectNode bootstrap = bootstrap();
        ((ObjectNode) bootstrap.withArray("events").get(0)).put("top_element", 999998);

        Snapshot snapshot = parse(bytes(bootstrap), bytes(fixtures), SEASON);

        assertThat(snapshot.gameweeks().getFirst().fields()).containsEntry("top_element", 999998);
        assertThat((String) snapshot.fixtures().getFirst().fields().get("stats"))
                .contains("\"element\":999999");
    }

    @Test
    void rejectsNonPositivePlayerIdsInFixtureStatsAndHistoricalRankings() throws IOException {
        ArrayNode fixtures = fixtures();
        ((ObjectNode) fixtures.get(0).withArray("stats").get(0).withArray("h").get(0))
                .put("element", 0);
        assertInvalid(bootstrapBytes(), bytes(fixtures), "element must be positive");

        ObjectNode bootstrap = bootstrap();
        ((ObjectNode) bootstrap.withArray("events").get(0)).put("top_element", -1);
        assertInvalid(bytes(bootstrap), fixtureBytes(), "top_element must be positive");
    }

    @Test
    void rejectsOldPayloadForFutureSeasonRegistrationAndIncoherentFinishedFixture() throws IOException {
        Season wrongSeason = new Season(28, "2027-28",
                LocalDate.of(2027, 8, 21), null);
        assertInvalid(bootstrapBytes(), fixtureBytes(), wrongSeason,
                "falls outside configured season 2027-28");

        ArrayNode fixtures = fixtures();
        ObjectNode future = (ObjectNode) fixtures.get(379);
        future.put("started", true);
        future.put("finished", true);
        future.put("minutes", 90);
        assertInvalid(bootstrapBytes(), bytes(fixtures), "incoherent finished status/scores");
    }

    @Test
    void rejectsEndpointReadsFromDifferentBoundedWindows() throws IOException {
        ReadInterval stale = new ReadInterval(Instant.parse("2026-09-12T17:00:00Z"),
                Instant.parse("2026-09-12T17:00:01Z"));

        assertThatThrownBy(() -> CatalogueScheduleParser.parse(SEASON,
                new SourceDocument(bootstrapBytes(), BOOTSTRAP_READ),
                new SourceDocument(fixtureBytes(), stale)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one bounded source window");
    }

    @Test
    void sourceDocumentsAndSnapshotsAreImmutable() throws IOException {
        byte[] bootstrapBytes = bootstrapBytes();
        SourceDocument bootstrap = new SourceDocument(bootstrapBytes, BOOTSTRAP_READ);
        bootstrapBytes[0] = 'x';
        byte[] exposed = bootstrap.bytes();
        exposed[0] = 'x';

        Snapshot snapshot = CatalogueScheduleParser.parse(SEASON, bootstrap,
                new SourceDocument(fixtureBytes(), FIXTURE_READ));

        assertThatThrownBy(() -> snapshot.teams().add(snapshot.teams().getFirst()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.players().getFirst().fields().put("total_points", 999))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(snapshot.teams()).hasSize(20);
    }

    private static Snapshot captured() throws IOException {
        return parse(bootstrapBytes(), fixtureBytes(), SEASON);
    }

    private static Snapshot parse(byte[] bootstrap, byte[] fixtures, Season season) {
        return CatalogueScheduleParser.parse(season,
                new SourceDocument(bootstrap, BOOTSTRAP_READ),
                new SourceDocument(fixtures, FIXTURE_READ));
    }

    private static void assertInvalid(byte[] bootstrap, byte[] fixtures, String message) {
        assertInvalid(bootstrap, fixtures, SEASON, message);
    }

    private static void assertInvalid(byte[] bootstrap, byte[] fixtures, Season season, String message) {
        assertThatThrownBy(() -> parse(bootstrap, fixtures, season))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(message);
    }

    private static ObjectNode bootstrap() throws IOException {
        return (ObjectNode) JSON.readTree(bootstrapBytes());
    }

    private static ArrayNode fixtures() throws IOException {
        return (ArrayNode) JSON.readTree(fixtureBytes());
    }

    private static byte[] bootstrapBytes() throws IOException {
        return resource("bootstrap-static.json");
    }

    private static byte[] fixtureBytes() throws IOException {
        return resource("fixtures.json");
    }

    private static byte[] resource(String file) throws IOException {
        try (InputStream input = CatalogueScheduleParserTest.class.getResourceAsStream(
                "/owned-sync-source-fixtures/" + file)) {
            if (input == null) {
                throw new IllegalArgumentException("Missing fixture " + file);
            }
            return input.readAllBytes();
        }
    }

    private static byte[] bytes(JsonNode node) throws IOException {
        return JSON.writeValueAsBytes(node);
    }

    private static byte[] concat(byte[] body, String suffix) {
        byte[] tail = suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] result = java.util.Arrays.copyOf(body, body.length + tail.length);
        System.arraycopy(tail, 0, result, body.length, tail.length);
        return result;
    }
}
