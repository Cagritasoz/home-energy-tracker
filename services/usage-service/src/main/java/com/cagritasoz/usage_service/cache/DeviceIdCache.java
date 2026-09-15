package com.cagritasoz.usage_service.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Set;

// Advisory, not authoritative: a stale/empty snapshot never blocks ingestion, it only affects
// the deviceKnown field written alongside each point (see UsageService). volatile reference
// swap - not a mutable shared Set - so the Kafka listener thread always reads a fully-populated,
// immutable snapshot without synchronizing against the scheduler thread that refreshes it.
@Component
@Slf4j
@RequiredArgsConstructor
public class DeviceIdCache {

    private final RestClient deviceServiceRestClient;

    private volatile Set<Long> knownDeviceIds = Set.of();

    @Scheduled(initialDelay = 0, fixedDelayString = "${device-service.device-id-cache.refresh-interval-ms}")
    public void refresh() {
        try {
            List<Long> ids = deviceServiceRestClient.get()
                    .uri("/api/v1/devices/ids")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Long>>() {
                    });
            knownDeviceIds = ids == null ? Set.of() : Set.copyOf(ids);
        } catch (RestClientException e) {
            // Keep serving the last good snapshot rather than wiping it to empty - a transient
            // device-service blip shouldn't suddenly mark every device "unknown". Already
            // handled, not a crash - so just e.getMessage(), not the full exception (passing e
            // itself would print a full stack trace every refresh-interval device-service is
            // down, which reads exactly like an uncaught crash even though it isn't one).
            log.warn("Failed to refresh device id cache from device-service; keeping previous snapshot of {} ids ({})", knownDeviceIds.size(), e.getMessage());
        }
    }

    public boolean isKnown(Long deviceId) {
        return knownDeviceIds.contains(deviceId);
    }
}
