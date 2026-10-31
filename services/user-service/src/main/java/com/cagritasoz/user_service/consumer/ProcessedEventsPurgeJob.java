package com.cagritasoz.user_service.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Deletes old rows of processed_events once a day, in batches, like the outbox's purge. The retention matches the
// longest a redelivery can still arrive (the topics' retention), so a row this old can never be needed again.
@Slf4j
@Component
@EnableConfigurationProperties(ProcessedEventsProperties.class)
public class ProcessedEventsPurgeJob {

    private final ProcessedEvents processedEvents;

    private final int retentionDays;

    private final int batchSize;

    public ProcessedEventsPurgeJob(ProcessedEvents processedEvents, ProcessedEventsProperties properties) {

        this.processedEvents = processedEvents;
        this.retentionDays = properties.purge().retentionDays();
        this.batchSize = properties.purge().batchSize();

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
