package fi.karivanttinen.fplassistant.sync;

import java.time.Duration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Small isolated pool whose acquisition, socket, statement and lock waits are all bounded. */
public final class OwnedSyncMonitoringDatabase implements AutoCloseable {

    static final int QUERY_TIMEOUT_SECONDS = 5;
    static final int WORK_QUERY_TIMEOUT_SECONDS = 10;
    private final HikariDataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    OwnedSyncMonitoringDatabase(Environment environment) {
        this(environment, "owned-sync-monitor", 2, QUERY_TIMEOUT_SECONDS, QUERY_TIMEOUT_SECONDS);
    }

    static OwnedSyncMonitoringDatabase work(Environment environment) {
        return new OwnedSyncMonitoringDatabase(environment, "owned-sync-work", 4,
                WORK_QUERY_TIMEOUT_SECONDS, 30);
    }

    private OwnedSyncMonitoringDatabase(Environment environment, String poolName, int poolSize,
                                        int queryTimeoutSeconds, int transactionTimeoutSeconds) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(environment.getRequiredProperty("spring.datasource.url"));
        config.setUsername(environment.getRequiredProperty("spring.datasource.username"));
        config.setPassword(environment.getRequiredProperty("spring.datasource.password"));
        config.setDriverClassName(environment.getProperty("spring.datasource.driver-class-name", "org.postgresql.Driver"));
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(Duration.ofSeconds(2).toMillis());
        config.setValidationTimeout(Duration.ofSeconds(1).toMillis());
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "3");
        config.addDataSourceProperty("socketTimeout", Integer.toString(queryTimeoutSeconds));
        config.addDataSourceProperty("prepareThreshold", "0");
        config.addDataSourceProperty("options", "-c statement_timeout=" + (queryTimeoutSeconds * 1_000)
                + " -c lock_timeout=3000");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(queryTimeoutSeconds);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setTimeout(transactionTimeoutSeconds);
    }

    JdbcTemplate jdbc() { return jdbc; }
    HikariDataSource dataSource() { return dataSource; }
    TransactionTemplate transaction() { return transaction; }

    @Override public void close() { dataSource.close(); }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("(!${fpl.owned-sync.importer.enabled:false} && T(fi.karivanttinen.fplassistant.sync.OwnedSyncMode).valueOf('${fpl.owned-sync.mode:OFF}') != T(fi.karivanttinen.fplassistant.sync.OwnedSyncMode).OFF) || ${fpl.owned-sync.ops.enabled:false} || ${fpl.owned-sync.external-health.enabled:false}")
    static class DatabaseConfiguration {
        @Bean
        OwnedSyncMonitoringDatabase ownedSyncMonitoringDatabase(Environment environment) {
            return new OwnedSyncMonitoringDatabase(environment);
        }
    }
}
