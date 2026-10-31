package com.cagritasoz.user_service.outbox.relay;

import com.cagritasoz.user_service.outbox.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxPurgeJob {

    private final OutboxRepository outboxRepository;

    private final int retentionDays;

    private final int batchSize;

    public OutboxPurgeJob(OutboxRepository outboxRepository, OutboxProperties properties) {
        this.outboxRepository = outboxRepository;
        this.retentionDays = properties.purge().retentionDays();
        this.batchSize = properties.purge().batchSize();
    }

    @Scheduled(cron = "${app.outbox.purge.cron}")
    public void purge() {

        int total = 0;
        int deleted;

        // Body executes at least once, condition "deleted == batchSize" is checked after the body.
        do {
            deleted = outboxRepository.deletePublishedOlderThan(retentionDays, batchSize);
            total += deleted;
        } while (deleted == batchSize); // Stop cron job deleting if deleted < batchSize, no more batches to work on.

        if (total > 0) {
            log.info("Outbox purge: deleted {} published rows older than {} days", total, retentionDays);
        }

    }
}
