package io.zodia.pgledger.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pgledger")
public record PgLedgerProperties(
        String writerJdbcUrl,
        String readerJdbcUrl,
        String jdbcUser,
        String jdbcPassword,
        Integer bankPoolSize,
        Integer jdbcPoolSize) {

    /** Default Hikari max pool size per role (writer and reader each). */
    public static final int DEFAULT_JDBC_POOL_SIZE = 40;

    public int jdbcPoolSizeOrDefault() {
        return jdbcPoolSize == null ? DEFAULT_JDBC_POOL_SIZE : Math.max(1, jdbcPoolSize.intValue());
    }
}
