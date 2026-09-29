package io.zodia.pgledger.rest;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.store.PostgresLedgerStore;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.config.ReadwriteSplittingRuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.config.rule.ReadwriteSplittingDataSourceGroupRuleConfiguration;
import org.apache.shardingsphere.readwritesplitting.transaction.TransactionalReadQueryStrategy;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgLedgerProperties.class)
class PgLedgerDataSourceConfiguration {
    @Bean(name = "writerDataSource", destroyMethod = "close")
    DataSource writerDataSource(PgLedgerProperties properties) {
        return pool("pgledger-writer", required("PGLEDGER_WRITER_JDBC_URL", properties.writerJdbcUrl()), properties);
    }

    @Bean(name = "readerDataSource", destroyMethod = "close")
    DataSource readerDataSource(PgLedgerProperties properties) {
        return pool("pgledger-reader", required("PGLEDGER_READER_JDBC_URL", properties.readerJdbcUrl()), properties);
    }

    @Bean(destroyMethod = "close")
    @Primary
    DataSource dataSource(
            @Qualifier("writerDataSource") DataSource writer,
            @Qualifier("readerDataSource") DataSource reader) throws SQLException {
        requireRoles(writer, reader);
        PostgresLedgerStore.migrate(writer);
        return readWriteSplitting(writer, reader);
    }

    @Bean(destroyMethod = "close")
    PgLedger pgLedger(@Qualifier("dataSource") DataSource dataSource, PgLedgerProperties properties) {
        int poolSize = properties.bankPoolSize() == null
                ? PgLedger.DEFAULT_BANK_POOL_SIZE
                : properties.bankPoolSize().intValue();
        return PgLedger.routed(dataSource, poolSize);
    }

    private static DataSource readWriteSplitting(DataSource writer, DataSource reader) throws SQLException {
        HashMap<String, DataSource> sources = new HashMap<>(2);
        sources.put("writer", writer);
        sources.put("reader", reader);
        ReadwriteSplittingDataSourceGroupRuleConfiguration group = new ReadwriteSplittingDataSourceGroupRuleConfiguration(
                "pgledger", "writer", List.of("reader"), TransactionalReadQueryStrategy.DYNAMIC, "round_robin");
        Map<String, AlgorithmConfiguration> loadBalancers = Map.of(
                "round_robin", new AlgorithmConfiguration("ROUND_ROBIN", new Properties()));
        ReadwriteSplittingRuleConfiguration rule =
                new ReadwriteSplittingRuleConfiguration(List.of(group), loadBalancers);
        SingleRuleConfiguration single = new SingleRuleConfiguration(List.of("*.*"), "pgledger");
        List<RuleConfiguration> rules = List.of(rule, single);
        Properties props = new Properties();
        props.setProperty("sql-show", "false");
        props.setProperty("check-table-metadata-enabled", "false");
        return ShardingSphereDataSourceFactory.createDataSource(sources, rules, props);
    }

    private static DataSource pool(String name, String jdbcUrl, PgLedgerProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(required("PGLEDGER_JDBC_USER", properties.jdbcUser()));
        config.setPassword(required("PGLEDGER_JDBC_PASSWORD", properties.jdbcPassword()));
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000L);
        return new HikariDataSource(config);
    }

    private static void requireRoles(DataSource writer, DataSource reader) throws SQLException {
        if (recovery(writer)) {
            throw new IllegalStateException("PGLEDGER_WRITER_JDBC_URL is a replica");
        }
        if (!recovery(reader)) {
            throw new IllegalStateException("PGLEDGER_READER_JDBC_URL is not a replica");
        }
    }

    private static boolean recovery(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_is_in_recovery()")) {
            if (!rs.next()) {
                throw new IllegalStateException("pg_is_in_recovery returned no row");
            }
            return rs.getBoolean(1);
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
