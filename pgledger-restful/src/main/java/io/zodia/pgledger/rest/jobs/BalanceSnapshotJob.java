package io.zodia.pgledger.rest.jobs;

import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.rest.PgLedgerServer;
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
        long rows = ledger.cutBalanceSnapshot(hour);
        PgLedgerServer.log.info("balance snapshot {} cut: {} rows", hour, rows);
    }
}
