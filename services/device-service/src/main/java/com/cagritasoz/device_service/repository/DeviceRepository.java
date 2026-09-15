package com.cagritasoz.device_service.repository;

import com.cagritasoz.device_service.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {

    // Id-only projection, not findAll() + stream().map() - avoids loading/hydrating every
    // Device entity just to discard everything but the id, for callers (usage-service's
    // device-id cache) that only need the id set.
    @Query("SELECT d.id FROM Device d")
    List<Long> findAllDeviceIds();
}
