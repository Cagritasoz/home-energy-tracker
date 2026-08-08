package com.cagritasoz.user_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // BIGSERIAL
    private Long id;

    @Column(name = "first_name", length = 100, nullable = false)
    private String firstName;

    @Column(name = "last_name", length = 100, nullable = false)
    private String lastName;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "alerts_enabled", nullable = false)
    private Boolean alertsEnabled = false; // Defaults to false.

    @Column(name = "energy_alerting_threshold", nullable = false)
    private Double energyAlertingThreshold = 0.0; // Defaults to 0.0.
}
