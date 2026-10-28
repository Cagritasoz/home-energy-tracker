package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakCircuitOpenException;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

// Deletes the Keycloak user of every account that has reached DELETING. Thin and not transactional, like the
// promoter: it asks KeycloakDeletionService for the pending accounts and handles them one by one, so a failing account
// does not hold up the others.
//
// Keycloak being down never fails a user's request, because the account's row was committed before this job ever sees
// it; the job simply keeps trying. Two outcomes stop a tick early or not at all:
//   - an open circuit breaker: every remaining call would be refused, so the tick ends at the first one (that is what
//     the breaker is for) and the gauge keycloak.admin.circuit.state shows why;
//   - any other failure on one account: counted, logged, and the loop goes on with the next account.
//
// fixedDelay, not fixedRate, so a tick never overlaps the previous one in this instance.
@Slf4j
@Component
public class KeycloakDeletionJob {

    private final KeycloakDeletionService deletionService;

    private final int batchSize;

    private final Counter deletedCounter;

    private final Counter failedCounter;

    public KeycloakDeletionJob(KeycloakDeletionService deletionService,
                               MeterRegistry meterRegistry,
                               @Value("${app.user.deletion.keycloak.batch-size}") int batchSize) {

        if (batchSize < 1) {

            throw new IllegalArgumentException("app.user.deletion.keycloak.batch-size must be at least 1 but was " + batchSize);

        }

        this.deletionService = deletionService;
        this.batchSize = batchSize;
        this.deletedCounter = Counter.builder("user.deletion.keycloak.deleted")
                .description("Accounts whose Keycloak user this instance deleted and recorded")
                .register(meterRegistry);
        this.failedCounter = Counter.builder("user.deletion.keycloak.failures")
                .description("Keycloak deletions that failed; the account stays pending and is retried on the next tick")
                .register(meterRegistry);

    }

    @Scheduled(fixedDelayString = "${app.user.deletion.keycloak.interval-ms}")
    public void deleteDue() {

        List<UUID> pending = deletionService.findPending(batchSize);

        for (UUID id : pending) {

            try {

                if (deletionService.deleteFromKeycloak(id)) {

                    deletedCounter.increment();
                    log.info("Keycloak user deleted for account {}", id);

                }

            }
            catch (KeycloakCircuitOpenException e) {

                // Not counted as a failure of this account: it was not tried. The rest would be refused as well.
                log.warn("Keycloak's circuit breaker is open; ending this tick with {} accounts still pending", pending.size());

                return; // stop the tick.

            }
            catch (KeycloakUnavailableException e) {

                failedCounter.increment();

                // The message is written by our client and holds no data from Keycloak's answer, so it is safe to log.
                log.error("Could not delete the Keycloak user of account {} ({}); retrying on the next tick", id, e.getMessage());

            }
            catch (RuntimeException e) {

                failedCounter.increment();

                // Anything else is most likely the database; its message can quote a row, so only the class is logged.
                log.error("Could not record the Keycloak deletion of account {} ({}); retrying on the next tick",
                        id, NestedExceptionUtils.getMostSpecificCause(e).getClass().getName());

            }

        }

    }
}
