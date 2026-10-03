package io.zodia.pgledger.rest;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * ShedLock wiring for HA scheduled jobs. Every replica runs the same schedule;
 * the lock table guarantees exactly one executes. Uses the writer pool because
 * the lock row is mutated by whoever holds the lock. KeepAlive refreshes the
 * lock while long archive batches run, so a slow batch never loses leadership
 * mid-run; if a replica dies the lock expires and another takes over.
 */
@Configuration(proxyBeanMethods = false)
class ShedLockConfiguration {
    private static final String LOCK_TABLE = "shedlock";

    @Bean
    LockProvider lockProvider(@Qualifier("writerDataSource") DataSource writer) {
        JdbcTemplateLockProvider delegate = new JdbcTemplateLockProvider(
                new JdbcTemplate(writer), LOCK_TABLE);
        return new KeepAliveLockProvider(delegate, scheduledExecutorService());
    }

    private static java.util.concurrent.ScheduledExecutorService scheduledExecutorService() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                task -> {
                    Thread thread = new Thread(task, "pgledger-shedlock-keepalive");
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
