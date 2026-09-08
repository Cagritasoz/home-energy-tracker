package com.cagritasoz.device_service.dto;

import com.cagritasoz.device_service.model.DeviceType;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@JsonPropertyOrder({"id", "deviceName", "deviceType", "location", "userId"})
public record DeviceDto( // All fields are private final by default, LOMBOK @Builder works on record classes.
        Long id,

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String deviceName,

        // Enum gives us validation for free, if deviceType field has a value not specified in the DeviceType enum class, HttpMessageNotReadableException is thrown.
        // That failure happens during JSON deserialization, before @Valid ever runs, so it can't
        // carry a per-field @NotNull-style message here - it surfaces via
        // GlobalExceptionHandler's HttpMessageNotReadableException handler instead (a generic
        // "Malformed request body." response, not a field-keyed validation message).
        DeviceType deviceType,

        @Size(max = 255, message = "must be at most 255 characters")
        String location,

        @NotNull(message = "must not be null")
        Long userId
) {
}
