package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.contracts.device.DeviceEventType;
import com.cagritasoz.contracts.device.DeviceEvents;
import com.cagritasoz.contracts.device.UserDevicesUnclaimedData;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// The participants that exist. The bean's name, participantAcks, is what the listener's topic expression refers to.
@Configuration
public class ParticipantAcksConfig {

    @Bean
    ParticipantAcks participantAcks() {

        return new ParticipantAcks(List.of(

                // device-service: "every device this user had claimed is released, its credentials revoked, and
                // new claims for the user are refused".
                new ParticipantAck("devices", DeviceEvents.TOPIC,
                        DeviceEventType.USER_DEVICES_UNCLAIMED.value(), UserDevicesUnclaimedData.SCHEMA_VERSION)

        ));

    }
}
