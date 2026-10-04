package io.zodia.pgledger.store;

public final class LedgerException extends RuntimeException {
    public LedgerException(String message, Throwable cause) {
        super(message, cause);
    }

    public LedgerException(String message) {
        super(message);
    }
}
