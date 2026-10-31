package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.deletion.DeletionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Checks, when the application starts, that every participant the configuration requires has a handler that can
// confirm it. Without this a typo in app.user.deletion.required-participants ("device") would be copied into every
// deletion's participant rows, nobody would ever confirm it, and every deletion would wait forever. A failed start is
// far cheaper than that.
//
// The name's format and duplicates are checked when DeletionProperties is bound.
@Component
@EnableConfigurationProperties(DeletionProperties.class)
public class RequiredParticipantsValidator {

    public RequiredParticipantsValidator(DeletionProperties properties, ParticipantAcks acks) {

        List<String> unknown = new ArrayList<>(properties.requiredParticipants());
        unknown.removeAll(acks.participants());

        if (!unknown.isEmpty()) {

            throw new IllegalStateException("app.user.deletion.required-participants lists " + unknown
                    + ", which have no ack handler (known participants: " + acks.participants() + ")");

        }

    }
}
