# Design decisions

Project-wide decisions that are not part of one feature's design document, and decisions still waiting for an answer. Feature-specific decisions live in the feature's own document (`docs/design/`). When an open decision is settled it moves to "Decided"; when a decided one changes, it is edited here.

## Decided

- **Provisioning on every `/api/v1/users/**` request**, not only on `GET /me`: a user's first call may be any endpoint, and every endpoint needs the row and the status gate. The cost (the interceptor and the controller each read the user row) is to be measured under load before optimising.
- **No email sync after provisioning.** Email is changed only through user-service, so a token's email is read only to create the row; a divergence is counted, not written back (saga design, section 10.1).
- **Admin accounts are staff, not customers.** An admin exists only in Keycloak (role `admin`, no `user`): no `users` row, no events about them. The customer API answers an admin token with 403 and the interceptor does not run on admin endpoints. A person who is both uses two accounts. Not done, production-grade extras: MFA for admins, a separate Keycloak client or audience for admin tooling, an audit record of admin reads and writes of personal data.
- **Audience validation:** the token's `aud` must contain `user-service` (an audience mapper on the development client adds it).
- **Admin read endpoints** (`GET /admin/users`, `/admin/users/{id}`) stay minimal; they are not a general admin API.
- **`DELETING` and `DELETED` accounts get 410 on every endpoint**, including a repeated `DELETE /me`, so `DELETE` stays idempotent in outcome (the saga design has the full table).
- **`email` in a `PATCH` body is rejected** (400 `validation-failed`) instead of ignored; email is not editable through the API at all today.
- **`If-Match`/412 is deferred** (see `03-roadmap.md`): `PATCH /users/me` relies on `@Version` and answers 409.
- **Contract fixtures live in the `contracts` jar** (`06-kafka-event-contract.md`).
- **Topic provisioning** is `infra/kafka/kafka-topics.sh` run by the `kafka-topics-init` compose service. Images: Kafka `apache/kafka:4.3.1`, Postgres 16.
- **Names.** The realm stays `energy-tracker`, the configuration prefix stays `app.*`, Keycloak clients and roles are added only when something needs them: a `service` role and a service-to-service client arrive with the first service that calls another (for example alert-service calling usage-service). The development client is `energy-cli`. Roles are lowercase `user` and `admin`. user-service is on 8081 and Keycloak on 8080; the other services take their target ports (`01-project-overview.md`) when they are rebuilt.
- **Resilience4j** is used programmatically where a call needs a breaker or a retry (the Keycloak admin client). The outbox relay deliberately has no breaker (outbox design, section 10).

## Open (the user decides; recommendation given)

1. **Postgres topology.** One `postgres:17` container with a database and role per service (and Keycloak's), created by init SQL, versus today's dedicated `user-db` (database `user_service`) and `keycloak-db` containers. *Recommendation:* switch when device-service is rebuilt, renaming `user_service` to `users_db` then.
2. **Correlation ids and tracing.** A hand-written filter putting a UUID into the logging context and copying it into `outbox_events.correlation_id`, or Micrometer Tracing (OTLP to Tempo, W3C `traceparent` over HTTP and Kafka, the trace id as `correlationId`). *Recommendation:* Micrometer Tracing. `OutboxService` stores the request's trace id in `correlation_id` (the relay runs in its own trace and forwards it as a header); `causation_id` stays an explicit parameter set by consumers. Also put the JWT `sub` into the logging context in `UserProvisioningInterceptor` (removed in `afterCompletion`) so every log line carries `userId`.
3. **Relay tuning.** 2000 ms / 50 rows per batch today (kept small while proving the relay); a short interval and larger batches are likely right. Revisit with load tests.
4. **Keycloak realm decisions**, each documented with its trade-offs in `infra/keycloak/README.md`: disabling the Account Console clients (`account`, `account-console`) in the realm file; the prototype login theme that gives the three password pages different titles; `adminEventsDetailsEnabled` (admin events keep email addresses, saga design section 13); whether `actionTokenGeneratedByUserLifespan` (300 s) is too short for mailed links; an `auth_time` claim in the access token (needed for a recent-login check).
