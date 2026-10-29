package com.cagritasoz.user_service.deletion.ack;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// Checks, when the application starts, that every participant the configuration requires has a handler that can
// confirm it. Without this a typo in app.user.deletion.required-participants ("device") would be copied into every
// deletion's participant rows, nobody would ever confirm it, and every deletion would wait forever. A failed start is
// far cheaper than that.
//
// The name's format and duplicates are checked by DeletionPromotionService, which owns the list's use.
@Component
public class RequiredParticipantsValidator {

    public RequiredParticipantsValidator(@Value("${app.user.deletion.required-participants:}") List<String> required,
                                         ParticipantAcks acks) {

        List<String> unknown = new ArrayList<>(required);
        unknown.removeAll(acks.participants());

        if (!unknown.isEmpty()) {

            throw new IllegalStateException("app.user.deletion.required-participants lists " + unknown
                    + ", which have no ack handler (known participants: " + acks.participants() + ")");

        }

    }
}
