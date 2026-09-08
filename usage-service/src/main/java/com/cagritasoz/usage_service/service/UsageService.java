package com.cagritasoz.usage_service.service;

import com.cagritasoz.usage_service.cache.DeviceIdCache;
import com.cagritasoz.usage_service.event.EnergyUsageEvent;
import com.influxdb.v3.client.InfluxDBClient;
import com.influxdb.v3.client.Point;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UsageService {

    private final InfluxDBClient influxDBClient;
    private final DeviceIdCache deviceIdCache;

    // This method runs on ONE dedicated consumer thread per container (Boot's
    // default listener concurrency is 1), not a new thread per message and not a thread pool -
    // the same thread repeatedly polls Kafka and invokes this method synchronously per record.
    // A slow write here directly throttles how fast the topic drains, and a retry backoff pauses
    // that same thread entirely - see the gaps noted in KafkaErrorHandlingConfig.
    @KafkaListener(topics = "${app.kafka.topic.energy-usage}", groupId = "${spring.kafka.consumer.group-id}")
    public void handleEnergyUsageEvent(EnergyUsageEvent event) {

        // Never rejected/dropped for an unknown device, only tagged - the cache is advisory
        // (eventually consistent with device-service, see DeviceIdCache), and dropping real
        // telemetry over a temporarily-stale cache would lose data for what's very likely a
        // perfectly valid device. deviceKnown=false can be reconciled later.
        boolean deviceKnown = deviceIdCache.isKnown(event.deviceId());

        Point point = Point.measurement("energy_readings")
                .setTag("deviceId", String.valueOf(event.deviceId()))
                .setField("consumedEnergy", event.consumedEnergy())
                .setField("deviceKnown", deviceKnown)
                .setTimestamp(event.timestamp());

        // Deliberately not caught: letting this propagate is what lets the container's
        // DefaultErrorHandler (KafkaErrorHandlingConfig) retry a transient InfluxDB write
        // failure and only then route it to the DLQ once retries are exhausted.
        //
        // Strength: safe to blindly retry - a point write is keyed by measurement+tags+time, so
        // resending the identical point on retry overwrites the same row rather than creating a
        // duplicate. Not every retried operation gets this for free (e.g. a payment charge).
        influxDBClient.writePoint(point);
    }
}
