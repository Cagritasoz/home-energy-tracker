# Project overview

`home-energy-tracker` is a learning project: event-driven Spring Boot microservices that track home energy consumption from IoT devices. Java 21, Spring Boot 4.1.1 (Spring Framework 7), Jackson 3, JUnit 5, Lombok, Maven multi-module; PostgreSQL, Kafka (KRaft), InfluxDB 3 Core, Keycloak.

This folder (`docs/open/`) holds the general, project-wide documents: where things stand, what is planned, the conventions and the testing approach. A feature that deserves its own write-up has a design document in `docs/design/` (one file per feature, numbered). The two kinds are kept apart on purpose: the open documents are broad and short, the design documents explain one feature in depth and carry that feature's roadmap.

## Direction, not specification

The target architecture below is a direction. Designs change while features are built, names and ports are adjusted, parts are dropped. Where a feature has a design document in `docs/design/`, that document is what the code is built to; everywhere else the code itself is the truth, and these documents describe it.

**Design principle: model real IoT energy devices as accurately as reasonable** rather than a convenient simplification (the cumulative meter counter is the example: a device reports a total that only grows and sometimes resets, and the system reads it that way).

## Target architecture

Eight services, one database per service, no cross-service database access:

| Service | Responsibility |
|---|---|
| api-gateway | Spring Cloud Gateway in front of everything; token checks and routing |
| user-service | Accounts: profile, lifecycle (including account deletion), the events other services follow |
| device-service | Devices, their ownership, device tokens, the registry topic ingestion reads |
| ingestion-service | Authenticates devices by token, validates readings, publishes them |
| usage-service | Sole owner of InfluxDB: stores readings, answers usage queries |
| alert-service | Tumbling-window alert rules and their evaluation |
| notification-service | Sends mail and other notifications from a queue of requests |
| ai-insight-service | Answers questions about usage through read-only tools |

Ideas that run through the design:

- `users.id` is Keycloak's `sub`; every service uses the JWT's `sub` as the user id. Ids are never reused.
- A transactional outbox in every producing service and a `processed_events` table in every relational consumer (`docs/design/01-outbox-design.md`, `02-processed-events-design.md`): events are at-least-once, handlers idempotent.
- Account deletion is a saga by choreography (`docs/design/03-account-deletion-saga.md`).
- Devices authenticate to ingestion with device tokens; ingestion learns about devices from a compacted registry topic.
- Readings are cumulative meter values (`energy_total_kwh`) in InfluxDB, tagged with `user_id`; usage is derived with a reset-aware calculator.
- Alerts are evaluated in tumbling windows; notifications go through a queue; the AI service only reads, through tools.

## Services today

| Service | Port today | Target port | State |
|---|---|---|---|
| api-gateway | none | 8000 | not started |
| user-service | 8081 | 8081 | active, see `02-user-service-status.md` |
| device-service | 8081 | 8082 | legacy, to be rebuilt next (its 8081 clashes with user-service until then; its `user-service.base-url` still says 8080) |
| ingestion-service | 8082 | 8083 | legacy |
| usage-service | 8083 | 8084 | legacy |
| alert-service | none | 8085 | not started |
| notification-service | none | 8086 | not started |
| ai-insight-service | none | 8087 | not started |

Legacy means: written before the current design, kept running only until it is replaced. device-, ingestion- and usage-service implement the first pipeline (ingestion publishes per-tick deltas to `energy-usage-events`, usage consumes them into InfluxDB with a polled device-id cache, device-service is plain CRUD that validates users over REST). All three are rebuilt, not patched: don't extend them.

## Work order

1. Finish user-service with real test coverage (`03-roadmap.md`).
2. Rebuild device-service, then ingestion, usage and the rest.

## Repository layout

One Maven reactor: the root `pom.xml` (packaging `pom`, parented on `spring-boot-starter-parent` 4.1.1) aggregates every module and holds the only Maven wrapper. Each module's `pom.xml` is parented on the root (`<relativePath>../../pom.xml</relativePath>`) and lists dependencies without versions. The root `<dependencyManagement>` imports the Spring Cloud, Spring AI, Resilience4j (2.4.0) and Testcontainers (2.0.5) BOMs and manages `contracts` at `${project.version}`; WireMock's version is a root property.

- `contracts/`: plain jar (Lombok compile-only, no Spring) with the event envelope, header names and the user events, plus the contract fixtures (`06-kafka-event-contract.md`).
- `services/<name>/`: one module per service.
- `infra/`: `docker-compose.yml`, `.env.example`, `.env` (gitignored), `keycloak/` (the realm file and its documentation), `kafka/kafka-topics.sh`. All container configuration lives here.
- `e2e/`: the end-to-end suite (Testcontainers `ComposeContainer`); a stub with zero tests, part of the reactor only under `-Pe2e`.
- `docs/open/`: general documents (this folder). `docs/design/`: one design document per feature. `docs/architecture/scrapped/`: abandoned proposals, kept for reference.
- `scripts/get-token.ps1 -Role user|admin`: prints a Keycloak access token for the seeded test or admin user.

## Local Keycloak

`infra/keycloak/realm-energy.json` is imported on first boot only, into an **empty** Keycloak, so editing it needs `docker compose down -v` before the next `up`. Everything about the realm (every setting, the registration endpoint, the event and mail model, the service account) is explained in `infra/keycloak/README.md`, which must be kept in step with the file.

Facts needed to work with it:

- Realm `energy-tracker`, reachable on `http://localhost:8080`. `KC_HOSTNAME` pins every token's `iss` to that address, so no hosts-file entry is needed. A container on the compose network calls Keycloak as `http://keycloak:8080` and overrides only `jwk-set-uri`, never `issuer-uri` (a string that is compared, not fetched).
- Client `energy-cli`: confidential, direct grant enabled, secret `secret` (a throwaway development credential, committed on purpose; a stand-in for the future gateway clients). An audience mapper adds `user-service` to `aud`.
- Realm roles `user` and `admin` (the default-roles composite grants `user`). Seeded `testuser@example.com` / `Testpassword1` (role `user`, id `11111111-…`) and `admin@example.com` / `Adminpassword1` (role `admin` only, id `22222222-…`; deliberately without `user`). The fixed ids are for this local realm only: a real realm never uses deterministic ids.
- The user-profile configuration is one escaped line inside the realm file (JSON strings cannot span lines). Edit `infra/keycloak/user-profile.json`, run `powershell -File infra/keycloak/embed-user-profile.ps1`, commit both; `RealmFileTest` fails when they differ.
- The realm JSON cannot hold comments (not `//`, not pseudo-comment keys; `RealmFileTest` fails on a `"//"` key), so every note on a setting lives in `infra/keycloak/README.md`.
- The service-account client `user-service-admin` (`manage-users`, `view-users` only) gets its secret from `USER_SERVICE_ADMIN_CLIENT_SECRET` while the realm is imported.
