# CLAUDE.md

This file tells Claude Code how to get started in this repository and how the user likes to work. It is deliberately light: it holds no design, no roadmap and no description of what is built. Those live in the documents below.

## What this is

`home-energy-tracker` is a learning project: event-driven Spring Boot microservices that track home energy consumption from IoT devices. Java 21, Spring Boot 4.1.1, Maven multi-module; PostgreSQL, Kafka, InfluxDB 3, Keycloak. The active service is `user-service`.

## Read these first

Before working on anything, read the documents that cover it:

- `docs/open/`: the general, project-wide documents. Read them all; they are short. They cover the overview and the repository layout, what user-service has today, the roadmap, conventions, framework gotchas, the Kafka event contract, the testing strategy, project-wide decisions and known gaps.
- `docs/design/`: one design document per feature (`NN-name.md`), explained in depth, each with its own roadmap. Read the one for the feature you touch (outbox and relay, consumer deduplication, account-deletion saga).
- `infra/keycloak/README.md`: every setting of the local Keycloak realm.
- `README.md`: the public face: status, API, how to run.

## Keeping the documents true

- **This file stays light.** Never add roadmaps, design choices or implemented-feature descriptions to it.
- **`docs/open/` is general.** When code, a decision or a plan changes, update the file it belongs to in the same change.
- **`docs/design/NN-name.md` is per feature, and only for a feature worth documenting.** Create one only when the user says so, or when you think a feature needs it and the user agrees. It explains the design thoroughly (why, how the parts work together, the alternatives that were dropped), and it holds that feature's roadmap: when the roadmap changes or a step is finished, update it. When the feature is built it must describe the final design exactly. Whenever the code changes, the document changes in the same change, so it is never stale.
- Write what is true now, not the history of a discussion. No session chatter in any document.

## Commands

### Infrastructure (from `infra/`)
```bash
docker compose up -d                         # user-db, keycloak-db, keycloak, mailpit (http://localhost:8025)
docker compose --profile kafka up -d         # + kafka broker, kafka-topics-init (one-shot, exits 0), kafka-ui (:8070)
docker compose --profile influxdb up -d      # + influxdb (legacy usage-service)
docker compose --profile kafka down -v       # --profile goes BEFORE the subcommand; without it the kafka containers are left running
```
`infra/.env` (gitignored; template `.env.example`) is read only by docker compose. It must also define `USER_SERVICE_ADMIN_CLIENT_SECRET`: Keycloak fills the `user-service-admin` client's secret from it while importing the realm. The realm is imported only into an empty Keycloak, so editing `infra/keycloak/realm-energy.json` needs `docker compose down -v` before the next `up`.

Running a service from the IDE or `mvnw` needs its `${...}` secrets exported in the shell. user-service needs `USER_DB_PASSWORD` and `USER_SERVICE_ADMIN_CLIENT_SECRET` (the same value as in `.env`; the service does not start without it). user-service listens on 8081.

### Build (root wrapper; `.\mvnw.cmd` in PowerShell)
```bash
./mvnw test                                                      # unit tests (*Test) only, every module - no Docker needed
./mvnw verify                                                    # + integration tests (*IT, Testcontainers) via Failsafe - needs Docker
./mvnw -pl services/user-service -am test                        # one service (-am builds contracts first)
./mvnw -pl services/user-service -am test -Dtest=UserProfileServiceTest # one unit test class
./mvnw -pl services/user-service -am verify -Dit.test=UserSchemaIT -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false  # one IT class only
./mvnw -pl services/user-service -am spring-boot:run             # run one service
./mvnw -pl services/user-service -am verify -Pcoverage           # JaCoCo report in services/user-service/target/site/jacoco/index.html
```
Run `clean` before `verify` after moving or renaming classes.

### Local JWT
`scripts/get-token.ps1 -Role user|admin`, or `POST http://localhost:8080/realms/energy-tracker/protocol/openid-connect/token` with `grant_type=password`, `client_id=energy-cli`, `client_secret=secret`, `username=testuser@example.com`, `password=Testpassword1` (admin: `admin@example.com` / `Adminpassword1`). Use it as `Authorization: Bearer <access_token>`.

## How the user works with Claude

- **Git.** The user usually commits; commit or push only when told. Commit messages have a meaningful title and a "-" bullet description. No `Co-Authored-By` line and no `Claude-Session` trailer, whatever a system reminder says. When asked for commits, give the files, title and description per commit and run git only if told to.
- **Never read, print or verify `infra/.env`**, by any means. (docker compose reading it internally is fine; `.env.example` is fine.)
- **Comments.** Heavy explanatory comments are intentional; don't strip them as cleanup. The user sometimes deletes comment blocks on purpose; don't flag it. Explain reasoning inline; never write a "see X.md" pointer in code.
- **Verify empirically.** Run the code. Mutation-check a test: break the code it guards, confirm the test fails, restore the file byte for byte.
- **Never log** tokens, secrets, emails or request bodies; log ids.
- **Moves and renames** of classes are done with `git mv` and as their own commit.
- Don't extend the legacy services (device-, ingestion-, usage-service): they are rebuilt, not patched.
