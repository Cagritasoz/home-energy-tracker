package com.cagritasoz.device_service.service;

import com.cagritasoz.device_service.dto.DeviceDto;
import com.cagritasoz.device_service.entity.Device;
import com.cagritasoz.device_service.exception.DeviceNotFoundException;
import com.cagritasoz.device_service.exception.DeviceOwnerImmutableException;
import com.cagritasoz.device_service.exception.UserNotFoundException;
import com.cagritasoz.device_service.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DeviceService {

    private final DeviceRepository deviceRepository;
    private final RestClient userServiceRestClient;

    @Transactional
    public DeviceDto createDevice(DeviceDto deviceDto) {

        if (!userExists(deviceDto.getUserId())) {
            throw new UserNotFoundException("User not found!");
        }

        final Device device = Device.builder()
                .deviceName(deviceDto.getDeviceName())
                .deviceType(deviceDto.getDeviceType())
                .location(deviceDto.getLocation())
                .userId(deviceDto.getUserId())
                .build();

        final Device saved = deviceRepository.save(device);

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public DeviceDto getDeviceById(Long id) {

        final Device foundDevice = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found!"));

        return toDto(foundDevice);
    }

    @Transactional
    public DeviceDto updateDevice(Long id, DeviceDto deviceDto) {

        final Device foundDevice = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found!"));

        boolean userIdChanged = !foundDevice.getUserId().equals(deviceDto.getUserId());
        if(userIdChanged) {
            throw new DeviceOwnerImmutableException("User ID can not change!");
        }

        foundDevice.setDeviceName(deviceDto.getDeviceName());
        foundDevice.setDeviceType(deviceDto.getDeviceType());
        foundDevice.setLocation(deviceDto.getLocation());

        return toDto(foundDevice);
    }

    @Transactional
    public void deleteDevice(Long id) {

        final Device foundDevice = deviceRepository.findById(id)
                .orElseThrow(() -> new DeviceNotFoundException("Device not found!"));

        deviceRepository.delete(foundDevice);

    }

    // user-service being unreachable (connection refused/timeout) throws ResourceAccessException,
    // not HttpClientErrorException.NotFound, so it isn't caught below - it's left to propagate
    // and is turned into a 503 by GlobalExceptionHandler instead.
    private boolean userExists(Long userId) {
        try {
            userServiceRestClient.get()
                    .uri("/api/v1/users/{id}", userId)
                    .retrieve()
                    .toBodilessEntity(); // Bodiless on purpose - we only care whether user-service has this id, not how the user dto looks like.
            return true; // No exception thrown, user exists.
        } catch (HttpClientErrorException.NotFound e) {
            // retrieve() throws HttpClientErrorException for any 4xx response; .NotFound is
            // the typed subclass Spring provides specifically for 404, so we only catch that
            // one case (user genuinely doesn't exist) instead of matching every 4xx as "missing".
            return false; // 404 status code, user actually does not exist.
        }
    }

    private DeviceDto toDto(Device device) {
        return DeviceDto.builder()
                .id(device.getId())
                .deviceName(device.getDeviceName())
                .deviceType(device.getDeviceType())
                .location(device.getLocation())
                .userId(device.getUserId())
                .build();
    }
}
