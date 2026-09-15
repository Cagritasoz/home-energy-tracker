package com.cagritasoz.device_service.entity;

import com.cagritasoz.device_service.model.DeviceType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "devices")
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_name", length = 100, nullable = false)
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "device_type")
    private DeviceType deviceType;

    @Column(name = "location")
    private String location;

    @Column(name = "user_id", nullable = false)
    private Long userId;
}
