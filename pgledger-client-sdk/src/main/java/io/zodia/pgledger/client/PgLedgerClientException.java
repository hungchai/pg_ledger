package io.zodia.pgledger.client;

public final class PgLedgerClientException extends RuntimeException {
    private final int status;

    public PgLedgerClientException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
