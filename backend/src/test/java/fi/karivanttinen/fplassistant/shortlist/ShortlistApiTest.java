package fi.karivanttinen.fplassistant.shortlist;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import fi.karivanttinen.fplassistant.testsupport.LocalSigningKey;
import fi.karivanttinen.fplassistant.testsupport.TestJwks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(LocalSigningKey.class)
class ShortlistApiTest {

    private static final UUID USER_A = UUID.fromString("73111111-1111-4111-8111-111111111111");
    private static final UUID USER_B = UUID.fromString("74222222-2222-4222-8222-222222222222");
    private static final String SEASON_PREFIX = "S-";
    private static final Instant CAPTURED = Instant.parse("2091-08-01T01:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlayerCatalogueStore catalogues;
    @Autowired ShortlistStore shortlist;
    @Autowired ShortlistSchema schema;
    @Value("${fpl.daily-price.table-name}") String dailyTable;
    @Value("${fpl.shortlist.table-name}") String shortlistTable;
    @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable;
    @Value("${fpl.shortlist.catalogue-player-table-name}") String cataloguePlayerTable;
    @Value("${fpl.shortlist.catalogue-alias-table-name}") String catalogueAliasTable;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM " + shortlistTable + " WHERE user_id IN (?, ?)", USER_A, USER_B);
        jdbc.update("DELETE FROM " + catalogueAliasTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + cataloguePlayerTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + catalogueTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
    }

    @Test
    void schemaAndSeedDataStayInIsolatedTablesAndSchemaCreationIsIdempotent() {
        assertThat(shortlistTable).endsWith("_test");
        assertThat(catalogueTable).endsWith("_test");

        schema.ensureSchema();
        schema.ensureSchema();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + shortlistTable,
                Integer.class)).isZero();
    }

    @Test
    void putUsesAuthoritativeCatalogueFieldsAndIsIdempotent() throws Exception {
        PlayerCatalogueStore.Catalogue catalogue = seedCatalogue("S-AUTH", 17, "Saka",
                "Bukayo Saka", 1, "ARS", "Arsenal", 3);
        String body = addBody(catalogue.id(), catalogue.season(), 17);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/shortlist").header("Authorization", bearer(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.catalogueId").value(catalogue.id().toString()))
                    .andExpect(jsonPath("$.season").value("S-AUTH"))
                    .andExpect(jsonPath("$.elementId").value(17))
                    .andExpect(jsonPath("$.webName").value("Saka"))
                    .andExpect(jsonPath("$.fullName").value("Bukayo Saka"))
                    .andExpect(jsonPath("$.teamShort").value("ARS"))
                    .andExpect(jsonPath("$.teamName").value("Arsenal"))
                    .andExpect(jsonPath("$.elementType").value(3));
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + shortlistTable
                + " WHERE user_id = ?", Integer.class, USER_A)).isEqualTo(1);
    }

    @Test
    void ownershipAndSeasonScopedKeysArePreservedByListing() throws Exception {
        PlayerCatalogueStore.Catalogue first = seedCatalogue("S-2091-A", 7, "Old",
                "Old Player", 1, "AAA", "Alpha", 2);
        PlayerCatalogueStore.Catalogue second = seedCatalogue("S-2092-B", 7, "New",
                "New Player", 2, "BBB", "Beta", 4);
        shortlist.put(USER_A, first.id(), first.season(), 7).orElseThrow();
        shortlist.put(USER_A, second.id(), second.season(), 7).orElseThrow();
        shortlist.put(USER_B, first.id(), first.season(), 7).orElseThrow();
        // Listing is a stored snapshot, not a join back to a mutable daily capture.
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");

        mockMvc.perform(get("/api/shortlist").header("Authorization", bearer(USER_A)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.season == 'S-2091-A')].webName").value("Old"))
                .andExpect(jsonPath("$[?(@.season == 'S-2092-B')].webName").value("New"));
    }

    @Test
    void catalogueSeasonAndPlayerMustMatchExactly() throws Exception {
        PlayerCatalogueStore.Catalogue catalogue = seedCatalogue("S-VALID", 23, "Valid",
                "Valid Player", 1, "AAA", "Alpha", 3);

        for (String body : new String[] {
                addBody(UUID.randomUUID(), "S-VALID", 23),
                addBody(catalogue.id(), "S-WRONG", 23),
                addBody(catalogue.id(), "S-VALID", 24),
                "{\"catalogueId\":\"not-a-uuid\",\"season\":\"S-VALID\",\"elementId\":23}",
                "{\"season\":\"S-VALID\",\"elementId\":23}"
        }) {
            mockMvc.perform(put("/api/shortlist").header("Authorization", bearer(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }

        assertThat(shortlist.list(USER_A)).isEmpty();
    }

    @Test
    void deleteIsOwnerScopedAndIdempotentlyMakesMembershipAbsent() throws Exception {
        PlayerCatalogueStore.Catalogue catalogue = seedCatalogue("S-DELETE", 31, "Gone",
                "Gone Player", 1, "AAA", "Alpha", 1);
        shortlist.put(USER_A, catalogue.id(), catalogue.season(), 31).orElseThrow();
        shortlist.put(USER_B, catalogue.id(), catalogue.season(), 31).orElseThrow();
        String body = "{\"season\":\"S-DELETE\",\"elementId\":31}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/shortlist").header("Authorization", bearer(USER_A))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        assertThat(shortlist.list(USER_A)).isEmpty();
        assertThat(shortlist.list(USER_B)).hasSize(1);
    }

    @Test
    void everyOperationRequiresAUsableJwtSubject() throws Exception {
        mockMvc.perform(get("/api/shortlist")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/shortlist").header("Authorization", bearer("not-a-uuid")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/shortlist").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/shortlist").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    private PlayerCatalogueStore.Catalogue seedCatalogue(String season, int elementId,
                                                           String webName, String fullName,
                                                           int teamId, String teamShort,
                                                           String teamName, int position) {
        LocalDate date = LocalDate.of(2091, 8, 1).plusDays(elementId);
        jdbc.update("""
                INSERT INTO %s (season, price_date, element_id, web_name, full_name, team_id,
                                team_short, team_name, element_type, now_cost, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 50, ?)
                """.formatted(dailyTable), season, date, elementId, webName, fullName, teamId,
                teamShort, teamName, position, Timestamp.from(CAPTURED.plusSeconds(elementId)));
        return catalogues.forCapture(season, date).orElseThrow();
    }

    private static String addBody(UUID catalogueId, String season, int elementId) {
        return "{\"catalogueId\":\"" + catalogueId + "\",\"season\":\"" + season
                + "\",\"elementId\":" + elementId + "}";
    }

    private static String bearer(UUID user) {
        return bearer(user.toString());
    }

    private static String bearer(String user) {
        return "Bearer " + TestJwks.validTokenFor(user);
    }
}
