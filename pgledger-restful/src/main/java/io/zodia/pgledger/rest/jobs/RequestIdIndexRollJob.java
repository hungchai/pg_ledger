package io.zodia.pgledger.rest.jobs;

import io.zodia.pgledger.rest.PgLedgerServer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Rolls the partial request_id index forward daily so the idempotency dedup
 * window stays at "now - retentionDays" while the index stays small. ShedLock
 * guarantees one replica does the CONCURRENTLY rebuild; the two-index overlap
 * window is safe because both predicates are correct.
 *
 * <p>Stateless: {@code retentionDays} is fixed config captured at startup; the
 * roll statement runs once per day off the hot path.
 */
@Component
public class RequestIdIndexRollJob {
    private final JdbcTemplate writer;
    private final int retentionDays;

    RequestIdIndexRollJob(@Qualifier("writerDataSource") DataSource writerDataSource,
                          @Value("${pgledger.request-id-retention-days:45}") int retentionDays) {
        this.writer = new JdbcTemplate(writerDataSource);
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${pgledger.roll-index-cron:0 47 4 * * *}")
    @SchedulerLock(name = "pgledger-request-id-roll", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void roll() {
        writer.execute("SELECT pgledger_roll_request_id_index(" + retentionDays + ")");
        PgLedgerServer.log.info("request_id index rolled: retention {}d", retentionDays);
    }
}
