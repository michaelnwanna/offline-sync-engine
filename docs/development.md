# Development Guide

## Requirements

- .NET 10 SDK (selected by the root SDK configuration).
- Docker with Compose, or a separately managed PostgreSQL 17 database.
- Optional Redis 7 for receipt caching. Leave its connection unset to use PostgreSQL directly.
- The portable Kotlin core uses JDK 21 and the checked-in Gradle 8.14.3 wrapper. Android persistence/sample work additionally requires an Android SDK and emulator/device.

## Start Local Infrastructure

Create a local `.env` using the names in the root environment example and choose a development-only PostgreSQL password. Compose does not load API configuration; configure the API separately through environment variables or .NET user secrets. Never commit credentials.

```sh
docker compose up -d postgres redis
dotnet user-secrets set --project src/OfflineSync.Api 'ConnectionStrings:Postgres' 'Host=localhost;Database=offline_sync;Username=offline_sync;Password=YOUR_LOCAL_PASSWORD'
dotnet user-secrets set --project src/OfflineSync.Api 'ConnectionStrings:Redis' 'localhost:6379'
```

User secrets are a local development convenience, not encrypted production secret storage. Redis is optional: omit its configuration to disable the cache.

Generate a stable base64 cursor-signing key of at least 32 random bytes and configure `Sync:CursorSigningKey` through secrets or a secret manager. If omitted in Development, the host generates an ephemeral key; restarting then invalidates existing pull cursors. Rebootstrap from the start in that case. Never treat this development fallback as a production key-rotation strategy.

## Migrate and Run

```sh
ASPNETCORE_ENVIRONMENT=Development dotnet run --project src/OfflineSync.Api -- --migrate
ASPNETCORE_ENVIRONMENT=Development dotnet run --project src/OfflineSync.Api -- --urls http://localhost:5080
```

The migration command exits after applying schema migrations. It is not performed automatically at host startup. Production migrations must be run through a controlled deployment process.

- `GET http://localhost:5080/health/live`: process liveness only.
- `GET http://localhost:5080/health/ready`: PostgreSQL connectivity, not a substitute for migration checks.
- `GET http://localhost:5080/openapi/v1.json`: development-only OpenAPI document.
- `POST /v1/sync/push` and `GET /v1/sync/pull`: authenticated sync operations.

## Development Authentication

Use .NET's development-only JWT tool from the API project context. Configure both devices with the same workspace and different replica IDs. Example for device A:

```sh
dotnet user-jwts create --project src/OfflineSync.Api --audience http://localhost:5080 --claim workspace=aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa --claim replica=11111111-1111-4111-8111-111111111111 --valid-for 1h
```

Create another token with a different replica UUID for device B. Treat tokens as credentials; never commit or log them. The JWT tool configures the development issuer/signing keys in local project secrets. Send the returned token using `Authorization: Bearer ...`. Development JWTs are not a production account system.

Outside Development, set `Authentication:Authority`, `Authentication:Audience`, a persistent `Sync:CursorSigningKey`, and the PostgreSQL connection through secure configuration. The issuer must supply the required workspace/replica claims. HTTPS metadata is required.

## Test

```sh
dotnet build OfflineSyncEngine.slnx
dotnet test OfflineSyncEngine.slnx
```

Without `SYNC_TEST_POSTGRES`, durable integration tests explicitly skip. They do not substitute an in-memory database. Configure that variable with a dedicated test-database connection and rerun to exercise real row locks, replay, and pagination. Tests apply migrations and leave uniquely scoped workspace data for inspection. Do not point them at production.

CI configures PostgreSQL and runs these tests with test reports. No claim of a passing hosted workflow is made until the repository is published and the workflow executes.

## Portable Kotlin Core

From the repository root, with JDK 21 configured:

```sh
./android/gradlew -p android :sync-core:test
```

On macOS, select an installed JDK explicitly with `JAVA_HOME=$(/usr/libexec/java_home -v 21)` before the command when needed. The first run downloads Gradle and dependencies. No Android emulator or database server is needed for this module.

Tests load language-neutral fixtures directly from `contracts/fixtures`: merge/clock cases, typed wire round trips, canonical JSON escaping, and an identical mutation fingerprint checked by .NET. Additional tests cover convergence, metadata ambiguity, arithmetic overflow, ownership validation, clock skew, and insertion-order-independent hashing. This does not validate encrypted Android storage or background delivery.

## Android Local Storage

The `sync-android` library requires API 26+, compiles against API 35, and pins Android Gradle Plugin 8.10.1, Room 2.7.2, KSP 2.2.20-2.0.3, SQLCipher community 4.10.0, and AndroidX SQLite 2.5.2. Gradle may install missing SDK packages when their licenses are already accepted.

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./android/gradlew -p android :sync-android:connectedDebugAndroidTest :sync-android:lintDebug
```

Use `adb devices` to select a running device and set `ANDROID_SERIAL` when multiple devices are connected. Eighteen instrumentation tests passed on the Pixel 8 Android 34 emulator, including storage, queue coordination, migration, acknowledgment/pull processing, and transaction rollback. Reports are generated under `android/sync-android/build/reports/androidTests/connected`.

Open `SyncDatabase` off the main thread with a stable authenticated account/workspace scope, then construct `LocalSyncStore`. Its `edit` transaction allocates the replica identity, local sequence, hybrid timestamp, and mutation UUID; callers build a field delta using `LocalEditContext`. Register edits must use the allocated stamp, tag additions the allocated mutation ID, and counters only the local replica component. The returned mutation and pending outbox payload remain immutable. `observeDocument` and `pendingCount` expose Room-backed flows. Close the database when the account store is no longer used.

The schema is exported under `sync-android/schemas`. Version 2 adds queue lease and retry metadata through an explicit, non-destructive migration from version 1. An instrumentation test creates an encrypted version-1 database from its exported schema, migrates it, validates the schema, and checks that its pending payload remains unchanged. This is a development schema, not a published SDK release. Acknowledgment/pull application uses the existing schema; transport and background scheduling are not implemented yet.

## Queue Coordination

Construct `MutationQueue` with the account's `SyncDatabase`. `claim(limit, leaseDurationMs)` transactionally recovers expired leases and claims due mutations in sequence order, selecting at most one unresolved mutation per entity. Defaults are 100 mutations and a 60-second lease; lease durations are bounded to five minutes. Each returned `MutationLease` contains the original typed mutation and a new token. Network operations must use this claim API, not the diagnostic `LocalSyncStore.pendingMutations` listing.

Report failures using `fail(lease, failure, errorCode)`. Only the current unexpired token can change an entry. Error codes must be short uppercase identifiers, not server messages or sensitive payloads. Transient failures return to `PENDING` with persisted attempts and a retry deadline: exponential backoff starts at one second, caps at one hour, and selects a random delay in the upper half of that interval. There is no retry-count eviction. Authentication failures persist an account-wide pause; call `resumeAfterAuthentication` only after credentials are refreshed. Existing in-flight operations are not cancelled by this flag.

`entries` is a Room-backed flow exposing state, attempts, deadlines, lease expiry, and error codes. Conflict/rejected entries remain retained and block subsequent edits to that entity; unrelated entities remain claimable. Terminal transitions rebuild the projection without terminal mutations. No manual discard or terminal recovery API is provided yet. Successful server receipts use `SyncResponseStore` rather than a direct queue-state setter. The existing `pendingCount` still counts only `PENDING`, not every unresolved entry.

Lease deadlines and retry times use persisted wall-clock milliseconds to survive restarts. Wall-clock rollback can delay recovery; fixed leases do not prevent an old network request from completing after expiry. Database token fencing rejects stale local failure updates, and server idempotency must handle duplicate delivery. Multi-handle concurrency is tested; separate-process contention, process kill, and WorkManager execution remain acceptance work.

## Applying Server Responses

Create `SyncResponseStore` with the same account database. Pass each `MutationAck` and its original `MutationLease` to `acknowledge`. A false result means the lease expired, was superseded, or was already resolved; no state changes occur. Leave unknown outcomes queued for replay with the original payload. Successful acknowledgments require a positive revision and authoritative state, update the confirmed base and active projection atomically, and do not change the pull cursor. Rejected/conflict receipts require an error code and no state/revision.

Read `pullCursor()` before fetching a page, then call `applyPull(expectedCursor, page)`. A false result means another pull already advanced the cursor; discard the stale page and fetch from the current cursor. Pages are bounded to 100 changes and must contain increasing revisions; the store persists page changes, received clocks, revision, and next cursor in one transaction. An empty final page is allowed. Network/authentication, workspace binding, response size limits, and cursor signature trust belong to the upcoming transport layer.

Projection reconstruction merges only `PENDING` and `IN_FLIGHT` local deltas over the confirmed base using the existing CRDT rules. This preserves newer local edits against stale server state, not against a legitimately higher LWW timestamp. Rejected payloads remain inspectable, but dependent later mutations stay blocked until explicit recovery is implemented. `SYNCED` entries are retained; no garbage collection is introduced.