package fi.karivanttinen.fplassistant.price;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import fi.karivanttinen.fplassistant.testsupport.TapasSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class DailyPlayingChanceIntegrationTest {
    private static final String TABLE = "fpl_playing_chance_test";
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private DailyPriceSchema schema;
    private HttpServer server;
    private DailyPriceSyncService sync;
    private volatile Object chance;

    @BeforeEach void setup() throws Exception {
        TapasSeed.ensure(jdbc);
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
        schema = new DailyPriceSchema(jdbc, TABLE);
        schema.ensureSchema();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bootstrap", exchange -> {
            var player = new HashMap<String, Object>();
            player.put("id", 1);
            player.put("web_name", "Chance");
            player.put("now_cost", 60);
            player.put("status", "d");
            player.put("chance_of_playing_next_round", chance);
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("elements", List.of(player)));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        sync = new DailyPriceSyncService(RestClient.builder(), jdbc,
                new TransactionTemplate(transactions), TABLE,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/bootstrap");
    }

    @AfterEach void cleanup() {
        if (server != null) server.stop(0);
        jdbc.execute("DROP TABLE IF EXISTS " + TABLE);
    }

    @Test void upgradesExistingRowsWithoutBackfillAndPreservesKeyAndRls() {
        jdbc.execute("ALTER TABLE " + TABLE + " DROP COLUMN chance_of_playing_next_round");
        jdbc.update("INSERT INTO " + TABLE + " (season,price_date,element_id,web_name,now_cost) VALUES ('2025-26','2026-05-01',1,'Archive',60)");
        schema.ensureSchema();
        schema.ensureSchema();
        assertThat(jdbc.queryForObject("SELECT chance_of_playing_next_round FROM " + TABLE, Integer.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE oid=to_regclass(?)", Boolean.class, TABLE)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid=to_regclass(?) AND contype='p'", String.class, TABLE))
                .contains("season, price_date, element_id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_constraint WHERE conrelid=to_regclass(?) AND conname=?", Integer.class, TABLE, TABLE + "_playing_chance_check")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLE + " SET chance_of_playing_next_round=101"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE " + TABLE + " SET chance_of_playing_next_round=-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void writerRoundTripReplacesAndClearsOnlyTheCurrentSeasonDay() {
        LocalDate today = LocalDate.now(DailyPriceSyncService.UK);
        String season = DailyPriceSyncService.seasonCode(today);
        jdbc.update("INSERT INTO " + TABLE + " (season,price_date,element_id,web_name,now_cost,chance_of_playing_next_round) VALUES (?,?,1,'Yesterday',60,25),('OLD',?,1,'Other season',60,100)", season, today.minusDays(1), today);
        for (Object value : java.util.Arrays.asList(75, 50, 0, null, 25, 100, "75", 25.5, -1, 101)) {
            chance = value;
            assertThat(sync.sync()).isEqualTo(1);
            Integer expected = value instanceof Integer n && n >= 0 && n <= 100 ? n : null;
            assertThat(jdbc.queryForObject("SELECT chance_of_playing_next_round FROM " + TABLE + " WHERE season=? AND price_date=? AND element_id=1", Integer.class, season, today))
                    .as("stored chance %s", value).isEqualTo(expected);
        }
        assertThat(jdbc.queryForObject("SELECT chance_of_playing_next_round FROM " + TABLE + " WHERE season=? AND price_date=?", Integer.class, season, today.minusDays(1))).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT chance_of_playing_next_round FROM " + TABLE + " WHERE season='OLD'", Integer.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + TABLE, Integer.class)).isEqualTo(3);
    }
}
