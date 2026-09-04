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

        @NotBlank
        @Size(max = 100)
        String deviceName,

        // Enum gives us validation for free, if deviceType field has a value not specified in the DeviceType enum class, HttpMessageNotReadableException is thrown.
        DeviceType deviceType,

        @Size(max = 255)
        String location,

        @NotNull
        Long userId
) {
}
