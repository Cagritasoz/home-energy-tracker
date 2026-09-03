CREATE TABLE devices (
    id BIGSERIAL PRIMARY KEY,
    device_name VARCHAR(100) NOT NULL,
    device_type VARCHAR CONSTRAINT chk_device_type CHECK (device_type IN (
        'SPEAKER', 'CAMERA', 'THERMOSTAT', 'LIGHT', 'LOCK', 'DOORBELL', 'FRIDGE',
        'WASHING_MACHINE', 'DISHWASHER', 'AIR_CONDITIONER', 'WATER_HEATER', 'EV_CHARGER'
    )),
    location VARCHAR(255),
    user_id BIGINT NOT NULL
);