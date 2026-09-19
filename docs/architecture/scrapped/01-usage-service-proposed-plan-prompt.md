You are an expert distributed systems and software architect. Based on our existing codebase and service registry, I need you to perform a rigorous feasibility study and architectural critique of my proposed design for the `usage-service`.

CRITICAL INSTRUCTIONS: Do NOT generate or apply any application code changes. This is strictly an architectural design and an evaluation task. Write your entire analysis, trade-offs, and structural recommendations to  `02-usage-service-proposed-plan-response.md` file. Clearly write the current date and time to the file.

First inspect the repository thoroughly before evaluating the architecture. Identify the currently implemented services, Kafka topics/events, database schemas, transactional outbox implementation, Docker infrastructure, InfluxDB integration, service boundaries, and existing configuration. Clearly distinguish between currently implemented behavior, proposed behavior, and your recommended architecture. Do not assume that the proposed architecture is correct.

Distinguish clearly between: current architecture → proposed architecture → recommended architecture

---

### THE ARCHITECTURE TO EVALUATE
The `usage-service` acts as a multi-domain aggregation engine (CQRS read-model) to handle high-load iot readings coming from ingestion-service via kafka and to periodically (like a @Scheduled job) query influx db evaluating user specified alert rules and which ones have been triggered.
- It consumes `user-*`, `alert_rule-*`, and `device-*` related events from Kafka to sync a local PostgreSQL instance. Determine whether those relationships should actually exist in the usage-service read model and what minimum state does usage-service actually need to perform its job well? user-service already has an implemented outbox pattern for publishing user-domain related events while device-service does not have one at the moment.
- It caches this relational data in Redis for sub-millisecond lookups during high-volume ingestion.
- The `ingestion-service` pipes time-series metrics via Kafka; `usage-service` validates device existence against Redis before streaming data into InfluxDB. Critically evaluate whether rejection is the correct semantic under eventual consistency. If not rejecting is preferred how do we deal with that later?
- It evaluates user-defined thresholds over an evaluation window and publishes its own events to an alert-service (not yet implented) if threshold has been exceeded within that window frame.

---

### YOUR ASSIGNMENT

Please evaluate this specific plan with absolute honesty. Focus heavily on feasibility, limits, and alternative patterns. Structure your response into the following clear sections:

#### 1. Feasibility & The "Hard Truths" (What is vs. Isn't Achievable)
- **Feasibility Verdict:** Is this specific data aggregation strategy realistic under high ingestion load?
- **Microservices best practises:** Does this plan follow best practises for microservices architecture? Why?
- **Downsides & Performance Traps:** Critique the pain points of this design. Specifically evaluate the performance impact of constantly querying InfluxDB on a timed loop for thousands of active alert rules.
- **The Stream Processing Alternative:** If pulling data out of InfluxDB via continuous queries is a bottleneck, outline how we can pivot or alter the architecture to use Stream Processing (e.g., Kafka Streams or Flink) to calculate threshold windows in real-time as data passes through. Provide a clear pros/cons comparison.

#### 2. Resilience & Fault Tolerance (Resilience4j Integration)
Detail exactly how **Resilience4j** must be configured within `usage-service` to protect the pipeline:
- **Circuit Breakers:** How to handle sudden latency spikes or temporary outages in Redis or InfluxDB without blocking Kafka consumer threads. Define fallback mechanisms (e.g., routing to secondary buffers or Kafka retry topics).
- **Bulkheads:** How to structurally isolate the high-volume ingestion validation threads from the heavy, CPU-intensive alert evaluation loops so they never starve each other of resources.
- **Retries vs. Rate Limiters:** Where to safely use retries (e.g., transient Postgres locks during event consumption) and where to strictly avoid them (e.g., high-volume ingestion streams).

#### 3. Distributed Systems Edge Cases
Provide explicit strategies for managing data drift and race conditions in `usage-service`:
- **Out-of-Order Events & Idempotency:** How should the consumer handle delayed or duplicated Kafka events (e.g., receiving a `device-deleted` or `device-updated` event before a delayed `device-created` event)?
- **Ingestion Race Conditions:** What happens if a device emits massive telemetry data at the *exact millisecond* it is being created or deleted in the upstream services? How do we handle the synchronization lag between the `device-created` event hitting `usage-service` vs. the telemetry event hitting the ingestion pipeline?
- **Redis Sync Strategy:** What cache strategy (Write-Through, Cache-Aside, etc.) minimizes data inconsistency with PostgreSQL under heavy write/delete operations?

#### 4. Upstream Ecosystem Requirements (Designing for `usage-service`)
What must the *other* services do to make this architecture possible? Define the strict contracts, patterns, and obligations for the surrounding ecosystem, including:
- **`user-service` & `device-service`:** Requirements for Transactional Outbox patterns, event versioning timestamps, and specific Kafka partitioning keys (to guarantee order per entity) see user-service outbox pattern and evaluate if it currently works well for proposed usage-service. Do not assume exactly-once semantics are necessary. Determine where at-least-once delivery + idempotent consumers is the preferable design.
- **`ingestion-service`:** how should ingestion-service look like to support this design of usage-service (if the design is feasible of course)
- **Dead Letter Queues (DLQ):** Define the cross-service error-handling and retry topic topology that would need to be introduced.

#### 5. Recovery and Rebuildability
If usage-service PostgreSQL or Redis is completely lost, can usage-service reconstruct its state from Kafka and/or upstream services? If not, identify which state is recoverable, which is not, Kafka retention requirements, replay strategy, and whether the architecture accidentally creates a non-rebuildable database.

Begin your evaluation now. Write all findings, suggestions, and structural blueprints directly to `02-usage-service-proposed-plan-response.md`. Do not apply code changes to the repo.
