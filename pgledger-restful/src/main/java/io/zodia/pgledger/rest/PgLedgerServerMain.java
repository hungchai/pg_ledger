package io.zodia.pgledger.rest;

import io.zodia.pgledger.PgLedger;

public final class PgLedgerServerMain {
    private PgLedgerServerMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        PgLedger ledger = PgLedger.postgres(
                requireEnv("PGLEDGER_WRITER_JDBC_URL"),
                requireEnv("PGLEDGER_READER_JDBC_URL"),
                requireEnv("PGLEDGER_JDBC_USER"),
                requireEnv("PGLEDGER_JDBC_PASSWORD"));
        int port = 8080;
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isBlank()) {
            port = Integer.parseInt(portEnv);
        }
        PgLedgerServer server = PgLedgerServer.start(ledger, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            ledger.close();
        }));
        System.out.println("pgledger listening on " + server.port());
        Thread.currentThread().join();
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
