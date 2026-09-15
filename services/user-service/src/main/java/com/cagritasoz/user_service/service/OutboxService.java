package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.aspect.SkipLogging;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.model.OutboxAggregateType;
import com.cagritasoz.user_service.model.OutboxEventType;
import com.cagritasoz.user_service.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

// Writes rows to outbox_events - nothing more. Never touches Kafka; that's the relay's job.
// Intended to be called from inside UserService's own @Transactional methods - never call
// kafkaTemplate.send() directly instead of going through this, see V4's header comment for the
// dual-write problem that would reintroduce.
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper; // Map payload objects into JSON strings.

    // @Transactional here joins whatever transaction the caller already has open (Spring's
    // default REQUIRED propagation) rather than starting a separate one - it does not commit
    // independently. That's what makes the outbox insert and the users change it announces
    // atomic: both commit together, or neither does. Only correct when called from
    // inside an already-@Transactional method - it would still "work" standalone (REQUIRED starts
    // a new transaction if none exists), just without the atomicity guarantee that's the entire
    // point of this class.
    @Transactional
    public void recordEvent(OutboxAggregateType aggregateType,
                             Long aggregateId,
                             Long partitionKey,
                             OutboxEventType eventType,
                             Object payload) {

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .partitionKey(partitionKey)
                .eventType(eventType)
                .payload(objectMapper.writeValueAsString(payload))
                .build();

        outboxEventRepository.save(outboxEvent);
    }

    // Reads live here too, not just in OutboxRelay, so that class never needs
    // OutboxEventRepository at all - it only ever talks to this one façade for outbox_events.
    // Backed by V4's partial index (idx_outbox_events_pending); Pageable bounds the batch size so
    // a large backlog (e.g. after downtime) drains gradually across ticks instead of all at once.
    // @SkipLogging: called every relay tick regardless of whether there's anything pending -
    // unlike recordEvent above (one call per real user action), logging every invocation here
    // would just be noise on a fixed timer, not a meaningful event.
    @SkipLogging
    @Transactional(readOnly = true)
    public List<OutboxEvent> findPendingEvents(int batchSize) {
        return outboxEventRepository.findByPublishedAtIsNullOrderByIdAsc(PageRequest.of(0, batchSize));
    }

    // Deliberately a method on THIS bean, not OutboxRelay: OutboxRelay previously called this via
    // a plain self-invocation (this.markPublished(...)), which silently never went through the
    // Spring proxy that actually starts the transaction - @Transactional on a method only
    // activates when the call arrives from OUTSIDE the bean, through the proxy. The self-called
    // version ran findById() (transactional on its own, but committed/closed immediately),
    // returned an already-detached entity, then mutated it with nothing left to ever flush - the
    // row's published_at was never actually persisted, so the same row kept being found as
    // "pending" and re-published every tick, forever. Calling this from OutboxRelay (a genuinely
    // different bean) makes it a real proxied call, so @Transactional actually applies.
    // @SkipLogging: same reasoning as findPendingEvents - fires once per delivered row on every
    // relay tick, not once per meaningful user-triggered action.
    @SkipLogging
    @Transactional
    public void markPublished(Long id) {
        outboxEventRepository.findById(id)
                .ifPresent(event -> event.setPublishedAt(Instant.now()));
        // Managed entity - dirty checking flushes the UPDATE at commit, no save() call needed.
    }
}
