# Home Energy Tracker

An event-driven microservice system for tracking home energy consumption from IoT devices, built with **Java 21, Spring Boot 4 and Kafka**.

Smart meters and plugs report how much energy they have used. This system ingests those readings, turns them into consumption data per device and per user, and raises alerts when usage looks wrong. It is split into independent services that communicate through Kafka events, each with its own database. **`user-service` is implemented**. The other services are either prototypes that are being rebuilt or planned. The [status table](#services-and-status) says which is which.

## Contents

- [Services and status](#services-and-status)
- [user-service](#user-service) (implemented)
- [Run user-service locally](#run-user-service-locally)
- [Testing](#testing)
- [Other services](#other-services-planned-or-being-rebuilt)
- [Repository layout](#repository-layout)
- [Tech stack](#tech-stack)

## Services and status

| Service | Purpose | Port | Status |
|---|---|---|---|
| **user-service** | User accounts, profile, GDPR-style account deletion, user events | 8080 | **Implemented**, see below. Account deletion is partly done |
| device-service | Device registry, per-device tokens, device events | 8081 | Prototype exists, **to be rebuilt next** |
| ingestion-service | Receives readings from devices and publishes them to Kafka | 8082 | Prototype exists, to be rebuilt |
| usage-service | Turns readings into consumption data in InfluxDB | 8083 | Prototype exists, to be rebuilt |
| alert-service | Usage alert rules, evaluated over time windows | planned | Not yet implemented |
| notification-service | Delivers alerts to users (queue + retries) | planned | Not yet implemented |
| ai-insight-service | AI explanations of consumption, via read-only tools | planned | Not yet implemented |
| api-gateway | Single entry point: routing, auth, rate limiting (Spring Cloud Gateway) | planned | Not yet implemented |

Ports are the current ones. They will be renumbered when the gateway arrives.

---

## user-service

The owner of "who is this user". It is the only service that talks to the identity provider about account lifecycle, and it publishes everything that happens to a user as events for the other services.

### What it does

- **Identity comes from Keycloak.** There is no sign-up or password code here. The service is an OAuth2 resource server and validates the JWT: issuer, audience (`aud` must contain `user-service`) and realm roles (`USER`, `ADMIN`).
- **Just-in-time provisioning.** The first authenticated request from a user creates their row, with the JWT `sub` as the primary key, and emits `UserRegistered`. This is race-safe (`INSERT ... ON CONFLICT DO NOTHING`). If two requests race, only the winner emits the event. A verified email change in Keycloak is picked up on the next request and emits `UserUpdated`.
- **Self-service API.** View and update your profile, request deletion of your account.
- **Admin API.** Paged read-only listing of users.
- **Account deletion** is a multi-service saga (choreography, no central orchestrator). `DELETE /me` moves the account to `DELETING` and emits `UserDeletionRequested`. The remaining steps (Keycloak disable and delete, device cleanup, scrubbing personal data) are not implemented yet. Any account that is not `ACTIVE` gets `410 Gone` on every endpoint.
- **Errors are RFC 9457 problem details**, with a stable `type`, `status`, `detail`, `instance` and `timestamp`. That includes 401 and 403 responses from the security layer.

### API

| Method | Path | Role | What |
|---|---|---|---|
| `GET` | `/api/v1/users/me` | any authenticated | Own profile. Creates the account on first call. |
| `PATCH` | `/api/v1/users/me` | any authenticated | Update `displayName` (≤ 100 chars) and/or `timezone` (a valid Java zone id like `Europe/Istanbul`). Optimistic locking returns `409` on a conflicting edit. |
| `DELETE` | `/api/v1/users/me` | any authenticated | Start account deletion. Returns `202`, idempotent. |
| `GET` | `/api/v1/admin/users` | `ADMIN` | Paged list (`page`, `size` ≤ 100). |
| `GET` | `/api/v1/admin/users/{id}` | `ADMIN` | One user, including lifecycle fields. |
| `GET` | `/actuator/health` | public | Health. |

A user can only ever reach their own row: identity comes from the token's `sub`, never from the URL or the body.

### Events published

Topic `user.events.v1`, keyed by user id, JSON envelope defined in the shared [`contracts`](contracts) module (`event_id`, `event_type`, `schema_version` headers).

| Event | When |
|---|---|
| `UserRegistered` | First time a user is seen |
| `UserUpdated` | Profile or verified email changed |
| `UserDeletionRequested` | `DELETE /me` accepted |

### How the reliability works

The interesting parts of this service are in how it keeps its database and Kafka consistent. Both are covered by tests that run against real Postgres and Kafka containers.

- **Transactional outbox.** The business change and its event are written in the *same* database transaction. A relay then publishes outbox rows to Kafka. A crash can delay an event but never lose it or emit one for a rolled-back change. Design notes: [`docs/design/01-outbox-design.md`](docs/design/01-outbox-design.md).
- **A relay that is safe to run many times.** A Postgres advisory lock lets only one instance publish at a time. Rows are sent as a pipelined batch, and only the acknowledged prefix is marked as published. If Kafka is down, the relay backs off exponentially (4 s up to 30 s) and resumes in order when it recovers. It never skips a row. Events can arrive late or twice, but not never.
- **Operable.** Micrometer gauges for pending rows, the age of the oldest pending row (the stall alarm) and the consecutive failed ticks. Published rows are purged after 7 days.
- **Compare-and-set state changes.** Every status change is `UPDATE ... WHERE status = '<expected>'`, and it only emits an event if a row was actually changed. Concurrent requests cannot double-emit.
- **The database owns time.** `created_at` has a default and `updated_at` is stamped by a trigger. Event timestamps come from the row as the database stored it.
- **Idempotent consumers** are prepared with a `processed_events` table. No consumer uses it yet, because user-service does not consume anything until the deletion saga lands.

---

## Run user-service locally

### Prerequisites

- **JDK 21** (the Maven wrapper is included, so no separate Maven install is needed)
- **Docker** with Compose
- A hosts-file entry `127.0.0.1 keycloak`. Tokens are issued for the issuer `http://keycloak:8180`, so the name has to resolve from your machine.
  - Windows: add the line to `C:\Windows\System32\drivers\etc\hosts` (as administrator).
  - Linux/macOS: add it to `/etc/hosts`.

### 1. Start the infrastructure

```bash
cd infra
cp .env.example .env          # local-only throwaway passwords; .env is gitignored
docker compose --profile kafka up -d
```

Compose starts three services by default: the Postgres database of user-service (`user-db`), the Postgres database of Keycloak (`keycloak-db`) and Keycloak itself, with a pre-seeded realm and two test users. `--profile kafka` adds a single-node Kafka (KRaft) with its topics, plus Kafka UI at http://localhost:8070. Wait until `docker compose ps` shows Keycloak as healthy. The first start takes a minute.

With plain `docker compose up -d` (no profile) the service still works and serves its API, but events pile up in the outbox table until Kafka is available.

### 2. Run the service

`.env` is only read by Docker, so export the database password in your shell. It is the same value as in `.env`.

```powershell
# PowerShell
$env:USER_DB_PASSWORD = "password"
.\mvnw.cmd -pl services/user-service -am spring-boot:run
```

```bash
# bash
export USER_DB_PASSWORD=password
./mvnw -pl services/user-service -am spring-boot:run
```

Flyway creates the schema on startup. The service listens on **http://localhost:8080**. Health check: http://localhost:8080/actuator/health.

### 3. Call it

The realm has two seeded dev users:

| User | Password | Role |
|---|---|---|
| `testuser@example.com` | `Testpassword1` | `USER` |
| `admin@example.com` | `Adminpassword1` | `ADMIN` |

```powershell
# PowerShell: the helper script prints an access token (valid 5 minutes)
$token = .\scripts\get-token.ps1                 # or: -Role admin
Invoke-RestMethod http://localhost:8080/api/v1/users/me -Headers @{ Authorization = "Bearer $token" }
```

```bash
# any shell: ask Keycloak directly
TOKEN=$(curl -s -X POST http://keycloak:8180/realms/energy-tracker/protocol/openid-connect/token \
  -d grant_type=password -d client_id=local-dev -d client_secret=secret \
  -d username=testuser@example.com -d password=Testpassword1 | jq -r .access_token)

curl -s http://localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN"

curl -s -X PATCH http://localhost:8080/api/v1/users/me \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"displayName":"Arthur Morgan","timezone":"Europe/Istanbul"}'
```

Response of `GET /api/v1/users/me`:

```json
{
  "id": "11111111-1111-1111-1111-111111111111",
  "email": "testuser@example.com",
  "displayName": "testuser",
  "timezone": "UTC",
  "createdAt": "2026-10-10T09:00:00Z",
  "updatedAt": "2026-10-10T09:00:00Z"
}
```

To see the events, open Kafka UI at http://localhost:8070 and look at the `user.events.v1` topic. The first `GET /me` produces a `UserRegistered` record.

### Stopping and resetting

```bash
docker compose --profile kafka down        # stop, keep data
docker compose --profile kafka down -v     # stop and wipe all data (needed after editing the Keycloak realm file)
```

Note that `--profile` goes before `down`. Without it the Kafka containers are left running.

---

## Testing

```bash
./mvnw test       # unit tests, no Docker needed
./mvnw verify     # + integration tests against real containers (needs Docker)
```

Unit tests are named `*Test` and run in Surefire. Integration tests are named `*IT`, use [Testcontainers](https://testcontainers.com) and run in Failsafe. CI (GitHub Actions) runs `./mvnw verify` on every push.

user-service has more than 200 test cases. Highlights:

- **Schema tests** that exercise every constraint, default and trigger directly against Postgres, plus a migration-upgrade test that upgrades a populated V7 database to the latest version.
- **Native query tests** that check row counts, compare-and-set guards and optimistic-locking conflicts.
- **Relay tests against real Postgres and Kafka**: a 120-row backlog drained in one tick, two instances competing for the advisory lock, the broker paused with `docker pause` (nothing marked as published, real cause stored, recovery in order, no payload in logs), and backoff timing checked with a fake clock.
- Tests are *mutation-checked*: a test is only trusted after the code it covers has been deliberately broken and the test has failed.

Not written yet: the HTTP security matrix, concurrency tests for the deletion saga, contract fixtures and end-to-end tests (the `e2e/` module is a stub).

---

## Other services (planned or being rebuilt)

The three prototype services (`device-service`, `ingestion-service`, `usage-service`) work end to end, but they predate the design and take shortcuts. For example, ingestion publishes per-tick deltas where a real meter reports a cumulative counter, and device ids are shared through a polled cache. They will be **rebuilt**, not extended, after user-service is complete.

### device-service (prototype, rebuild next)
Owns devices: who has which meter, registration, and the per-device tokens that IoT hardware uses to authenticate. Publishes `device.events.v1` and a compacted `device.registry.v1` topic that ingestion uses to validate tokens without calling this service. Reacts to `UserDeletionRequested` by deleting the user's devices and answering with `UserDevicesDeleted`, which is the other half of the deletion saga.

### ingestion-service (prototype)
The front door for IoT devices. Authenticates a device by its token (checked against the registry topic), validates the reading and publishes it to `energy.readings.v1`. Readings carry the **cumulative meter value** (`energy_total_kwh`) and the owning user id.

### usage-service (prototype)
The only owner of InfluxDB. Consumes readings, computes consumption from the cumulative counter (including counter resets and gaps) and stores it as time series. Serves usage queries.

### alert-service (not yet implemented)
User-defined alert rules (for example "more than X kWh per day") evaluated over tumbling time windows, publishing alert events.

### notification-service (not yet implemented)
Consumes alert events and delivers them through a notification queue with retries, so a failing mail provider cannot lose an alert.

### ai-insight-service (not yet implemented)
Natural-language insights about a user's consumption, built with Spring AI. The model gets **read-only tools** over usage data and cannot change anything.

### api-gateway (not yet implemented)
Spring Cloud Gateway in front of everything: routing, JWT validation, rate limiting. It replaces the dev-only `local-dev` Keycloak client with proper `energy-ui` and `energy-cli` clients.

---

## Repository layout

```
contracts/            Shared event contracts (envelope, headers, user events). Plain jar, no Spring.
services/
  user-service/       The finished reference service
  device-service/     Prototype
  ingestion-service/  Prototype
  usage-service/      Prototype
infra/                docker-compose.yml, Keycloak realm, Kafka topic script
scripts/              get-token.ps1: a dev access token from Keycloak
docs/design/          Design notes (outbox, processed-events)
e2e/                  End-to-end test module (stub)
.github/workflows/    CI
```

It is a single Maven reactor: the root `pom.xml` aggregates every module and manages shared dependency versions, and there is one Maven wrapper at the root.

## Tech stack

| Area | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1 (Spring Framework 7), Lombok |
| Web / security | Spring MVC, Spring Security OAuth2 resource server, Bean Validation, RFC 9457 problem details |
| Persistence | PostgreSQL 16, Spring Data JPA / Hibernate, Flyway |
| Messaging | Apache Kafka 4 (KRaft), Spring Kafka |
| Identity | Keycloak 26 |
| Time series | InfluxDB 3 Core (usage-service) |
| Observability | Spring Actuator, Micrometer |
| Resilience | Resilience4j (planned for the Keycloak admin client) |
| Testing | JUnit 5, Mockito, Testcontainers, Awaitility |
| Build / CI | Maven multi-module, GitHub Actions, Docker Compose |
