package com.cagritasoz.device_service.dto;

import com.cagritasoz.device_service.model.DeviceType;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonPropertyOrder({"id", "deviceName", "deviceType", "location", "userId"})
public class DeviceDto {

    private Long id;

    @NotBlank
    @Size(max = 100)
    private String deviceName;

    private DeviceType deviceType;

    @Size(max = 255)
    private String location;

    @NotNull
    private Long userId;

}
