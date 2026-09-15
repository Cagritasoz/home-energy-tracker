package com.cagritasoz.device_service.controller;

import com.cagritasoz.device_service.dto.DeviceDto;
import com.cagritasoz.device_service.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    // Literal "/ids" beats the "/{id}" pattern for its own matches - Spring MVC always prefers
    // the more specific static segment, so there's no route collision here.
    @GetMapping("/ids")
    public ResponseEntity<List<Long>> getAllDeviceIds() {
        return ResponseEntity.ok(deviceService.getAllDeviceIds());
    }

    @PostMapping
    public ResponseEntity<DeviceDto> createDevice(@Valid @RequestBody DeviceDto deviceDto)  {

        DeviceDto createdDevice = deviceService.createDevice(deviceDto);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdDevice);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceDto> getDeviceById(@PathVariable  Long id){

        DeviceDto foundDevice = deviceService.getDeviceById(id);

        return ResponseEntity.ok(foundDevice);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DeviceDto> updateDevice(@PathVariable Long id,
                                                  @Valid @RequestBody DeviceDto deviceDto) {

        DeviceDto updatedDevice = deviceService.updateDevice(id, deviceDto);

        return ResponseEntity.ok(updatedDevice);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDevice(@PathVariable Long id) {

        deviceService.deleteDevice(id);

        return ResponseEntity.noContent().build();

    }
}
