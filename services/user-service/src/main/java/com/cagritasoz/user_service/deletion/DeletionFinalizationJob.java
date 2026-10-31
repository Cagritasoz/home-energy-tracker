package com.cagritasoz.user_service.deletion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Finishes every deletion that is ready: Keycloak user gone for the quiet period, every required participant
// confirmed. Thin and not transactional, like DeletionPromotionJob: it asks DeletionFinalizationService for the ready
// accounts and finalizes them one by one, each in a transaction of its own, so one failing account is logged,
// counted and skipped instead of holding up the rest.
//
// fixedDelay, not fixedRate: a tick never overlaps the previous one in this instance. Another instance may run the
// same tick; the compare-and-set inside finalizeDeletion() lets exactly one of them finish each account.
@Slf4j
@Component
@EnableConfigurationProperties(DeletionProperties.class)
public class DeletionFinalizationJob {

    private final DeletionFinalizationService finalizationService;

    private final int batchSize;

    private final Counter finalizedCounter;

    private final Counter failedCounter;

    public DeletionFinalizationJob(DeletionFinalizationService finalizationService,
                                   MeterRegistry meterRegistry,
                                   DeletionProperties properties) {

        this.finalizationService = finalizationService;
        this.batchSize = properties.finalizer().batchSize();
        this.finalizedCounter = Counter.builder("user.deletion.finalized")
                .description("Accounts moved from DELETING to DELETED by this instance")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("user.deletion.finalization.failures")
                .description("Finalization that threw; the account stays DELETING and is retried on the next tick")
                .register(meterRegistry);

    }

    @Scheduled(fixedDelayString = "${app.user.deletion.finalizer.interval-ms}") // set to 3000, IDE displays as 86400000 for some reason.
    public void finalizeReady() {

        // One batch per tick, like the other jobs.
        List<UUID> ready = finalizationService.findReady(batchSize);

        for (UUID id : ready) {

            try {

                if (finalizationService.finalizeDeletion(id)) {

                    finalizedCounter.increment();
                    log.info("Deletion finalized for user {}", id);

                }

            }
            catch (RuntimeException e) {

                failedCounter.increment();

                // The class of the root cause only: a database error message can quote the row it rejected.
                log.error("Could not finalize the deletion of user {} ({}); retrying on the next tick",
                        id, NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());

            }

        }

    }
}
