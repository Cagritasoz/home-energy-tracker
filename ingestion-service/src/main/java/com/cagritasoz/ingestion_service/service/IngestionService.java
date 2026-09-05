package com.cagritasoz.ingestion_service.service;

import com.cagritasoz.ingestion_service.dto.EnergyUsageDto;
import com.cagritasoz.ingestion_service.event.EnergyUsageEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IngestionService {

    private final KafkaTemplate<Long, EnergyUsageEvent> kafkaTemplate;
    private final NewTopic energyUsageTopic;

    // Handwritten rather than using @RequiredArgsConstructor: by default, Lombok does not
    // copy @Qualifier from a field onto the corresponding generated constructor parameter.
    // Since constructor injection is preferred, the @Qualifier must be present on
    // the constructor parameter so Spring knows which NewTopic bean to inject.
    // Lombok can be configured with lombok.copyableAnnotations to copy @Qualifier, but
    // writing the constructor explicitly is simpler for this case, using @Qualifier and @Autowired
    // for field injection would have also worked.
    public IngestionService(KafkaTemplate<Long, EnergyUsageEvent> kafkaTemplate,
                             @Qualifier("energyUsageTopic") NewTopic energyUsageTopic) {
        this.kafkaTemplate = kafkaTemplate;
        this.energyUsageTopic = energyUsageTopic;
    }

    public void ingestEnergyUsage(EnergyUsageDto energyUsageDto) {

        final EnergyUsageEvent event = EnergyUsageEvent.builder()
                .deviceId(energyUsageDto.deviceId())
                .consumedEnergy(energyUsageDto.consumedEnergy())
                .timestamp(energyUsageDto.timestamp())
                .build();

        // Not blocked on: this is telemetry ingestion, so HTTP
        // response latency shouldn't wait on broker ack - failures are logged asynchronously
        // instead of being surfaced to the caller.
        kafkaTemplate.send(energyUsageTopic.name(), event.deviceId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish energy usage event for device {}", event.deviceId(), ex);
                    }
                });
    }
}
