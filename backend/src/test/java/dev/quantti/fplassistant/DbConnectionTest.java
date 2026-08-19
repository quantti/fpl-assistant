package dev.quantti.fplassistant;

import dev.quantti.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DbConnectionTest {

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        TapasSeed.ensure(jdbc);
    }

    @Test
    void canQueryCurrentSeason() {
        Integer seasonId = jdbc.queryForObject(
                "SELECT id FROM season WHERE is_current = true", Integer.class);

        assertThat(seasonId).isEqualTo(TapasSeed.CURRENT_SEASON_ID);
    }
}
