package com.cagritasoz.user_service.deletion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Starts the deletion of every account whose grace period is over. Deliberately thin and not transactional: it asks
// DeletionPromotionService for the overdue accounts and promotes them one by one, each in a transaction of its own
// (the service is a separate bean so that its @Transactional methods go through Spring's proxy).
//
// One transaction per account, not one for the batch, so that an account that cannot be promoted does not hold up
// the others: its failure is logged and counted, and the loop moves on. It is picked up again on the next tick,
// first in line because the list is ordered by deadline - the failures counter is what raises the alarm.
//
// fixedDelay, not fixedRate: a tick never overlaps the previous one in this instance. Another instance may run
// the same tick at the same time; the two end up promoting disjoint accounts or losing a row to each other, which
// the compare-and-set inside promote() makes harmless.
@Slf4j
@Component
public class DeletionPromotionJob {

    private final DeletionPromotionService promotionService;

    private final int batchSize;

    private final Counter promotedCounter;

    private final Counter failedCounter;

    public DeletionPromotionJob(DeletionPromotionService promotionService,
                               MeterRegistry meterRegistry,
                               @Value("${app.user.deletion.promoter.batch-size}") int batchSize) {

        if (batchSize < 1) {

            throw new IllegalArgumentException("app.user.deletion.promoter.batch-size must be at least 1 but was " + batchSize);

        }

        this.promotionService = promotionService;
        this.batchSize = batchSize;
        this.promotedCounter = Counter.builder("user.deletion.promoted")
                .description("Accounts moved from DELETION_SCHEDULED to DELETING by this instance")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("user.deletion.promotion.failures")
                .description("Promotions that threw; the account stays DELETION_SCHEDULED and is retried on the next tick")
                .register(meterRegistry);

    }

    @Scheduled(fixedDelayString = "${app.user.deletion.promoter.interval-ms}")
    public void promoteDue() {

        // One batch per tick: the backlog after a long outage drains over a few ticks, and a batch that fails on
        // every row cannot turn into an endless loop.
        List<UUID> due = promotionService.findDue(batchSize);

        for (UUID id : due) {

            try {

                if (promotionService.promote(id)) {

                    promotedCounter.increment();
                    log.info("Deletion started for user {}", id);

                }

            }
            catch (RuntimeException e) {

                failedCounter.increment();

                // The class of the root cause only, never the exception itself: a database error message can quote
                // the row it rejected, and that row holds the user's email address.
                log.error("Could not start the deletion of user {} ({}); retrying on the next tick",
                        id, NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());

            }

        }

    }
}
