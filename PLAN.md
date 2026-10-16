# Resilient Offline Sync Engine

Status: implementation in progress. The .NET backend and portable Kotlin core exist with shared fixture tests. Android encrypted persistence, atomic edits/outbox, fenced leases, persisted retries, authentication pause, queue states, transactional acknowledgments, confirmed-base/pending-overlay reconstruction, received-clock advancement, and atomic pull cursors are implemented. Eighteen Android 34 instrumentation tests passed locally, covering concurrency, recovery, migration, response preservation, and injected rollback failures. HTTP transport, WorkManager, terminal recovery UX, and the sample remain outstanding, along with real PostgreSQL validation and broader resilience acceptance tests. See the README for the current verification boundary.

## Goal

Build a portfolio-ready offline-first client and server SDK that handles multi-day offline periods, queues local mutations durably, transfers field-level deltas, resolves concurrent edits, and safely retries requests after lost acknowledgments.

Demonstrate offline synchronization, conflict resolution, and weak-network optimization through a working two-device collaborative checklist, tests, and reproducible documentation.

## Agreed Scope

- Kotlin Android SDK using Room, SQLCipher, Android Keystore, Retrofit/OkHttp, and WorkManager.
- C#/.NET 10 LTS backend using ASP.NET Core, PostgreSQL, and Redis.
- Field-level Last Write Wins (LWW), PN-counter, and observed-remove set CRDTs.
- Versioned JSON deltas with GZIP request and response compression.
- Native Android sample, automated tests, CI, local packaging, and a portfolio-ready README.
- Documentation is a first-class deliverable. Maintain Mermaid end-to-end architecture and sequence diagrams in the README alongside implementation changes.
- Publishing to the existing `michaelnwanna/offline-sync-engine` GitHub repository and updating its details and the profile README were explicitly authorized on 2026-10-16. Cloud deployment and package publishing remain outside scope.

## Architecture

### Android

- `sync-core`: portable Kotlin contracts, hybrid logical clock, merge rules, and shared-fixture tests.
- `sync-android`: encrypted Room persistence, transactional outbox, networking, background synchronization, and observable sync state.
- `sample`: native collaborative checklist demonstrating concurrent and offline updates.

### Backend

- `OfflineSync.Core`: protocol models and deterministic merge policies.
- `OfflineSync.Server`: reusable synchronization service, PostgreSQL persistence, Redis receipt cache, and dependency registration.
- `OfflineSync.Api`: ASP.NET Core host, authenticated sync endpoints, health checks, and OpenAPI.

### Demo Schema

- Title and completion status: LWW registers.
- Quantity: PN counter supporting concurrent increments and decrements.
- Tags: observed-remove set supporting concurrent additions and removals.
- Deletion: permanent delete-wins tombstone; recreating an item requires a new entity ID.

The reusable SDK supports registered typed field policies, not arbitrary database or ORM replication.

## Correctness Decisions

### Local Durability and Queue

- Persist each local edit, clock advancement, CRDT metadata, and immutable outbox entry in one Room transaction.
- Assign a stable installation/account-scoped replica identity, mutation UUID, and increasing local sequence number.
- Model queue states as `PENDING`, `IN_FLIGHT`, `SYNCED`, `CONFLICT`, and `REJECTED`. Expired worker leases return entries to `PENDING`.
- Retry transient errors with persisted exponential backoff and jitter. Never discard pending work after an arbitrary retry count.
- Pause on authentication failure until credentials are refreshed.
- Retry an unknown acknowledgment using the exact original mutation ID and payload.
- Keep confirmed server state separate from the projected local state. Apply incoming changes and recompute the pending overlay atomically so acknowledgments and pulls cannot erase newer local edits.
- Use database coordination for worker concurrency and ordering. WorkManager scheduling alone is not a queue lock.
- Recover the gap between committing an outbox entry and scheduling work through startup/foreground checks and a periodic connected sweep.
- Surface terminal failures explicitly. Block dependent operations when necessary without silently blocking unrelated entities.

### Conflict Resolution

- LWW uses a persisted hybrid logical clock with a deterministic replica tie-breaker. Advance it on local edits and received timestamps.
- LWW ordering is deterministic, not proof of real-world chronological order under device clock skew.
- Reject excessively future-dated timestamps with an explicit recoverable clock-skew result. Do not silently retimestamp pending mutations.
- PN counters merge nonnegative per-replica positive and negative components using componentwise maximum; validate bounds and replica ownership.
- Observed-remove sets use unique add dots. A removal records only observed dots, so an unobserved concurrent addition survives.
- Retain deletion and set tombstones in v1 to avoid resurrecting data from long-offline devices.
- A losing LWW field is acknowledged with its resolution outcome, not retried indefinitely. Reserve `CONFLICT` for cases requiring user action.

### Idempotency and Change Ordering

- PostgreSQL is authoritative for deduplication. Store durable receipts keyed by authenticated workspace, replica, and mutation ID.
- Store a canonical typed payload hash and the exact acknowledgment. Reusing an ID with different content returns a mismatch without applying changes.
- Commit entity changes, receipt, and change-log entry in one transaction.
- Lock a workspace cursor row while assigning transactional revisions and hold that lock until commit. This provides commit-ordered revisions within a workspace.
- Do not treat `BIGSERIAL` allocation as commit ordering.
- Redis is an optional short-lived acknowledgment cache populated after commit. Cache eviction, expiry, or outage falls back to PostgreSQL without changing correctness.
- Authenticate and authorize before consulting cached receipts. Never trust a workspace identifier supplied by the client as authorization.
- PostgreSQL unavailability must never produce a success acknowledgment.

### Protocol and Compression

- `POST /v1/sync/push`: versioned, bounded, ordered batches of typed field deltas with immutable mutation IDs and per-mutation acknowledgments.
- Process mutations in batch order with independent transactions; explicitly support partial acknowledgments.
- `GET /v1/sync/pull`: ordered changes with a versioned, workspace-bound cursor, bounded pages, and a `hasMore` indicator.
- Advance the local pull cursor in the same transaction that applies its page.
- Bootstrap by replaying retained history from cursor zero. Snapshot/CDC infrastructure is outside v1.
- Use an explicit OkHttp interceptor for GZIP request bodies. Configure ASP.NET Core request decompression and response compression.
- Bound compressed/decompressed request sizes, mutation batches, and pull pages. Start with 100 mutations per batch, 1 MiB decompressed requests, and 100 changes per page; tune against measurements.
- Transfer changed fields and CRDT components/dots instead of unchanged entities. Initial creation includes initial field values.
- Retain receipts, change history, and CRDT metadata indefinitely in v1. Document storage growth and bootstrap cost rather than introducing unsafe expiry.

### Security

- Generate a random SQLCipher passphrase and wrap it with an Android Keystore AES-GCM key. Store only wrapped bytes and nonce in app-private, nonbackup storage.
- Use a community SQLCipher build and a verified compatible Room integration. Resolve and pin compatible stable dependency versions during setup.
- Validate encryption against a real on-disk database, not only an in-memory Room database.
- Do not fall back to plaintext or destructively reset data after key failure.
- Scope databases, credentials, and background work to the account. Exclude database/key material from backups and sensitive payloads from logs.
- Validate JWT issuer, audience, signing key, and workspace membership. Use an injectable token provider on Android.
- Provide development-only short-lived demo credentials. Disable demo issuance outside development; require TLS outside explicitly configured local debug use.

## Implementation Phases

1. **Foundation:** pin compatible .NET and Android toolchains; create the solution, Gradle modules/wrapper, Docker Compose PostgreSQL/Redis services, environment examples, and health checks. Restore/build .NET and run an encrypted Android database smoke test early.
2. **Protocol and merge engines:** define contracts, canonical hashing, numeric limits, clocks, and conflict policies. Implement Kotlin/C# engines with shared language-neutral fixtures and algebraic convergence tests. Depends on phase 1.
3. **Durable backend:** implement schema/migrations, row-locked revision allocation, merge transactions, replay receipts, optional Redis caching, authentication, pull pagination, GZIP, and OpenAPI. Add real PostgreSQL integration tests immediately. Depends on phase 2.
4. **Android persistence:** implement encrypted Room storage, Keystore wrapping, atomic local edits/outbox, base/projected state, queue leases, retry metadata, migrations, and observable state. Depends on phase 2; can run alongside phase 3.
5. **Sync orchestration:** connect Retrofit push/pull, per-item acknowledgments, cursor transactions, crash recovery, token refresh integration, and network-aware WorkManager retries. Depends on phases 3 and 4.
6. **Native sample:** build checklist editing, quantity controls, tag editing, sync/queue/conflict status, pause/resume, retry, and actionable error recovery. Provide two-device setup and repeatable offline/lost-ack demonstrations. Depends on phase 5.
7. **Resilience verification:** complete fault scenarios, cross-language/wire compatibility, encrypted persistence tests, concurrent server tests, and simulated seven-day/10,000-mutation recovery. Tests are added throughout earlier phases; this phase is the final acceptance gate.
8. **Portfolio delivery:** document architecture, protocol, security, tradeoffs, quickstart, and demo. Add actual screenshots, measured bandwidth comparisons, CI, and local AAR/NuGet packaging. Claim only implemented and verified behavior. Depends on phase 7.

## Proposed Repository Layout

```text
global.json
OfflineSyncEngine.slnx
compose.yaml
.env.example
README.md
PLAN.md
contracts/
  sync-v1.schema.json
  fixtures/
src/
  OfflineSync.Core/
  OfflineSync.Server/
  OfflineSync.Api/
tests/
  OfflineSync.Core.Tests/
  OfflineSync.IntegrationTests/
android/
  settings.gradle.kts
  gradle/libs.versions.toml
  gradlew
  sync-core/
  sync-android/
  sample/
tools/
  OfflineSync.Scenarios/
  OfflineSync.Benchmarks/
docs/
  architecture.md
  protocol.md
  security.md
  demo.md
  decisions/
  assets/
.github/workflows/ci.yml
```

## Acceptance Tests

- Concurrent retries of one mutation produce one durable server effect and a stable replay response.
- Reusing a mutation ID with changed content is rejected without side effects.
- Dropping an acknowledgment after commit, restarting the service, and retrying remains safe.
- Flushing or disconnecting Redis does not weaken replay protection.
- Overlapping workspace write transactions and paginated pulls do not skip committed revisions.
- Cross-workspace reads, writes, receipts, and cursors are rejected.
- Two replicas recover after seven simulated offline days and 10,000 queued mutations, restarts, duplicate delivery, and shuffled delivery across replicas; admissible work drains and state converges.
- Acknowledgments and pull pages preserve newer pending local edits.
- Counter merges preserve concurrent increments/decrements; observed-remove sets preserve unobserved additions; permanent deletion never resurrects an entity.
- Merge operations satisfy commutativity, associativity, and idempotence against shared Kotlin/C# fixtures.
- Ordinary SQLite cannot read the encrypted database. The correct wrapped key reopens it after restart; wrong/missing keys fail non-destructively.
- Injecting a local transaction failure leaves neither an orphaned queue entry nor an unqueued document edit.
- GZIP tests inspect actual wire bytes and headers, and expansion-limit tests reject oversized bodies.
- Bandwidth benchmarks compare full JSON, delta JSON, and gzipped deltas using identical deterministic edits and clearly defined byte measurements.

## Planned Verification Commands

These commands are implementation targets, not commands already run. Android instrumentation requires a configured emulator or device; backend integration tests require a running container engine.

```sh
docker compose up -d postgres redis
dotnet restore OfflineSyncEngine.slnx
dotnet build OfflineSyncEngine.slnx --no-restore
dotnet test OfflineSyncEngine.slnx
dotnet pack src/OfflineSync.Server -c Release
dotnet run --project src/OfflineSync.Api
```

From the Android project directory:

```sh
./gradlew :sync-core:test :sync-android:testDebugUnitTest :sync-android:lintDebug :sample:assembleDebug
./gradlew :sync-android:connectedDebugAndroidTest :sample:connectedDebugAndroidTest
./gradlew :sync-android:assembleRelease
```

## Limitations and Non-Goals

- Eventual delivery requires worker/app execution, valid credentials, network access, and a healthy backend. No immediate execution guarantee under Android force-stop or Doze.
- Per-workspace write serialization favors inspectable correctness over maximum write throughput.
- Unbounded retained history, receipts, and tombstones increase storage and initial bootstrap cost. Safe garbage collection is deferred.
- No collaborative text CRDT, Protocol Buffers, iOS/web client, arbitrary database replication, or complete account-management product in v1.
- No invented performance results, passing CI badges, published package claims, or production-readiness claims.

## Reference Documentation

- .NET support policy: https://dotnet.microsoft.com/en-us/platform/support/policy/dotnet-core
- Android persistent background work: https://developer.android.com/develop/background-work/background-tasks/persistent
- SQLCipher Android integration: https://www.zetetic.net/sqlcipher/sqlcipher-for-android/