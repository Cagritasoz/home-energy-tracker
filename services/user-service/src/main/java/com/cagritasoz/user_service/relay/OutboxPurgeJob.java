package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OutboxPurgeJob {

    private final OutboxRepository outboxRepository;

    private final int retentionDays;

    private final int batchSize;

    public OutboxPurgeJob(OutboxRepository outboxRepository,
                          @Value("${app.outbox.purge.retention-days}") int retentionDays,
                          @Value("${app.outbox.purge.batch-size}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
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
