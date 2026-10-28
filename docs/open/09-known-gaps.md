# Known gaps

Shortfalls and risks that are known and not yet scheduled as part of a feature. Planned work with an order is in `03-roadmap.md`; gaps that belong to one feature are in that feature's document (the outbox's in `01-outbox-design.md` section 13, the saga's in `03-account-deletion-saga.md` section 13). An item leaves this list when it is fixed or when it moves into a roadmap.

## Not monitored yet

The gauges and counters exist (`02-user-service-status.md`) but nothing scrapes them and no alert rule exists: the Prometheus endpoint and the alert rules arrive with the observability step. Until then a stalled relay or a stuck deletion is visible only in the logs.

## No consumer exists yet

`processed_events` (V10) is unused, the dead-letter handling is not built, and no service consumes `user.events.v1`. Everything the consumer side promises (idempotent handlers, the version guard, tombstones) is design only until device-service and user-service's own ack consumer exist (`docs/design/02-processed-events-design.md`).

## Operational

- **Database timeouts are not configured**: a hung session can hold the relay's advisory lock and block the single scheduler thread. Part of the observability step.
- **A malformed `sub` ends in a 500** (only reachable with a token validly signed by Keycloak); see `03-roadmap.md` for the fix.
- **The interceptor and the controller each read the user row** on every request.
- **Local development uses shared credentials** that are committed on purpose (`energy-cli` secret, seeded users); production Keycloak (no development client, admin console not public, TLS, SMTP, `sslRequired`) is configured when the container step arrives. The frontend will need its own public authorization-code + PKCE client.
- **CI** runs one job; there is no contract stage, no image build and no end-to-end stage yet.

## Privacy

- **Personal data stays in Kafka until topic retention expires.** `user.events.v1` carries emails and names in `UserRegistered` and `UserUpdated`; scrubbing the table and purging the outbox do not remove them from the topic. Intended fix: set the topic's `retention.ms` to match the outbox purge, or crypto-shred (saga design, section 13).
- **Keycloak admin events keep personal data** (the data of each admin call, including email addresses) and it is not verified that they expire.
- **No audit record of admin reads and writes** of personal data in our own services.

## Features not built

Admin write endpoints, email change, recent-login check on `DELETE /me`, the reconcile of diverged emails and orphaned accounts, the replay endpoint: `03-roadmap.md`.
