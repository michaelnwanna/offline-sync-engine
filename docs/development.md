# Development Guide

## Requirements

- .NET 10 SDK (selected by the root SDK configuration).
- Docker with Compose, or a separately managed PostgreSQL 17 database.
- Optional Redis 7 for receipt caching. Leave its connection unset to use PostgreSQL directly.
- Android: JDK 21, the checked-in Gradle 8.14.3 wrapper, Android SDK 35, and an API 26+ emulator/device for instrumentation or the sample. Dependency versions are pinned in the Gradle build files.

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

## Android Build and Tests

From the repository root:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./android/gradlew -p android --no-daemon \
  '-Dorg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=512m' \
  :sync-core:test :sync-android:testDebugUnitTest \
  :sync-android:lintDebug :sample:lintDebug :sample:assembleDebug :sample:assembleDebugAndroidTest
```

The exports above select macOS SDK/JDK locations; use your installed paths on other systems. Portable Kotlin tests need no emulator/database. JVM transport tests use MockWebServer and inspect actual GZIP bodies, authorization behavior, and message limits.

For ordinary device tests, start an API 26+ emulator and select it explicitly when multiple devices are available:

```sh
ANDROID_SERIAL=emulator-5554 ./android/gradlew -p android \
  :sync-android:connectedDebugAndroidTest :sample:connectedDebugAndroidTest
```

Live-server tests explicitly skip unless their opt-in arguments are supplied. The [interoperability guide](interoperability.md) provides the disposable PostgreSQL/Kestrel harness and staged commands; do not run its radio, process-termination, Doze, or reboot coordinators on an everyday emulator. Staged tests must retain installation data/APKs between phases.

Reports are under each module's `build/reports`; Android connected-test results are under `build/reports/androidTests/connected`. Heap dumps (`*.hprof`), build output, local SDK paths, keys, and credentials are not source deliverables.

## Android SDK Integration

Open `SyncDatabase` off the main thread with a stable account/workspace scope. `LocalSyncStore.edit` atomically allocates the replica identity, mutation UUID, sequence, hybrid clock, projected state, and immutable outbox entry. Use the allocated stamp for register edits, mutation ID for tag additions, and only the local replica's counter components. Room-backed flows expose documents and queue state.

Use `MutationQueue.claim` for delivery, not the diagnostic pending-mutation listing. Claims are fenced by expiring tokens; transient failures persist attempts and jittered retry deadlines without retry-count eviction. Authentication pause is account-wide. Conflict/rejected mutations remain retained and block dependent work on their entity, not unrelated entities. No manual terminal discard/recovery API is provided yet.

`SyncResponseStore` acknowledges the exact leased mutation and atomically merges confirmed state with remaining pending overlays. Pull application checks the expected cursor and commits the page with its next cursor. `SYNCED` entries, receipts, history, and tombstones are retained; no garbage collection is introduced.

Implement `SyncWorkerHost` on the application to open an account-scoped `SyncWorkerSession` containing a queue and `SyncRunner`. Supply an `HttpSyncTransport` with an injectable `SyncTokenProvider`. Use `SyncScheduler.start` for the periodic connected sweep and `requestSync` after edits and on foreground entry; queue leases, not WorkManager uniqueness alone, coordinate delivery. Resume authentication-paused work after refreshing credentials. Action-required background pauses need explicit recovery before resuming.

The exported Room schema includes a tested, non-destructive v1-to-v2 migration. Lease/retry deadlines use wall-clock time: clock rollback can delay recovery, and an expired network request may still reach the server. Local fencing plus durable server idempotency handles duplicate outcomes.

## Fieldlist Sample

```sh
./android/gradlew -p android :sample:installDebug
adb -s emulator-5554 shell am start -n dev.offlinesync.sample/.ChecklistActivity
```

Fieldlist supports offline create/rename/complete, quantity changes, observed tag additions/removals, permanent deletion, queue status, sync requests, and connection/credential recovery. Local edits work before a server is configured.

For a normal development API:

1. Start infrastructure, migrate, and run the API using the earlier sections. For emulator access, bind the development API explicitly to `http://0.0.0.0:5080` only on a trusted local network; never expose development credentials publicly.
2. Open Fieldlist connection settings and copy that installation's replica UUID.
3. Create a short-lived development JWT using that replica UUID and a workspace UUID. Use the same workspace but a different installation's own replica UUID for the second device.
4. Enter `http://10.0.2.2:5080` in the debug emulator sample and paste its JWT. The token audience remains the audience configured on the API, not necessarily the URL used by Android.
5. Make independent offline edits, reconnect, and request sync on both installations. Use the separate opt-in harness for automated assertions rather than treating a manual UI run as acceptance evidence.

The normal API does not issue demo tokens. The standalone interop harness uses different test-only issuer/audience settings and port 5087; do not mix its tokens with normal development JWTs.

An installation binds to the origin, issuer, subject, workspace, and replica of its first saved connection. Credential replacement must preserve that identity. Disconnect cancels scoped work and clears the active token, not local documents or the identity binding; use a separate installation for another account/server. This sample is not a general account-management product.

## Recorded Verification and Remaining Work

The [interoperability guide](interoperability.md) records real Kestrel/PostgreSQL runs for two independent installations, lost acknowledgment, active-request termination, process restart, radio reconnection, forced Doze, and reboot. The Doze/reboot runs passed on Android 34 on 2026-10-21. Explicit instrumentation relaunch is part of these staged tests; they do not prove autonomous boot delivery, operation while force-stopped, pre-unlock key access, or OEM behavior.

Run coordinator readiness regressions without Android or Docker:

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools -p test_os_recovery.py -v
```

Seven-day/10,000-mutation recovery, Redis-outage acceptance, production TLS/identity-provider checks, measured bandwidth comparisons, screenshots, and packaging verification remain outstanding. Do not infer those results from passing functional interoperability tests.