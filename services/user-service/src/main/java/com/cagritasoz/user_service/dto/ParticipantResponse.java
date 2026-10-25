package com.cagritasoz.user_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;

// One service a deletion waits for (admin view). A row with neither timestamp is still pending, which is what an
// admin looks for when a deletion is stuck.
@Builder
@JsonPropertyOrder({"participant", "ackedAt", "waivedAt"})
public record ParticipantResponse(

        String participant,

        Instant ackedAt,

        Instant waivedAt

) {
}
