package io.zodia.pgledger.rest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pgledger")
public record PgLedgerProperties(
        String writerJdbcUrl,
        String readerJdbcUrl,
        String jdbcUser,
        String jdbcPassword) {
}
