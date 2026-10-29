# Roadmap

What is planned and in what order, project-wide. A feature that has its own design document in `docs/design/` keeps its own roadmap there (the account-deletion saga's remaining steps are in `03-account-deletion-saga.md`, the outbox's in `01-outbox-design.md`); this file only points at them. When a step is done, it is removed or marked here in the same change that finishes it.

## Order of work

1. **user-service**, finished with real test coverage: the saga (its own roadmap), then the steps below.
2. **device-service**, rebuilt: device tokens, the compacted registry topic `device.registry.v1`, consuming `user.events.v1` (it is the first participant of the deletion saga and confirms with `UserDevicesUnclaimed` on `device.events.v1`).
3. ingestion-, usage-, alert-, notification- and ai-insight-service, then the gateway.

## user-service: remaining steps

1. **Account-deletion saga**: see `docs/design/03-account-deletion-saga.md`, section 17.
2. **Runtime configuration and observability**:
   - `open-in-view: false` (Boot defaults it to true), Hikari pool sizes, database timeouts (`statement_timeout` in the JDBC URL, `lock_timeout`, `idle_in_transaction_session_timeout`, the JDBC `socketTimeout`, `tcpKeepAlive`), graceful shutdown.
   - Virtual threads. Two things to do with them: Boot then builds a different scheduler (the pool size property no longer applies), and the token methods of `KeycloakAdminRestClient` are `synchronized` around an HTTP call, which pins the carrier thread on JDK 21; replace the monitor with a `ReentrantLock`.
   - Actuator `prometheus` endpoint and the service's metrics (outbox lag, parked count, age of the oldest `DELETING` account, the saga gauges), `logging.structured.format.console: ecs`, Micrometer tracing to Tempo with the trace id as `correlation_id` (open decision in `08-design-decisions.md`), the JWT `sub` as `userId` in the logging context.
   - Alert rules to add once something scrapes the gauges: `outbox.oldest.pending.age.seconds` above 60 s for 2 min (warn) and above 300 s (critical), `outbox.parked.rows` above 0, `outbox.relay.consecutive.failed.ticks` above 0 for 2 min (warn), `keycloak.admin.circuit.state` at 2 for more than a minute, a dead-letter alert per consumer topic.
3. **Replay**: a `UserSnapshot` event (with fixture) and `POST /api/v1/admin/users/replay-snapshots`, so a new or lost consumer can rebuild its copy; outbox replay by aggregate id or `seq` range; the operator quarantine that uses the `parked` column.
4. **Container**: a Dockerfile and a compose service with a readiness health check.
5. **With the rest of the system**: load tests, to settle the relay's tuning and the cost of provisioning on every request (`UserProvisioningInterceptor` and the controller each read the user row); end-to-end tests (`e2e/`) once device-service consumes `user.events.v1`.

## Planned, not yet scheduled

- **Admin write endpoints** (create, patch and delete a user). They act on a target `{id}` through the same service methods as the customer endpoints. Creating a user means creating the Keycloak account first (the row's id is its `sub`), then the row; deleting is the saga with the deadline set to now; the events should record the acting admin. `If-Match` becomes worth doing once admins edit other people's profiles.
- **Email change.** Needs notification-service. Agreed design: saga design, section 10.4. Until it exists an email cannot be changed.
- **Recent-login check on `DELETE /me`**, which needs an `auth_time` claim in the access token (not present today).
- **Retention for personal data in `user.events.v1`**, **a reconcile of diverged emails** and **a reconcile of orphaned accounts**: saga design, section 13.
- **`If-Match` and 412 on `PATCH /users/me`**, deferred: today a stale write is caught by `@Version` and answers 409. Revisit with the first resource that clearly needs it (device or alert-rule edits) or if user-service becomes the reference pattern.
- **A token validator for `sub`** (must be a UUID, else 401 `invalid_token`) in the validator chain of `SecurityConfig`, so the rule sits at the trust boundary. Today a malformed or missing `sub` ends in a 500 at the two places that parse it; only a token validly signed by Keycloak can carry one, so it matters only if federation or custom user ids are ever added.
- **A topic format check** for `outbox_events.topic` and database timeouts: outbox design, section 13.
- **The consumer half of the event contract tests** (read a fixture, assert the handler's effect): with the first consumer.
- **An ArchUnit test for the package rules** (proposed, not yet agreed): no cycles; `user`, `outbox` and `keycloak` depend on nothing above them; `security` stands alone; controllers only in feature packages. Possibly with tighter visibility (package-private classes) and `DeletionScheduledException` moved to `user`.
- **Other services** follow their own design documents when they are rebuilt.
