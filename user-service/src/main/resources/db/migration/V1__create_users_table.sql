CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    address TEXT,
    alerts_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    energy_alerting_threshold DOUBLE PRECISION NOT NULL DEFAULT 0
);