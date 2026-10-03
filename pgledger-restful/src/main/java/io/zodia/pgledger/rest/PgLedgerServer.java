package io.zodia.pgledger.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PgLedgerServer {
    public static final String ROLE_HEADER = "X-Pgledger-Role";
    public static final String WRITER = "writer";
    public static final String READER = "reader";
    public static final Logger log = LoggerFactory.getLogger(PgLedgerServer.class);

    private PgLedgerServer() {
    }
}
