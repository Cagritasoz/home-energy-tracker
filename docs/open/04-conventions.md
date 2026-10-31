# Conventions

How code in this repository is written. These are rules the code already follows; a new service adopts them when it is built.

## Comments

This is a learning project: heavy explanatory comments are intentional and are not stripped as cleanup. The author sometimes deletes comment blocks on purpose after saving them elsewhere; that is not an accident. Comments explain the reasoning inline and never point at a documentation file ("see X.md").

## Schema

- Flyway owns all DDL; `ddl-auto=validate`. Changes are new forward migrations, never edits to an applied `V` file (a comment change alters Flyway's checksum too).
- Constraint and index names: `pk_`, `uq_`, `chk_`, `idx_` + `<table>_…`. `CURRENT_TIMESTAMP` over `now()`.
- The database owns time (`created_at` default, a `BEFORE UPDATE` trigger for `updated_at`, mapped `@Generated` with `insertable/updatable = false`). The trigger leaves `version` alone, so native `UPDATE`s bump `version = version + 1` themselves. Other services adopt the same when rebuilt.
- One concern per migration; a table is created whole, with its constraints and indexes, so it reads in one place; each column is explained in the migration's comments.

## State transitions

Every status-changing `UPDATE` is a compare-and-set: it carries `WHERE status = '<expected>'` and reports the rows affected (`int`). The database constraint (primary key, partial unique index) is the real uniqueness guarantee; an application pre-check only improves the error message.

A change that is not a status transition (a profile edit) is a read-modify-write of the entity: it reads the row `FOR UPDATE` (`UserRepository.findByIdForUpdate`) and decides under that lock, so it queues behind every other writer of the row instead of colliding with it. A plain read followed by a version-checked write is not enough: Hibernate cannot report that collision for this entity (`05-framework-and-tooling-gotchas.md`, Hibernate and JPA).

## Packages

A service is organised by feature, not by layer (user-service since the restructure; the other services adopt it when rebuilt). A feature's controller, service, DTOs, entity, repository, exceptions and jobs sit in one package named after it, so there is no `controller`, `service`, `dto`, `entity`, `model`, `util` or `common` package to scatter a feature across.

- A class is named for what it does (`UserProfileService`, not `UserService`), and a controller serves one feature's routes.
- Tests mirror the main packages: a class's test sits in its package, so package-private members stay reachable. A test that exercises a feature end to end sits with that feature, not with the layer it passes through (a deletion flow test is in `deletion/`, not `security/`).
- A test helper used by more than one feature's tests lives in `testsupport/<kind>/` (`config`, `containers`, `data`, `jwt`, `kafka`, `keycloak`, `web`); a helper only one feature uses stays in that feature's package (`DeletionSagaFlowSupport`; `SecuritySliceTest` is the security slice, which the interceptor's wiring test in `provisioning` also runs in).
- Moves are done with `git mv` and as their own commit, so `git log --follow` keeps working.

## Entities

Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`; fields with defaults need `@Builder.Default`; lazy associations are excluded from `equals`, `hashCode` and `toString`.

## Outbox writer rules

- `OutboxService.record*` methods are `@Transactional(propagation = MANDATORY)`: they must join the business transaction.
- Only the request that actually changed the row emits: check the native query's row count, or the entity's version before and after `saveAndFlush`.
- Build the event from the row as the database now holds it (re-read after a native update); `occurredAt` is the row's database-stamped `created_at`, `updated_at`, `deletion_requested_at`, `deletion_started_at` or `deleted_at`.
- The event id is generated once, in the envelope, and the outbox columns are derived from that same envelope.
- `aggregate_version` has gaps (bumps that emit nothing); consumers compare with `>`, never `previous + 1`.

Why: `docs/design/01-outbox-design.md`, section 4.

## Spring pitfalls that cost us once

**Never call a `@Transactional`, `@Scheduled` or otherwise aspect-advised method through `this.`**: self-invocation bypasses Spring's proxy silently. (It happened once: an old relay called its own `markPublished`, so `published_at` was never persisted.) Put the method on another bean.

## Web

- One `@RestControllerAdvice GlobalExceptionHandler` per service returning `ProblemDetail`. user-service's is the reference: it extends `ResponseEntityExceptionHandler`; `type` is under `https://home-energy-tracker/problems/`; `title`, `status`, `detail`, `instance` and `timestamp` are on every error, the same as the security 401/403 handlers. `OptimisticLockingFailureException` answers 409. `DataIntegrityViolationException` answers 409 only for a unique violation (SQLState 23505 in the cause chain); any other integrity violation is a logged 500. The catch-all 500 logs the cause and rethrows `AccessDeniedException` and `AuthenticationException`.
- DTOs are records with `@Builder` and `@JsonPropertyOrder` (Jackson 3 orders alphabetically and matches names verbatim, camelCase). Bean Validation on request DTOs with `@Valid`.
- User identity comes only from the JWT `sub`. Ownership is `WHERE user_id = :sub` in the query, and a miss is 404, never 403, so the API does not reveal that another user's resource exists.

## Configuration

Settings are read through `@ConfigurationProperties` records, one per feature prefix, in the package of the feature they configure: `OutboxProperties` (`app.outbox`), `ProcessedEventsProperties`, `DeletionProperties` (`app.user.deletion`), `KeycloakAdminProperties`, `SecurityProperties` and `KafkaClientIdProperties`. There is no `@Value` in the main code.

- **Validated, strictly.** Each record is `@Validated` with Bean Validation on its fields (`@Positive`, `@NotBlank`, `@DurationMin`) and has `ignoreUnknownFields = false`, so a missing, out-of-range or misspelled setting stops the start-up and names the field. The records have no defaults: `application.yaml` holds every value, with a comment per setting. Validation that belongs to the value itself lives in the record (a grace period must be positive, a participant name must match the table's rule); a rule that needs another bean (the required participants must each have an ack handler) stays in the bean that owns it (`RequiredParticipantsValidator`).
- **Registered where used.** A class that needs a record registers it with `@EnableConfigurationProperties(X.class)` on itself, so importing the class alone is enough in a slice test. A global `@ConfigurationPropertiesScan` would bind every record in every slice test, the Keycloak secret among them.
- **Annotations read the key too.** `@Scheduled` and a listener's `autoStartup` take a placeholder, not a bean, so they read `${app.…}` themselves. The same key is a field of its record, so that it is validated at start-up like the rest.
- **Names.** A field is named after its key (`wait-duration`, not `wait`: a record component cannot be called `wait`, which is `Object.wait()`). Durations are `Duration` (`5m`, `200ms`); the keys that end in `-ms` are plain numbers of milliseconds.
- **Secrets** come only from environment variables, and a binding keeps an unresolved `${X}` as plain text (`05-framework-and-tooling-gotchas.md`), so a secret's field also rejects a value that starts with `${`.
- `application.yaml` uses spaces only (YAML forbids tabs) and explains every setting in a comment.

## Logging

Never log tokens, secrets, emails or request bodies; log ids. Exception messages that come from another system (Keycloak, the database) are not logged or returned: they can quote personal data; log the class and the id instead. Logging, metrics and tracing come from Micrometer and structured logging (observability step in `03-roadmap.md`). `spring-boot-starter-aspectj` stays in the pom for Resilience4j's annotations.

## Tests

Test style and structure: `07-testing-strategy.md`.
