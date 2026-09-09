package dev.quantti.fplassistant.schedule;

import java.time.Instant;
import java.util.concurrent.Executors;

import dev.quantti.fplassistant.testsupport.RankingSeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScheduleSourceViewIntegrationTest {

    private static final Instant FIRST_DEADLINE = Instant.parse("2097-08-14T17:30:00Z");
    private static final Instant OLD_CAPTURE = Instant.parse("2097-08-10T02:10:00Z");
    private static final Instant NEW_CAPTURE = Instant.parse("2097-08-11T02:10:00Z");

    @Autowired ScheduleSource source;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Value("${fpl.schedule.gameweek-table-name}") String gameweekTable;
    @Value("${fpl.schedule.fixture-table-name}") String fixtureTable;

    @AfterEach
    void cleanUp() {
        RankingSeed.cleanSchedule(jdbc, gameweekTable, fixtureTable, RankingSeed.VIEW_SEASON);
    }

    @Test
    void readsAnImmutableCompleteViewWithoutDroppingUnresolvedScheduling() {
        RankingSeed.seedSchedule(jdbc, gameweekTable, fixtureTable, RankingSeed.VIEW_SEASON,
                OLD_CAPTURE, FIRST_DEADLINE);
        jdbc.update("UPDATE " + fixtureTable
                        + " SET gameweek = NULL, kickoff_time = NULL WHERE season = ? AND id = ?",
                RankingSeed.VIEW_SEASON,
                RankingSeed.fixtureId(RankingSeed.team(1), RankingSeed.team(2)));

        ScheduleSource.View view = source.readView();

        assertThat(view.snapshot()).contains(
                new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, OLD_CAPTURE));
        assertThat(view.events()).hasSize(38);
        assertThat(view.fixtures()).hasSize(380);
        assertThat(view.completeness().complete()).isTrue();
        assertThat(view.fixtures()).anySatisfy(row -> {
            assertThat(row.gameweek()).isNull();
            assertThat(row.kickoff()).isNull();
        });
        assertThatThrownBy(() -> view.events().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repeatableReadKeepsOneScheduleSnapshotWhileANewOneCommits() throws Exception {
        RankingSeed.seedSchedule(jdbc, gameweekTable, fixtureTable, RankingSeed.VIEW_SEASON,
                OLD_CAPTURE, FIRST_DEADLINE);
        TransactionTemplate reader = new TransactionTemplate(transactionManager);
        reader.setReadOnly(true);
        reader.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionTemplate writer = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newSingleThreadExecutor()) {
            reader.executeWithoutResult(status -> {
                ScheduleSource.View before = source.readView();
                var committed = executor.submit(() -> writer.executeWithoutResult(ignored ->
                        RankingSeed.seedSchedule(jdbc, gameweekTable, fixtureTable,
                                RankingSeed.VIEW_SEASON, NEW_CAPTURE, FIRST_DEADLINE)));
                try {
                    committed.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                ScheduleSource.View after = source.readView();
                assertThat(before.snapshot()).isEqualTo(after.snapshot());
                assertThat(after.snapshot()).contains(
                        new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, OLD_CAPTURE));
                assertThat(after.completeness().complete()).isTrue();
            });
        }
        assertThat(source.readView().snapshot()).contains(
                new ScheduleSource.Snapshot(RankingSeed.VIEW_SEASON, NEW_CAPTURE));
    }
}
