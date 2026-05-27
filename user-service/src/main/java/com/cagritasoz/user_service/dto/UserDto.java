package com.cagritasoz.user_service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserDto {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String address;
    private boolean alertsEnabled;
    private double energyAlertingThreshold;
}
