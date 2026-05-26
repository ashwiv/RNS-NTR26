# Project Specification — NTR26 Platform Migration

## 1. Project Context

We have three repositories on GitHub:

| Repository | Language | Framework | State | Role |
|------------|----------|-----------|-------|------|
| `RNS-NTR` | C++ | Custom C++ monolith | Working production | **Source of truth** for business logic — read-only reference |
| `RNS-sds` | Java 8/11 | SDS FSMApp (no `main()`, XML-driven rules) | ~70% migrated from RNS-NTR | **Reference implementation** — design lookup only, never edit |
| `RNS-NTR26` | Java 21 | SDS FSMApp (no `main()`, XML-driven rules) | **Empty — new target** | **Where everything gets built** — all writes go here |

`RNS-NTR26` is the destination. Both legacy sources (the C++ monolith and the Java 8/11 partial migration) are retired when this is done. All new code goes into `RNS-NTR26`.

**Architecture continuity:** `RNS-NTR26` keeps the FSMApp framework — XML-driven rules in `package.xml`, no `main()` methods, event-driven lifecycle. What changes is everything else: Java version, microservice decomposition, persistence, caching, IPC, sync→async posture, build tool.

## 2. Mission

Build a unified Java 21 platform in `RNS-NTR26` that replaces both:
- `RNS-NTR` (C++ monolith), and
- `RNS-sds` (Java 8/11 partial migration).

The new platform delivers the same telecom functionality with materially better performance, scalability, and operability — on the same SDS FSMApp framework but with modernized everything underneath it.

## 3. The Nine Architectural Pivots

This is not a 1:1 port. The architecture changes along nine dimensions:

| # | From | To | Why |
|---|------|-----|----|
| 1 | C++ (RNS-NTR) | Java 21 | Modern language, GC tuning, virtual threads |
| 2 | Java 8/11 (RNS-sds) | Java 21 | Records, sealed types, pattern matching, virtual threads |
| 3 | C++ monolith | Java FSMApp microservices, no `main()`, XML-driven rules | Independent deployability, blast radius isolation |
| 4 | Synchronous IPC | Asynchronous event-driven (Kafka topics) | Throughput, backpressure handling, decoupling |
| 5 | Spread Toolkit | Apache Kafka via IPCB bridge | Persistent log, replay, IMSI-keyed partitioning |
| 6 | Couchbase SDK 2.0 | Redis Cluster (Lettuce client) | Sub-ms latency, simpler ops, lower licensing cost |
| 7 | Oracle | PostgreSQL | Open-source, modern features, lower licensing cost |
| 8 | Stateful processes | Stateless services | Horizontal scaling, K8s-native, faster failover |
| 9 | Ant build (RNS-sds) | Maven | Standard tooling, modern dependency management |

**Constant across the pivot:** The SDS FSMApp framework — XML rule definitions in `package.xml`, action handler classes, event ID allocation, `fsmapp.properties` configuration, framework-controlled lifecycle. This is preserved and upgraded, not replaced.

## 4. Scope of Work — Nine Workstreams

The migration is organized into nine workstreams. Workstreams A–C are foundational and must complete before D–I can finalize.

### 4.1 Workstream A — RNS-NTR26 Scaffold (Java 21 + Maven + FSMApp microservices)
- Create the new repository structure with bounded-context microservices, each one an FSMApp module.
- Maven multi-module parent POM + service modules.
- Shared layers: API, domain, infrastructure, observability, FSMApp framework integration.
- Local development environment: docker-compose for Redis, Kafka, PostgreSQL.
- CI/CD on GitHub Actions.

### 4.2 Workstream B — Java 8/11 → Java 21 Lift (RNS-sds → RNS-NTR26)
- Port the **already-migrated 70%** from `RNS-sds` into `RNS-NTR26`.
- Java 8/11 → Java 21 modernization: replace `Lombok @Value` with `record`, `Optional.get()` after `isPresent()` with chaining, blocking I/O with virtual threads, anonymous inner classes with lambdas where appropriate.
- Refactor the FSMApp monolith into the microservices layout from Workstream A — each microservice is an FSMApp module with its own `package.xml`.
- This is not copy-paste — it's a re-homing with modernization, keeping the FSMApp framework intact.

### 4.3 Workstream C — C++ Remaining 30% Port (RNS-NTR → RNS-NTR26)
- The remaining `RNS-NTR` C++ functionality not yet covered by `RNS-sds` is ported directly to Java 21 in `RNS-NTR26`.
- Each component: analyzed (C++ studied), characterized (Golden Master tests against C++ output), ported as an FSMApp module with XML rules, validated.
- Includes the **7 component gap areas**: NTRPassiveInterface, NTRActiveInterface, NTRRRDRInterface, NTRSIPInterface, NTROnDemandInterface, NTRCacheController, EventFramework rules.

### 4.4 Workstream D — Couchbase SDK 2.0 → Redis Cluster Migration
- **367+ files** affected by the database migration (true work surface is ~120 after deduping shaded-Jackson false positives).
- Couchbase retired entirely; Redis Cluster becomes the distributed cache and source-of-truth refresh feed.
- Existing Couchbase-backed refreshers replaced with Redis-backed equivalents.
- Direct Couchbase API callers ported to Redis client calls. **Lettuce preferred** (or Redisson where its features matter); pure Jedis only for tooling; **never `JedisPoolManager`**.
- Cache key patterns and JSON data formats retained for backward compatibility during transition.

### 4.5 Workstream E — Cache Layer Modernization
- **56 cache classes** must be ported or replaced.
- Most caches remain in-process JVM-local data structures (L1); only their refresh source changes from Couchbase to Redis.
- A subset that require distributed/shared state must be backed by Redis directly (L2).
- L1 + L2 hierarchy with write-through and TTL-based expiration.

### 4.6 Workstream F — IPC: Spread → Kafka Migration
- `RNS-NTR` (C++) uses the Spread Toolkit for IPC; target is Apache Kafka via the IPCB bridge.
- The `IBusInterface` / `IBusConnector` API already exists in RNS-sds; the Kafka adapter under `ipcb/kafka/` must be completed to parity with the Spread adapter, then Spread retired.
- Kafka topic partitioning preserves per-subscriber message ordering (partition by IMSI key).
- Message byte format characterization-tested for exact compatibility during the dual-bus transition period.

### 4.7 Workstream G — Oracle → PostgreSQL Migration
- Schema migration: DDL adapted from Oracle to PostgreSQL dialect.
- Sequence handling, partition syntax, JSON column types, stored procedure conversion (where present).
- Data migration via dump-and-load or logical replication during cutover window.
- Application layer: JDBC URLs, driver swap, vendor-specific SQL refactored to standard SQL or PostgreSQL-equivalent.
- Flyway (or Liquibase) for managed schema versioning, per-service migration files.

### 4.8 Workstream H — CBRGHandler Port (Very High Complexity)
- The **CBRGHandler** C++ class has 25+ methods handling SS7 MAP, Diameter, and GTP protocols — the single highest-complexity port in the project.
- Phased approach:
  1. **Phase 1 — Analysis:** document every public method, its protocol, state, IPC, cache dependencies.
  2. **Phase 2 — Characterization:** Golden Master tests captured against the live C++ behavior for every protocol path.
  3. **Phase 3 — Incremental port by protocol family:** SS7 MAP first → Diameter second → GTP third.
  4. **Phase 4 — Validation:** 100% byte-level Golden Master match per protocol.
- Lives in its own dedicated FSMApp module: `ntr-brg-service`.
- Listed separately from Workstream C because of its complexity and risk profile — it deserves its own track, its own owner, and its own milestone.

### 4.9 Workstream I — Synchronous → Asynchronous Refactoring
- Every internal call path that is currently blocking is reviewed.
- Event-driven communication (Kafka topics) for everything that does not require an immediate response — this is the default.
- Synchronous REST APIs reserved for: external integration points, health checks, admin/query APIs.
- Internal flows: Kafka topics + virtual threads on the consumer side, dispatched through FSMApp action handlers.
- Backpressure handling, retry policies, and dead-letter queues defined per topic.

## 5. Microservices Layout (Bounded Contexts)

`RNS-NTR26` is organized as a Maven multi-module project. Each microservice is a separate FSMApp module deployable. Shared infrastructure lives in `lib/` modules.

```
RNS-NTR26/
├── pom.xml                              ← parent POM (dependency management, plugin config)
├── lib/
│   ├── ntr-common-domain/               ← shared domain model (records, sealed types)
│   ├── ntr-common-cache/                ← L1+L2 cache abstraction (Redis client wrapper)
│   ├── ntr-common-messaging/            ← Kafka producer/consumer abstraction (IPCB-compatible)
│   ├── ntr-common-observability/        ← OpenTelemetry + Prometheus + Log4j2 setup
│   ├── ntr-common-fsmapp/               ← shared FSMApp framework extensions
│   └── ntr-common-security/             ← Vault integration, secrets management
│
├── services/                            ← each is an FSMApp module
│   ├── ntr-event-handler-service/       ← TCAP/Diameter/SIP/Internal event handlers
│   ├── ntr-node-manager-service/        ← FSM state machines (formerly C[*]NodeManager classes)
│   ├── ntr-rule-engine-service/         ← 28 business rules + RulesAdjudicator
│   ├── ntr-zone-area-service/           ← Zone/Area model, Area-Based Steering
│   ├── ntr-brg-service/                 ← CBRGHandler port (SS7/Diameter/GTP)
│   ├── ntr-cache-service/               ← Cache refresh coordinators, Redis Pub/Sub listeners
│   ├── ntr-ota-integration-service/     ← OTA SIM updates (integration only)
│   ├── ntr-config-service/              ← Centralized config + feature flags
│   └── ntr-admin-api-service/           ← Operator/admin REST APIs
│
├── packages/                            ← FSMApp package.xml rule definitions, one folder per module
│   ├── event-handler/package.xml
│   ├── node-manager/package.xml
│   ├── rule-engine/package.xml
│   └── ... (one per service)
│
├── config/
│   └── fsmapp.properties                ← FSMApp module registration
│
├── deploy/
│   ├── docker-compose.yml               ← local dev: Redis, Kafka, PostgreSQL
│   ├── kubernetes/                      ← K8s manifests per service
│   └── helm/                            ← Helm charts per service
│
├── docs/
│   ├── architecture/                    ← C4 diagrams, ADRs
│   └── runbooks/                        ← operational runbooks
│
└── .github/workflows/                   ← CI/CD pipelines
```

## 6. Standard Service Layout (Per Microservice / FSMApp Module)

Every microservice follows the same internal layered structure, with FSMApp framework integration:

```
services/ntr-{service-name}/
├── pom.xml
├── Dockerfile
├── src/
│   ├── main/
│   │   ├── java/com/mobileum/ntr/{service}/
│   │   │   ├── application/                ← FSMApp module class (extends FSMApp base)
│   │   │   ├── handler/                    ← action handler classes invoked by XML rules
│   │   │   ├── domain/                     ← business logic, entities, records, value objects
│   │   │   ├── infrastructure/
│   │   │   │   ├── persistence/            ← PostgreSQL repositories
│   │   │   │   ├── cache/                  ← Redis client adapters
│   │   │   │   ├── messaging/              ← Kafka producers (via IPCB)
│   │   │   │   └── external/               ← outbound HTTP clients
│   │   │   └── config/                     ← FSMApp module configuration
│   │   └── resources/
│   │       ├── db/migration/               ← Flyway SQL migrations
│   │       └── package.xml                 ← FSMApp XML rule definitions for this module
│   └── test/
│       ├── java/                           ← unit + integration tests
│       └── resources/
│           └── golden-master/              ← characterization test fixtures
```

**Layer dependency rules:**
- `handler` (action handlers invoked via XML rules) depends on `application`. Never the reverse.
- `application` depends on `domain` and on `infrastructure` *interfaces*. Never on infrastructure implementations directly.
- `domain` depends on nothing else in the project. Pure business logic.
- `infrastructure` implements interfaces declared in `domain` or `application`.

**FSMApp framework rule:** Every module has its own `package.xml` mapping events to action handler classes. No `main()` methods anywhere — FSMApp controls the application lifecycle.

## 7. Project Objectives (Non-Negotiable)

Any change that regresses against these is blocking and must be remediated before merge:

- **Performance** — Event processing latency under 2ms target, 5ms maximum. Performance regression versus the C++ baseline must stay under 10%.
- **High Throughput** — Sustained 7,000–8,000 TPS across MAP, Diameter, and 5G traffic combined.
- **Scalability** — Horizontal scale-up under 2 minutes from threshold breach to scaled capacity. New instance ready within 60 seconds. Stateless services only — all state in Redis or PostgreSQL.

## 8. Quality Constraints

Full development and review standards live in `CLAUDE.md`. Non-negotiable highlights:

- **Java 21 LTS** — modern idioms (records, sealed classes, pattern matching, switch expressions, virtual threads where appropriate).
- **SDS FSMApp framework compliance** — no `main()` methods, no procedural routing logic in Java; use XML rules in `package.xml`; extend FSMApp base classes.
- **Microservices discipline** — every service independently deployable; no shared mutable state; database-per-service or schema-per-service.
- **Stateless services** — no in-memory session state; everything in Redis (L2) or PostgreSQL. L1 caches are read-through replicas that survive eviction.
- **Async-first** — synchronous calls require justification in the PR description; default is Kafka events.
- **Constructor injection** — never `@Autowired` on fields. `Optional<T>` instead of raw nulls.
- **Logging** — Log4j2 only, with structured JSON fields (timestamp, level, service, traceId, imsi).
- **Test coverage** — ≥80% JaCoCo line coverage; ≥3,200 Golden Master test cases total across components.
- **Observability** — OpenTelemetry tracing (<0.5ms overhead per event) and Prometheus metrics on every service.
- **Security** — All credentials via HashiCorp Vault; no hardcoded secrets anywhere.
- **SonarLint** — zero blocker/critical issues before merge.

## 9. Workflow for Every Task

Every change follows one of four modes defined in `CLAUDE.md`:

- **ANALYZE** — given a C++ file or a Java 8/11 file from `RNS-sds`, study it and produce the Analyze Report before any Java 21 is written.
- **PORT** — translate source (C++ or Java 8/11) to Java 21 FSMApp module code in `RNS-NTR26` using the mandatory 7-step pattern (Analyze → Characterize → Design → Port → Configure → Validate → Integrate).
- **NEW** — create something that doesn't exist in any source repo (e.g., the Redis client layer, observability stack, scaffold code).
- **MODIFY** — change already-ported Java 21 code already in `RNS-NTR26`; show the Proposed Change block before any edit affecting multiple call sites.

Do not begin Step 4 (PORT) on any component until Step 2 (CHARACTERIZE) is complete.

## 10. Tech Stack

| Concern | Choice | Notes |
|---------|--------|-------|
| Language | Java 21 LTS | OpenJDK 21+ |
| Build | Maven 3.9+ | Multi-module parent POM |
| Framework | SDS FSMApp | XML rules in `package.xml`; no `main()` |
| Async runtime | Virtual threads | `Thread.ofVirtual()` for I/O-bound work |
| Cache | Redis Cluster 7+ | Lettuce client; never `JedisPoolManager` |
| Messaging | Apache Kafka 3.6+ | Via IPCB bridge; partition by IMSI |
| Database | PostgreSQL 16+ | Flyway for schema migration |
| Container | Docker | Multi-stage builds; distroless base images |
| Orchestration | Kubernetes | Helm 3+ for packaging |
| Secrets | HashiCorp Vault | Vault Agent sidecar injection |
| Observability | OpenTelemetry + Prometheus + Grafana | Loki for logs; Tempo for traces |
| Testing | JUnit 5 + Mockito + Testcontainers | Golden Master tests + integration tests |
| Code quality | SonarLint + SonarQube + JaCoCo | Enforced in CI/CD |

## 11. Definition of Done

A workstream is complete only when:

- All affected modules build clean under Java 21 (`mvn clean verify`).
- All `package.xml` files validate against the SDS DTD.
- All unit tests pass with ≥80% JaCoCo coverage.
- All Golden Master tests match the C++ baseline at 100%.
- Performance regression versus C++ baseline is ≤10%.
- All deployment modes from `CLAUDE.md` Section 9 are tested.
- SonarLint shows zero blocker/critical violations.
- All services pass `/health/ready`, `/health/live`, `/health/startup` probes.
- All Kafka topics have documented partition strategies, retention policies, and consumer-group naming conventions.
- All PostgreSQL schemas have Flyway migrations checked in and tested.
- All event IDs are within their allocated FSMApp ranges (no conflicts).
- UAT sign-off from Mobileum is obtained.

## 12. GitHub Repository Setup

When starting work on `RNS-NTR26`, the following must be in place on day one.

### 12.1 Repository structure
- Maven multi-module parent POM at the root.
- `.gitignore` covering Java, Maven, IDE files, secrets, local config.
- `README.md` with quick-start instructions.
- `LICENSE` file (per Mobileum policy).
- `CODEOWNERS` defining review responsibilities per module.
- `SPEC.md` (this file) and `CLAUDE.md` at the repository root.

### 12.2 Branch protection (main branch)
- Require pull request reviews (minimum 1 approver; 2 for core libraries under `lib/`).
- Require status checks: build, tests, coverage, SonarLint, security scan, `package.xml` DTD validation.
- Require branches up to date before merging.
- No force pushes; no direct pushes to `main`.
- Linear history (squash-and-merge).

### 12.3 Required GitHub Actions workflows
- `pr-validation.yml` — build, test, coverage, SonarLint, `package.xml` validation on every PR.
- `main-build.yml` — full build + integration tests on merge to `main`.
- `release.yml` — tag-triggered release builds with Docker image publishing.
- `dependency-scan.yml` — weekly Dependabot + security audits.
- `golden-master-check.yml` — runs the Golden Master test suite nightly.

### 12.4 Required secrets in GitHub
- `DOCKER_REGISTRY_TOKEN`
- `SONAR_TOKEN`
- `MAVEN_REPO_TOKEN` (if using a private artifact repository)
- `VAULT_TOKEN` (for integration tests against Vault)

### 12.5 Required labels
- `workstream:a` through `workstream:i` (mapping to Section 4).
- `type:port`, `type:new`, `type:modify`, `type:analyze` (mapping to modes in CLAUDE.md).
- `priority:p1` through `priority:p3`.
- `blocked`, `needs-characterization`, `needs-uat`.

### 12.6 Working with reference repositories
The `RNS-NTR` and `RNS-sds` repos are **read-only references**:
- Clone them to local directories alongside `RNS-NTR26`.
- Disable push to prevent accidents:
  ```
  cd RNS-NTR  && git remote set-url --push origin DO_NOT_PUSH
  cd RNS-sds  && git remote set-url --push origin DO_NOT_PUSH
  ```
- Use them for `grep`/lookup during analyze and port modes — never modify.
