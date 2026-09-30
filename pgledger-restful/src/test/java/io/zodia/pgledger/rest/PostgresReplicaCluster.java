package io.zodia.pgledger.rest;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Primary + streaming replica for REST tests. Reuses {@code docker/writer} and
 * {@code docker/reader} scripts from the repo root (test {@code workingDir}).
 * Not used by {@link PgLedgerStressTest}, which targets docker compose.
 */
final class PostgresReplicaCluster implements AutoCloseable {
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16");
    private static final String DB = "pgledger";
    private static final String USER = "pgledger";
    private static final String PASSWORD = "pgledger";
    private static final String WRITER_ALIAS = "writer";

    private final Network network;
    private final PostgreSQLContainer<?> writer;
    private final GenericContainer<?> reader;

    private PostgresReplicaCluster(Network network, PostgreSQLContainer<?> writer, GenericContainer<?> reader) {
        this.network = network;
        this.writer = writer;
        this.reader = reader;
    }

    static PostgresReplicaCluster start() {
        Path root = Path.of("").toAbsolutePath();
        MountableFile enableReplication = MountableFile.forHostPath(
                root.resolve("docker/writer/zz-enable-replication.sh"), 0755);
        MountableFile readerEntrypoint = MountableFile.forHostPath(
                root.resolve("docker/reader/entrypoint.sh"), 0755);

        Network network = Network.newNetwork();
        PostgreSQLContainer<?> writer = new PostgreSQLContainer<>(IMAGE)
                .withNetwork(network)
                .withNetworkAliases(WRITER_ALIAS)
                .withDatabaseName(DB)
                .withUsername(USER)
                .withPassword(PASSWORD)
                .withCommand(
                        "postgres",
                        "-c", "wal_level=replica",
                        "-c", "hot_standby=on",
                        "-c", "max_wal_senders=10",
                        "-c", "max_replication_slots=10",
                        "-c", "wal_keep_size=128MB")
                .withCopyFileToContainer(enableReplication,
                        "/docker-entrypoint-initdb.d/zz-enable-replication.sh")
                .withReuse(false);
        try {
            writer.start();
        } catch (IllegalStateException e) {
            network.close();
            throw new IllegalStateException(
                    "Docker is required for PgLedgerRestTest (Testcontainers PostgreSQL). Is the daemon running?", e);
        }

        GenericContainer<?> reader = new GenericContainer<>(IMAGE)
                .withNetwork(network)
                .withEnv("POSTGRES_USER", USER)
                .withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("POSTGRES_DB", DB)
                .withEnv("PRIMARY_HOST", WRITER_ALIAS)
                .withEnv("PRIMARY_PORT", "5432")
                .withEnv("PGDATA", "/var/lib/postgresql/data")
                .withCopyFileToContainer(readerEntrypoint, "/reader-entrypoint.sh")
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/bash", "/reader-entrypoint.sh"))
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*ready to accept read only connections.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)))
                .withReuse(false);
        try {
            reader.start();
        } catch (RuntimeException e) {
            writer.stop();
            network.close();
            throw new IllegalStateException(
                    "Docker is required for PgLedgerRestTest (Testcontainers replica). Is the daemon running?", e);
        }
        return new PostgresReplicaCluster(network, writer, reader);
    }

    String writerJdbcUrl() {
        return writer.getJdbcUrl();
    }

    String readerJdbcUrl() {
        return "jdbc:postgresql://" + reader.getHost() + ":" + reader.getMappedPort(5432) + "/" + DB;
    }

    String username() {
        return USER;
    }

    String password() {
        return PASSWORD;
    }

    @Override
    public void close() {
        try {
            reader.stop();
        } finally {
            try {
                writer.stop();
            } finally {
                network.close();
            }
        }
    }
}
