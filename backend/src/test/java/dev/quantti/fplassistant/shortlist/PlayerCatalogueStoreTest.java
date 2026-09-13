package dev.quantti.fplassistant.shortlist;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PlayerCatalogueStoreTest {

    private static final String SEASON_PREFIX = "C-";
    private static final Instant CAPTURED = Instant.parse("2090-08-01T01:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlayerCatalogueStore store;
    @Value("${fpl.daily-price.table-name}") String dailyTable;
    @Value("${fpl.shortlist.catalogue-table-name}") String catalogueTable;
    @Value("${fpl.shortlist.catalogue-player-table-name}") String playerTable;
    @Value("${fpl.shortlist.catalogue-alias-table-name}") String aliasTable;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM " + aliasTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + playerTable + " WHERE catalogue_id IN (SELECT id FROM "
                + catalogueTable + " WHERE season LIKE ?)", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + catalogueTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
        jdbc.update("DELETE FROM " + dailyTable + " WHERE season LIKE ?", SEASON_PREFIX + "%");
    }

    @Test
    void seasonScopesReusedElementIds() {
        LocalDate day = LocalDate.of(2090, 8, 1);
        seed("C-2090-A", day, CAPTURED, 7, "Oldname", "Old Name", 1, "AAA", "Alpha", 3);
        seed("C-2091-B", day.plusYears(1), CAPTURED.plusSeconds(1),
                7, "Newname", "New Name", 2, "BBB", "Beta", 4);

        PlayerCatalogueStore.Catalogue oldCatalogue = store.forCapture("C-2090-A", day).orElseThrow();
        PlayerCatalogueStore.Catalogue newCatalogue = store.forCapture("C-2091-B", day.plusYears(1))
                .orElseThrow();

        assertThat(oldCatalogue.id()).isNotEqualTo(newCatalogue.id());
        assertThat(oldCatalogue.players().getFirst().elementId()).isEqualTo(7);
        assertThat(newCatalogue.players().getFirst().elementId()).isEqualTo(7);
        assertThat(oldCatalogue.players().getFirst().webName()).isEqualTo("Oldname");
        assertThat(newCatalogue.players().getFirst().webName()).isEqualTo("Newname");
    }

    @Test
    void ambiguousAliasesAreOmittedAndReliableAliasesAreLongestFirst() {
        String season = "C-ALIASES";
        LocalDate day = LocalDate.of(2090, 8, 2);
        seed(season, day, CAPTURED, 1, "Smith", "Adam Smith", 1, "AAA", "Alpha", 2);
        seed(season, day, CAPTURED, 2, "Smith", "Samuel Smith", 2, "BBB", "Beta", 2);
        seed(season, day, CAPTURED, 3, "Saka", "Bukayo   Saka", 1, "AAA", "Alpha", 3);

        PlayerCatalogueStore.Catalogue catalogue = store.forCapture(season, day).orElseThrow();

        assertThat(catalogue.players().get(0).aliases()).containsExactly("Adam Smith");
        assertThat(catalogue.players().get(1).aliases()).containsExactly("Samuel Smith");
        assertThat(catalogue.players().get(2).aliases()).containsExactly("Bukayo   Saka", "Saka");
        assertThat(catalogue.players()).flatExtracting(PlayerCatalogueStore.Player::aliases)
                .doesNotContain("Smith");
    }

    @Test
    void emptyOrMalformedCaptureCreatesNothing() {
        LocalDate emptyDay = LocalDate.of(2090, 8, 3);
        assertThat(store.forCapture("C-EMPTY", emptyDay)).isEmpty();

        String malformedSeason = "C-BAD";
        seed(malformedSeason, emptyDay, CAPTURED, 1, "Valid", null,
                1, "AAA", "Alpha", 3);
        assertThat(store.forCapture(malformedSeason, emptyDay)).isEmpty();

        String mixedSeason = "C-MIXED";
        seed(mixedSeason, emptyDay, CAPTURED, 1, "One", "Player One",
                1, "AAA", "Alpha", 3);
        seed(mixedSeason, emptyDay, CAPTURED.plusSeconds(1), 2, "Two", "Player Two",
                1, "AAA", "Alpha", 3);
        assertThat(store.forCapture(mixedSeason, emptyDay)).isEmpty();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + catalogueTable
                + " WHERE season LIKE ?", Integer.class, SEASON_PREFIX + "%")).isZero();
    }

    @Test
    void unchangedIdentityContentsReuseOneCatalogueAcrossDailyCaptures() {
        String season = "C-STABLE";
        LocalDate firstDay = LocalDate.of(2090, 8, 4);
        seed(season, firstDay, CAPTURED, 9, "Isak", "Alexander Isak",
                3, "NEW", "Newcastle", 4);
        PlayerCatalogueStore.Catalogue first = store.forCapture(season, firstDay).orElseThrow();

        seed(season, firstDay.plusDays(1), CAPTURED.plusSeconds(86_400),
                9, "Isak", "Alexander Isak", 3, "NEW", "Newcastle", 4);
        PlayerCatalogueStore.Catalogue second = store.latest().orElseThrow();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.sourcePriceDate()).isEqualTo(firstDay);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + catalogueTable
                + " WHERE season = ?", Integer.class, season)).isEqualTo(1);
    }

    @Test
    void aChangedCaptureCreatesANewCatalogueWithoutMutatingTheOldOne() {
        String season = "C-IMMUT";
        LocalDate day = LocalDate.of(2090, 8, 5);
        seed(season, day, CAPTURED, 12, "Original", "Original Player",
                1, "AAA", "Alpha", 3);
        PlayerCatalogueStore.Catalogue original = store.forCapture(season, day).orElseThrow();

        jdbc.update("UPDATE " + dailyTable
                        + " SET web_name = ?, full_name = ?, captured_at = ?"
                        + " WHERE season = ? AND price_date = ? AND element_id = ?",
                "Renamed", "Renamed Player", Timestamp.from(CAPTURED.plusSeconds(1)),
                season, day, 12);
        PlayerCatalogueStore.Catalogue changed = store.forCapture(season, day).orElseThrow();
        Optional<PlayerCatalogueStore.Catalogue> rereadOriginal = store.find(original.id());

        assertThat(changed.id()).isNotEqualTo(original.id());
        assertThat(changed.players().getFirst().webName()).isEqualTo("Renamed");
        assertThat(rereadOriginal).isPresent();
        assertThat(rereadOriginal.orElseThrow().players().getFirst().webName()).isEqualTo("Original");
    }

    private void seed(String season, LocalDate date, Instant capturedAt, int elementId,
                      String webName, String fullName, int teamId, String teamShort,
                      String teamName, int position) {
        jdbc.update("""
                INSERT INTO %s (season, price_date, element_id, web_name, full_name, team_id,
                                team_short, team_name, element_type, now_cost, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 50, ?)
                """.formatted(dailyTable), season, date, elementId, webName, fullName, teamId,
                teamShort, teamName, position, Timestamp.from(capturedAt));
    }
}
