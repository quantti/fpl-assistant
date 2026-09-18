package fi.karivanttinen.fplassistant.tools;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerNameSearchTest {

    @Test
    void tokensSplitOnWhitespaceAndDropPunctuationAndBlanks() {
        assertThat(PlayerNameSearch.tokens("  Hall,  Newcastle ")).containsExactly("Hall", "Newcastle");
        assertThat(PlayerNameSearch.tokens("   ")).isEmpty();
        assertThat(PlayerNameSearch.tokens(null)).isEmpty();
    }

    @Test
    void tokensAreCappedSoAPastedParagraphCannotBuildAnUnboundedStatement() {
        assertThat(PlayerNameSearch.tokens("a b c d e f g h")).hasSize(PlayerNameSearch.MAX_TOKENS);
    }

    @Test
    void everyTokenMustHitNameOrClubAndAtLeastOneMustHitTheName() {
        var f = new PlayerNameSearch(true).fragment("Hall Newcastle", "web_name", "N", "C");
        assertThat(f.where()).isEqualTo(
                "((unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(C)) LIKE unaccent(lower(?)))"
                + " AND (unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(C)) LIKE unaccent(lower(?)))"
                + " AND (unaccent(lower(N)) LIKE unaccent(lower(?)) OR unaccent(lower(N)) LIKE unaccent(lower(?))))");
        assertThat(f.whereArgs()).containsExactly(
                "%Hall%", "%Hall%", "%Newcastle%", "%Newcastle%", "%Hall%", "%Newcastle%");
    }

    @Test
    void exactWebNameRanksFirstThenAWebNameEqualToAnyToken() {
        var f = new PlayerNameSearch(true).fragment("Hall Newcastle", "web_name", "N", "C");
        assertThat(f.exactFirst()).isEqualTo(
                "(unaccent(lower(web_name)) = unaccent(lower(?))) DESC,"
                + " (unaccent(lower(web_name)) = unaccent(lower(?))"
                + " OR unaccent(lower(web_name)) = unaccent(lower(?))) DESC");
        assertThat(f.orderArgs()).containsExactly("Hall Newcastle", "Hall", "Newcastle");
    }

    @Test
    void likeMetacharactersInATokenAreEscaped() {
        var f = new PlayerNameSearch(true).fragment("100%", "web_name", "N", "C");
        assertThat(f.whereArgs()).contains("%100\\%%");
    }

    @Test
    void aBlankQueryMatchesNothingRatherThanEverything() {
        var f = new PlayerNameSearch(true).fragment(" ", "web_name", "N", "C");
        assertThat(f.where()).isEqualTo("(1 = 0)");
        assertThat(f.whereArgs()).isEmpty();
        assertThat(f.exactFirst()).isEqualTo("(1 = 0) DESC");
        assertThat(f.orderArgs()).isEmpty();
    }

    @Test
    void withoutUnaccentTheFragmentDegradesToPlainLower() {
        var f = new PlayerNameSearch(false).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).doesNotContain("unaccent").contains("lower(N) LIKE lower(?)");
    }

    @Test
    void aDatabaseThatRefusesTheExtensionDoesNotFailConstruction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("read-only")).when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new DataAccessResourceFailureException("read-only"));
        var f = new PlayerNameSearch(jdbc).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).doesNotContain("unaccent");
    }

    /**
     * A refused CREATE is not an unusable function — read-only mode refuses the statement while
     * the extension is already installed and callable.
     */
    @Test
    void aRefusedCreateStillProbesAndFoldsWhenTheFunctionIsCallable() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doThrow(new DataAccessResourceFailureException("read-only")).when(jdbc).execute(anyString());
        when(jdbc.queryForObject("SELECT unaccent('ć')", String.class)).thenReturn("c");
        var f = new PlayerNameSearch(jdbc).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).contains("unaccent");
    }

    /** Not every startup failure is SQL-shaped: a pool failure is a bare RuntimeException. */
    @Test
    void aNonSqlRuntimeFailureFromTheProbeDoesNotFailConstruction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class)))
                .thenThrow(new IllegalStateException("pool not initialised"));
        var f = new PlayerNameSearch(jdbc).fragment("Hall", "web_name", "N", "C");
        assertThat(f.where()).doesNotContain("unaccent");
    }
}
