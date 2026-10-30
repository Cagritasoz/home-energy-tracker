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
| **user-service** | User accounts, profile, GDPR-style account deletion, user events | 8081 | **Implemented**, see below. Account deletion is partly done |
| device-service | Device registry, per-device tokens, device events | 8081 | Prototype exists, **to be rebuilt next** |
| ingestion-service | Receives readings from devices and publishes them to Kafka | 8082 | Prototype exists, to be rebuilt |
| usage-service | Turns readings into consumption data in InfluxDB | 8083 | Prototype exists, to be rebuilt |
| alert-service | Usage alert rules, evaluated over time windows | planned | Not yet implemented |
| notification-service | Delivers alerts to users (queue + retries) | planned | Not yet implemented |
| ai-insight-service | AI explanations of consumption, via read-only tools | planned | Not yet implemented |
| api-gateway | Single entry point: routing, auth, rate limiting (Spring Cloud Gateway) | planned | Not yet implemented |

The prototypes keep their old ports until they are rebuilt, so device-service (8081) cannot run next to user-service for now. Keycloak is on 8080.

---

## user-service

The owner of "who is this user". It is the only service that talks to the identity provider about account lifecycle, and it publishes everything that happens to a user as events for the other services.

### What it does

- **Identity comes from Keycloak.** There is no sign-up or password code here. The service is an OAuth2 resource server and validates the JWT: issuer, audience (`aud` must contain `user-service`) and realm roles (`user`, `admin`).
- **Just-in-time provisioning.** The first request from a customer (role `user`) creates their row, with the JWT `sub` as the primary key, and emits `UserRegistered`. This is race-safe (`INSERT ... ON CONFLICT DO NOTHING`). If two requests race, only the winner emits the event. The token's email is only read to create the row: a verified token email that differs from the stored one is counted (`user.email.token.mismatch`) and never written back, because a token is a copy of Keycloak's state up to 5 minutes old. If the new account's address belongs to another account, Keycloak is asked whether that account still exists: if not (an operator deleted its user by hand) the old account is scheduled for immediate deletion and the request is answered `503` with `Retry-After`, so the retry succeeds once the address is freed; if it does exist the answer is `409`; if Keycloak cannot answer it is `503`.
- **Self-service API.** View and update your profile, request deletion of your account.
- **Admin API.** Paged read-only listing of users, for staff accounts (role `admin`). An admin is an identity in Keycloak only: admins have no row in the `users` table and cause no events, and calling the customer API with an admin token returns `403`.
- **Account deletion** is a multi-service saga (choreography, no central orchestrator) with a grace period. `DELETE /me` moves the account to `DELETION_SCHEDULED` and emits `UserDeletionScheduled`: for the configured grace (`app.user.deletion.grace`) the account stays readable but read-only, and `POST /me/deletion/cancel` brings it back to `ACTIVE`. After the deadline a background promoter scrubs the account's personal data and moves it to `DELETING` (announced by `UserDeletionStarted`); a job then deletes the account's Keycloak user (idempotent, retried until Keycloak answers, behind a circuit breaker) and, once every participant service that is required for the deletion has confirmed its cleanup (with an event of its own, read by a consumer in user-service; none is required yet, so today a deletion completes by time alone) and a quiet period after the Keycloak delete has passed, the finalizer takes it on to `DELETED` (announced by `UserDeletionFinalized`). A `DELETING` or `DELETED` account gets `410 Gone` on every endpoint.
- **Errors are RFC 9457 problem details**, with a stable `type`, `status`, `detail`, `instance` and `timestamp`. That includes 401 and 403 responses from the security layer.

### API

| Method | Path | Role | What |
|---|---|---|---|
| `GET` | `/api/v1/users/me` | `user` | Own profile and `status` (`ACTIVE` or `DELETION_SCHEDULED` with `deletionScheduledFor`). Creates the account on first call. |
| `PATCH` | `/api/v1/users/me` | `user` | Update `displayName` (≤ 100 chars) and/or `timezone` (a valid Java zone id like `Europe/Istanbul`). Concurrent edits are serialized (the second applies on top of the first); `409 deletion-scheduled` while a deletion is scheduled. |
| `DELETE` | `/api/v1/users/me` | `user` | Schedule account deletion after the grace period. Returns `202` with the account and its `deletionScheduledFor`; idempotent. |
| `POST` | `/api/v1/users/me/deletion/cancel` | `user` | Cancel a scheduled deletion. `204`; `409 deletion-deadline-passed` once the deadline has passed. |
| `GET` | `/api/v1/admin/users` | `admin` | Paged list (`page`, `size` ≤ 100). |
| `GET` | `/api/v1/admin/users/{id}` | `admin` | One user, including lifecycle fields and the services a deletion waits for (`participants`). |
| `GET` | `/actuator/health` | public | Health. |

A user can only ever reach their own row: identity comes from the token's `sub`, never from the URL or the body.

### Events published

Topic `user.events.v1`, keyed by user id, JSON envelope defined in the shared [`contracts`](contracts) module (`event_id`, `event_type`, `schema_version` headers).

| Event | When |
|---|---|
| `UserRegistered` | First time a user is seen |
| `UserUpdated` | Profile changed (`PATCH /me`) |
| `UserDeletionScheduled` | `DELETE /me` accepted (carries the deadline) |
| `UserDeletionCancelled` | A scheduled deletion was cancelled |
| `UserDeletionStarted` | The grace period ended and the promoter started the deletion (the account's email and name are already scrubbed) |
| `UserDeletionFinalized` | Every required service confirmed its cleanup and the quiet period after the Keycloak delete passed (emitted by the finalizer) |

user-service also **consumes** `device.events.v1`: device-service's `UserDevicesUnclaimed` confirms that it no longer holds anything personal for a deleted user. Records it cannot apply go to `device.events.v1.dlt`.

### How the reliability works

The interesting parts of this service are in how it keeps its database and Kafka consistent. Both are covered by tests that run against real Postgres and Kafka containers.

- **Transactional outbox.** The business change and its event are written in the *same* database transaction. A relay then publishes outbox rows to Kafka. A crash can delay an event but never lose it or emit one for a rolled-back change. Design: [`docs/design/01-outbox-design.md`](docs/design/01-outbox-design.md); consumer side: [`docs/design/02-processed-events-design.md`](docs/design/02-processed-events-design.md).
- **A relay that is safe to run many times.** A Postgres advisory lock lets only one instance publish at a time. Rows are sent as a pipelined batch, and only the acknowledged prefix is marked as published. If Kafka is down, the relay backs off exponentially (4 s up to 30 s) and resumes in order when it recovers. It never skips a row. Events can arrive late or twice, but not never.
- **Operable.** Micrometer gauges for pending rows, the age of the oldest pending row (the stall alarm) and the consecutive failed ticks. Published rows are purged after 7 days.
- **Keycloak is never called inside a database transaction.** The jobs that talk to Keycloak read their work list, call Keycloak, then write one guarded `UPDATE`: a slow or dead Keycloak holds no connection and no lock, and two instances doing the same account is harmless because the delete is idempotent. The admin client has 1 s / 3 s timeouts, one retry for transient failures, and a circuit breaker whose state is a metric.
- **Compare-and-set state changes.** Every status change is `UPDATE ... WHERE status = '<expected>'`, and it only emits an event if a row was actually changed. Concurrent requests cannot double-emit.
- **The database owns time.** `created_at` has a default and `updated_at` is stamped by a trigger. Event timestamps come from the row as the database stored it.
- **Idempotent consumers.** The consumer of the participants' confirmations claims each event in a `processed_events` table in the same transaction as its effect, so a redelivered event is applied once and a failure rolls the claim back with the effect. A message that can never be applied goes to a dead-letter topic without blocking the ones behind it; a failing database is waited out and never dead-letters anything.

---

## Run user-service locally

### Prerequisites

- **JDK 21** (the Maven wrapper is included, so no separate Maven install is needed)
- **Docker** with Compose

### 1. Start the infrastructure

```bash
cd infra
cp .env.example .env          # local-only throwaway passwords; .env is gitignored
docker compose --profile kafka up -d
```

Compose starts four services by default: the Postgres database of user-service (`user-db`), the Postgres database of Keycloak (`keycloak-db`), Keycloak itself, with a pre-seeded realm and two test users, and Mailpit, a mail catcher: every mail Keycloak sends (the verification mail of a new registration, password resets, password-changed notices) lands at http://localhost:8025 instead of being delivered. `.env` must also define `USER_SERVICE_ADMIN_CLIENT_SECRET` (copy the line from `.env.example` into an existing `.env`); Keycloak uses it for the admin client of user-service. `--profile kafka` adds a single-node Kafka (KRaft) with its topics, plus Kafka UI at http://localhost:8070. Wait until `docker compose ps` shows Keycloak as healthy. The first start takes a minute.

With plain `docker compose up -d` (no profile) the service still works and serves its API, but events pile up in the outbox table until Kafka is available. The listener for the participants' confirmations keeps trying to reach Kafka and logs warnings meanwhile (`app.user.deletion.ack-consumer.enabled=false` switches it off).

### 2. Run the service

`.env` is only read by Docker, so export the database password and the Keycloak admin-client secret in your shell. They are the same values as in `.env` (the secret is what lets user-service delete Keycloak users; the service does not start without it).

```powershell
# PowerShell
$env:USER_DB_PASSWORD = "password"
$env:USER_SERVICE_ADMIN_CLIENT_SECRET = "<the value from .env>"
.\mvnw.cmd -pl services/user-service -am spring-boot:run
```

```bash
# bash
export USER_DB_PASSWORD=password
export USER_SERVICE_ADMIN_CLIENT_SECRET=<the value from .env>
./mvnw -pl services/user-service -am spring-boot:run
```

Flyway creates the schema on startup. The service listens on **http://localhost:8081**. Health check: http://localhost:8081/actuator/health.

### 3. Call it

The realm has two seeded dev users:

| User | Password | Role |
|---|---|---|
| `testuser@example.com` | `Testpassword1` | `user` |
| `admin@example.com` | `Adminpassword1` | `admin` |

```powershell
# PowerShell: the helper script prints an access token (valid 5 minutes)
$token = .\scripts\get-token.ps1                 # or: -Role admin
Invoke-RestMethod http://localhost:8081/api/v1/users/me -Headers @{ Authorization = "Bearer $token" }
```

```bash
# any shell: ask Keycloak directly
TOKEN=$(curl -s -X POST http://localhost:8080/realms/energy-tracker/protocol/openid-connect/token \
  -d grant_type=password -d client_id=energy-cli -d client_secret=secret \
  -d username=testuser@example.com -d password=Testpassword1 | jq -r .access_token)

curl -s http://localhost:8081/api/v1/users/me -H "Authorization: Bearer $TOKEN"

curl -s -X PATCH http://localhost:8081/api/v1/users/me \
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
  "status": "ACTIVE",
  "deletionScheduledFor": null,
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

### Test coverage

Coverage is measured with [JaCoCo](https://www.jacoco.org/jacoco/), behind an optional `coverage` Maven profile so normal builds stay fast:

```bash
./mvnw verify -Pcoverage                                    # every module
./mvnw -pl services/user-service -am verify -Pcoverage      # one service
```

Open `services/user-service/target/site/jacoco/index.html` in a browser. Packages and classes are listed with line and branch coverage; click through to the source, where green lines ran, red lines never ran and yellow lines ran with some branch untaken. `jacoco.csv` in the same folder holds the raw numbers. The report includes both the unit tests and the integration tests, needs Docker like any `verify`, and is regenerated on every run (it lives in `target/`, which git ignores).

Unit tests are named `*Test` and run in Surefire. Integration tests are named `*IT`, use [Testcontainers](https://testcontainers.com) and run in Failsafe. CI (GitHub Actions) runs `./mvnw verify` on every push.

user-service has more than 1,100 test cases. Highlights:

- **Schema tests** that exercise every constraint, default and trigger directly against Postgres, plus a migration-upgrade test that upgrades a populated V7 database to the latest version.
- **Native query tests** that check row counts, compare-and-set guards and optimistic-locking conflicts.
- **Relay tests against real Postgres and Kafka**: a 120-row backlog drained in one tick, two instances competing for the advisory lock, the broker paused with `docker pause` (nothing marked as published, real cause stored, recovery in order, no payload in logs), and backoff timing checked with a fake clock.
- **Keycloak admin client tests**: a WireMock server plays Keycloak (slow, down, connection reset, 5xx, rejected token, token expiry, circuit open and half-open), and the same client is checked against the real Keycloak image with the project's realm file. The deletion and orphan-address flows run end to end with the real scheduler.
- **Consumer tests against a real broker**: confirmations produced by hand are applied once even when delivered twice, three kinds of poison message land unchanged on the dead-letter topic without blocking the records behind them, and a database that fails several times in a row is waited out without dead-lettering anything. A claim and its effect roll back together, proven with real commits.
- **Races between the customer's own requests and against the saga**, with real commits: two `DELETE`s, a `PATCH` against a schedule in both orders, two `PATCH`es, two finalizers, a confirmation against the finalizer, each staged so that the second transaction is provably blocked on the first's row lock, and again through HTTP with simultaneous requests.
- **The whole deletion saga**, from `DELETE /me` to `DELETED`: with no participants by time alone, and with a participant required, waiting for a confirmation read from and produced to a real Kafka broker, with every job fired by Spring's own scheduler; and with Keycloak unreachable or the Kafka broker paused in the middle of a deletion.
- Tests are *mutation-checked*: a test is only trusted after the code it covers has been deliberately broken and the test has failed.

- **Security tests** against the real security configuration: real RS256-signed tokens with keys served from a local JWKS endpoint, a role matrix of 14 token shapes across every route, forged, unsigned, expired and wrong-audience tokens, CORS, method security in isolation, key rotation and key-endpoint outages, and an integration test proving an admin account never gets a user row or an event. The security classes have 100 % line and branch coverage.

Not written yet: the remaining concurrency tests of the deletion saga and cross-service end-to-end tests (the `e2e/` module is a stub).

---

## Other services (planned or being rebuilt)

The three prototype services (`device-service`, `ingestion-service`, `usage-service`) work end to end, but they predate the design and take shortcuts. For example, ingestion publishes per-tick deltas where a real meter reports a cumulative counter, and device ids are shared through a polled cache. They will be **rebuilt**, not extended, after user-service is complete.

### device-service (prototype, rebuild next)
Owns devices: who has which meter, registration, and the per-device tokens that IoT hardware uses to authenticate. Publishes `device.events.v1` and a compacted `device.registry.v1` topic that ingestion uses to validate tokens without calling this service. Reacts to `UserDeletionRequested` by deleting the user's devices and answering with `UserDevicesUnclaimed`, which is the other half of the deletion saga.

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
Spring Cloud Gateway in front of everything: routing, JWT validation, rate limiting. It sits in front of the dev-only `energy-cli` Keycloak client with a proper UI client.

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
docs/open/            General documents: overview, status, roadmap, conventions, testing, decisions, gaps
docs/design/          One design document per feature (outbox, processed-events, account-deletion saga)
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
| Resilience | Resilience4j (circuit breaker and retry in the Keycloak admin client) |
| Testing | JUnit 5, Mockito, Testcontainers, Awaitility |
| Build / CI | Maven multi-module, GitHub Actions, Docker Compose |
