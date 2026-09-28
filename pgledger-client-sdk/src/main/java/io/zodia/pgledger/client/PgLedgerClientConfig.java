package io.zodia.pgledger.client;

import java.net.URI;
import java.time.Duration;

public record PgLedgerClientConfig(URI baseUrl, Duration connectTimeout, Duration readTimeout, int maxRetries) {
    public PgLedgerClientConfig {
        if (baseUrl == null) {
            throw new IllegalArgumentException("baseUrl");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout");
        }
        if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries");
        }
    }
}
