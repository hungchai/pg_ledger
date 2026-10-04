package io.zodia.pgledger.rest.jobs;

import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.rest.PgLedgerServer;
import io.zodia.pgledger.rest.web.WriteRoutes;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Hourly balance snapshot job. Runs at the top of every hour across all
 * replicas; ShedLock guarantees exactly one replica cuts the snapshot for the
 * just-finished hour (UTC). Re-runs for the same hour are idempotent: rows for
 * that hour are replaced inside one transaction. Disable with an empty
 * PGLEDGER_SNAPSHOT_CRON.
 *
 * <p>Both the cut and the partition roll are SELECT-invoked functions that
 * write; {@link WriteRoutes#onWriter} forces them onto the primary or
 * ShardingSphere routes them to the read-only replica, which fails.
 */
@Component
public class BalanceSnapshotJob {
    private final PgLedger ledger;

    BalanceSnapshotJob(PgLedger ledger) {
        this.ledger = ledger;
    }

    @Scheduled(cron = "${pgledger.snapshot-cron:0 5 * * * *}")
    @SchedulerLock(name = "pgledger-balance-snapshot", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cutHourly() {
        // Five past the hour: snapshot the hour that just closed (hh:00 UTC).
        Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS);
        long rows = WriteRoutes.onWriter(() -> ledger.cutBalanceSnapshot(hour));
        PgLedgerServer.log.info("balance snapshot {} cut: {} rows", hour, rows);
    }

    /** Daily: keep twelve months of snapshot partitions pre-created. */
    @Scheduled(cron = "${pgledger.snapshot-partition-cron:0 10 0 * * *}")
    @SchedulerLock(name = "pgledger-snapshot-partitions", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void rollPartitions() {
        int created = WriteRoutes.onWriter(() -> ledger.ensureSnapshotPartitions(Instant.now(), 12));
        PgLedgerServer.log.info("snapshot partition roll: {} partitions created", created);
    }
}
