package com.cagritasoz.user_service.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Deletes old rows of processed_events once a day, in batches, like the outbox's purge. The retention matches the
// longest a redelivery can still arrive (the topics' retention), so a row this old can never be needed again.
@Slf4j
@Component
public class ProcessedEventsPurgeJob {

    private final ProcessedEvents processedEvents;

    private final int retentionDays;

    private final int batchSize;

    public ProcessedEventsPurgeJob(ProcessedEvents processedEvents,
                                   @Value("${app.processed-events.purge.retention-days}") int retentionDays,
                                   @Value("${app.processed-events.purge.batch-size}") int batchSize) {

        if (retentionDays < 1 || batchSize < 1) {

            throw new IllegalArgumentException("app.processed-events.purge.retention-days and .batch-size must be at least 1");

        }

        this.processedEvents = processedEvents;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;

    }

    @Scheduled(cron = "${app.processed-events.purge.cron}")
    public void purge() {

        int total = 0;
        int deleted;

        // Runs at least once; a batch that comes back short means nothing older is left.
        do {

            deleted = processedEvents.purgeOlderThan(retentionDays, batchSize);
            total += deleted;

        } while (deleted == batchSize);

        if (total > 0) {

            log.info("Processed-events purge: deleted {} rows older than {} days", total, retentionDays);

        }

    }
}
