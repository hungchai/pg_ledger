package io.zodia.pgledger.rest;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class
})
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class PgLedgerServerMain {
    public static void main(String[] args) {
        SpringApplication.run(PgLedgerServerMain.class, args);
    }
}
