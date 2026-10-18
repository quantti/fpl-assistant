package dev.quantti.fplassistant.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class ImporterRequestLimitsTest {
    @Test
    void defaultsRemainFiveThousand() {
        assertThat(CoalescedPlayerHistoryImportConfiguration.requestLimits(new MockEnvironment()))
                .isEqualTo(OwnedSyncRequestBudget.Limits.defaults());
        assertThat(OwnedSyncRequestBudget.Limits.defaults().maxAttempts()).isEqualTo(5_000);
    }

    @Test
    void importerMayUseBoundedCutoverHeadroomWithoutRelaxingOtherGuards() {
        var limits = CoalescedPlayerHistoryImportConfiguration.requestLimits(new MockEnvironment()
                .withProperty("fpl.owned-sync.importer.max-attempts-per-day", "6000"));
        var defaults = OwnedSyncRequestBudget.Limits.defaults();
        assertThat(limits.maxAttempts()).isEqualTo(6_000);
        assertThat(limits.minimumSpacing()).isEqualTo(Duration.ofSeconds(1));
        assertThat(limits.attemptWindow()).isEqualTo(Duration.ofHours(24));
        assertThat(limits.maxLivePermits()).isEqualTo(defaults.maxLivePermits());
        assertThat(limits.permitTtl()).isEqualTo(defaults.permitTtl());
        assertThat(limits.baseBackoff()).isEqualTo(defaults.baseBackoff());
        assertThat(limits.maximumBackoff()).isEqualTo(defaults.maximumBackoff());
    }

    @Test
    @SuppressWarnings("unchecked")
    void admissionRetainsExistingLedgerAndStopsAtTheRaisedCeiling() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, org.springframework.transaction.support.TransactionCallback.class)
                        .doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        when(jdbc.queryForObject(any(String.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(1, org.springframework.jdbc.core.RowMapper.class)
                        .mapRow(mock(java.sql.ResultSet.class), 0));
        when(jdbc.queryForObject(contains("WHERE admitted_at > ?"), eq(Long.class), any(Timestamp.class)))
                .thenReturn(5_000L, 5_000L, 6_000L);
        var clock = Clock.fixed(Instant.parse("2026-10-17T14:00:00Z"), java.time.ZoneOffset.UTC);
        when(jdbc.queryForObject(contains("min(admitted_at)"), eq(Timestamp.class), any(Timestamp.class)))
                .thenReturn(Timestamp.from(clock.instant().minus(Duration.ofHours(1))));
        var legacy = new OwnedSyncRequestBudget(jdbc, tx, clock, OwnedSyncTables.defaults());
        var importer = new OwnedSyncRequestBudget(jdbc, tx, clock, OwnedSyncTables.defaults(),
                OwnedSyncRequestBudget.Limits.defaults(6_000));
        assertThat(legacy.admit()).isInstanceOfSatisfying(OwnedSyncRequestBudget.Deferred.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(OwnedSyncRequestBudget.DeferredReason.ATTEMPT_LIMIT));
        assertThat(importer.admit()).isInstanceOf(OwnedSyncRequestBudget.Allowed.class);
        assertThat(importer.admit()).isInstanceOfSatisfying(OwnedSyncRequestBudget.Deferred.class,
                deferred -> assertThat(deferred.reason()).isEqualTo(OwnedSyncRequestBudget.DeferredReason.ATTEMPT_LIMIT));
        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(3)).update(
                contains("admitted_at < ? AND completed_at IS NOT NULL"),
                eq(Timestamp.from(clock.instant().minus(Duration.ofHours(24)))));
    }

    @Test
    void watchdogUsesTheSameRaisedLimitRatherThanTheLegacyThreshold() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(contains("fpl_sync_request_attempt"), eq(Long.class), any(Timestamp.class)))
                .thenReturn(5_500L, 6_000L);
        var watchdog = new OwnedSyncWatchdog(jdbc, mock(TransactionTemplate.class),
                Clock.systemUTC(), OwnedSyncTables.defaults(),
                new OwnedSyncWatchdog.Notification(false, false, true), payload -> { },
                OwnedSyncRequestBudget.Limits.defaults(6_000));
        var signals = new LinkedHashMap<String, Object>();
        ReflectionTestUtils.invokeMethod(watchdog, "inspectBudget", "2026-27", Instant.now(), signals);
        assertThat(signals).isEmpty();
        ReflectionTestUtils.invokeMethod(watchdog, "inspectBudget", "2026-27", Instant.now(), signals);
        assertThat(signals).containsKey("budget:2026-27");
    }

    @Test
    void refusesUnboundedOrInvalidOverrides() {
        for (String value : new String[] {"0", "-1", "6001", "2147483647"}) {
            assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.requestLimits(
                    new MockEnvironment().withProperty("fpl.owned-sync.importer.max-attempts-per-day", value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> CoalescedPlayerHistoryImportConfiguration.requestLimits(
                new MockEnvironment().withProperty("fpl.owned-sync.importer.max-attempts-per-day", "invalid")))
                .isInstanceOf(ConversionFailedException.class);
    }
}
