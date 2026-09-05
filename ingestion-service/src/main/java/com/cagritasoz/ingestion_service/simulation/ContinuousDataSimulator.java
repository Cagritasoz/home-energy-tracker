package com.cagritasoz.ingestion_service.simulation;

import com.cagritasoz.ingestion_service.dto.EnergyUsageDto;
import com.cagritasoz.ingestion_service.service.IngestionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// Generates fake EnergyUsageDto traffic on a schedule to simulate a fleet of IoT devices
// sending readings, so there's realistic volume flowing through Kafka to test against.

// Calls IngestionService directly (a plain method call, not HTTP) rather than sending itself
// requests over REST - a self-HTTP-calling simulator would share the same Tomcat thread pool
// and JVM as the thing it's supposed to be generating load against, so it'd partly be measuring
// self-contention rather than real external load. For genuinely load-testing the HTTP layer
// itself, a real external tool (k6, Gatling, JMeter, or even a standalone script hitting this
// service from outside the process) is the correct tool - not something built into the service
// under test.

// ingestion-service deliberately does NOT check whether each simulated deviceId actually exists in device-service
// before sending. A synchronous REST call per event here would reintroduce the exact throughput/
// coupling problem already discussed for ingestEnergyUsage() itself - at this volume, that check
// doesn't belong on the hot path at all. If it's ever needed, a local cache (e.g. Redis, or an
// in-memory cache refreshed periodically / from a device-service event stream) is the right
// shape for it - not a live REST call per request.

// TODO: Simulation with multiple threads might be implemented.
@Component
@RequiredArgsConstructor
public class ContinuousDataSimulator {

    private final IngestionService ingestionService;

    // Has its own initializer, so @RequiredArgsConstructor leaves it alone - it only turns
    // blank final fields (no initializer) into constructor parameters, not ones already set.
    private final Random random = new Random();

    // A handful of events per tick is enough to produce visible, sustained volume through Kafka
    // at the 1s tick rate without this being a real load-test tool. (see the class comment above
    // on why a standalone tool is the right place for that). Not wired to an application.properties
    // key like simulation.interval-ms is - that property is a real operational knob (wall-clock
    // cadence), this is just an internal simulator constant with no reason to change without a
    // rebuild, so giving it a property would only add config surface for nothing.
    private static final int EVENTS_PER_TICK = 5;
    private static final double MIN_CONSUMED_ENERGY = 0.05;
    private static final double MAX_CONSUMED_ENERGY = 2.0;

    // Fixed pool of device ids the simulator repeatedly samples from, rather than generating a
    // fresh id per event - this makes the stream look like a stable fleet of devices reporting
    // repeatedly, not a Firehose of one-off unknown devices.
    private final List<Long> deviceIdPool = new ArrayList<>();

    // Not a @Bean: nothing else in the app needs this list, and unlike KafkaTopicConfig's
    // NewTopic/RestClient beans this isn't a shared infrastructure resource - it's state private
    // to this one component. Not a CommandLineRunner either, for the same reason. @PostConstruct
    // runs after Lombok's generated constructor has already wired ingestionService, so it can add
    // this extra piece of state without displacing @RequiredArgsConstructor - the same way the
    // Random field above is added by hand alongside the generated constructor.

    // The range is hardcoded to roughly device-service's current dev DB size rather than fetched
    // from device-service at startup - the same reasoning that rules out a live per-event REST
    // check above rules out a live fetch of the id set too. Device ids are BIGSERIAL and never
    // reclaimed after deletes, so some ids in this range will not exist in device-service's DB -
    // that's intentional, not a bug: it's a stand-in for the "unknown device" case a future
    // usage-service consumer needs to handle anyway, not something this simulator should avoid.
    @PostConstruct
    void initDeviceIdPool() {
        for (long id = 1; id <= 215; id++) {
            deviceIdPool.add(id);
        }
    }

    // @Scheduled already resolves ${...} placeholders on its own - no @Value needed here.
    @Scheduled(fixedDelayString = "${simulation.interval-ms}") // Single threaded by default, does not use Tomcat threads.
    public void sendMockData() {
        for (int i = 0; i < EVENTS_PER_TICK; i++) {
            final Long deviceId = deviceIdPool.get(random.nextInt(deviceIdPool.size()));

            final EnergyUsageDto dto = EnergyUsageDto.builder()
                    .deviceId(deviceId)
                    .consumedEnergy(MIN_CONSUMED_ENERGY + random.nextDouble() * (MAX_CONSUMED_ENERGY - MIN_CONSUMED_ENERGY)) // Falls in interval [MIN_C_E, MAX_C_E]
                    .timestamp(Instant.now())
                    .build();

            ingestionService.ingestEnergyUsage(dto);
        }
    }

}
