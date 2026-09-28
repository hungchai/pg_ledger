package io.zodia.pgledger.store;

/** Business rule rejection. HTTP maps this to 422. */
public final class LedgerViolation extends RuntimeException {
    public LedgerViolation(String message) {
        super(message);
    }
}
