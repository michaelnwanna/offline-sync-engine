# Security and Operational Boundaries

## Trust Model

The API trusts its configured JWT issuer to assign `workspace` and `replica` claims. Authenticated clients can edit checklist entities inside their workspace, not outside it. Only one installed writer should own a replica identity; sharing it across independent devices invalidates PN-counter assumptions. There is no public signup or permission-management service in this repository.

The host verifies authentication before sync handlers run. Handlers match the claimed replica to the batch and every mutation. Each counter delta may contain only the caller's component; each new tag add uses the mutation ID as its dot. This prevents a caller from directly replacing another replica's counter components. It does not prevent an authorized workspace member from submitting unwanted but valid edits.

Configure a trusted HTTPS issuer and audience outside development. Terminate external traffic with TLS and configure forwarded headers correctly when deploying behind a trusted proxy. Never expose the development HTTP listener, development JWT signing keys, PostgreSQL, or unauthenticated Redis to public networks.

## Replay and Storage

Receipt keys include workspace, replica, and mutation ID. Payload hashes cover typed immutable mutation content. Durable state, receipts, and changes commit together under a workspace cursor-row lock. Redis is trusted infrastructure with network access restricted to the service; it is not an untrusted client cache. Neither Redis nor PostgreSQL is encrypted at rest by this application: configure infrastructure encryption and access controls separately.

Receipts, change history, and tombstones have no automatic expiry. This supports long-offline clients but permits storage growth. Operators must budget and monitor storage; unsafe manual deletion can invalidate replay/convergence guarantees.

Run schema migrations explicitly as a deployment step with suitable privileges. Do not grant runtime database users permission to drop schemas. The development migration command uses the configured connection, so confirm the target before running it.

## Resource Limits

The host bounds request bodies at 1 MiB and uses ASP.NET Core decompression with the request-size limit applied to decompressed input. Batch and pull sizes are limited to 100; JSON depth is limited to 32. Wire tests for compressed expansion, malformed authorization, and all semantic validation edges remain required before production use.

No tenant quota, rate limiter, maximum entity lifetime size, or tombstone compaction is implemented yet. A single workspace serializes writes; abusive clients can consume storage or reduce that workspace's throughput. A production rollout needs quotas, request timeouts, proxy limits, monitoring, backup/recovery drills, key management, and load tests.

## Android Encryption

The Android library uses a random 32-byte SQLCipher passphrase wrapped by an Android Keystore AES-GCM key. Each account identifier is SHA-256 scoped to its own database directory and Keystore alias. Database, WAL, shared-memory, lock, and wrapped-key files reside beneath `noBackupFilesDir`; only a version byte, nonce, and authenticated ciphertext are written to the key file. Account identifiers must include the server/workspace identity when those distinguish separate data scopes. The caller is responsible for supplying the correct authenticated account scope.

First-open key creation is protected by an in-process monitor and an OS file lock. Key files use atomic replacement. Database opening fails rather than deleting files or falling back to plaintext. The passphrase array retained by the SQLCipher helper is cleared when the database is closed; applications must close unused stores. The wrapping key does not require interactive authentication, allowing future background synchronization. Hardware-backed keys depend on device support; no universal hardware-protection claim is made.

Eighteen instrumentation tests passed on an Android 34 emulator, covering encryption, wrong/missing key recovery, account isolation, local atomicity, queue lease/retry recovery, encrypted migration, acknowledgment/pull preservation, and response/cursor rollback. Process death, physical-device Keystore invalidation, OS backup/restore, and concurrent multi-process open remain additional acceptance scenarios. Host applications must exclude any separately stored credentials from backups. Account sign-out/deletion and credential lifecycle are not implemented by this storage layer.

Encryption does not protect a compromised running device or prevent an authorized user from reading their data. Losing wrapped key material or its Keystore key requires explicit application recovery; the SDK does not silently recreate an existing database. A crash during initial key creation may also require recovery rather than automatic destructive reset.

## Current Verification Boundary

Core merge/protocol tests and Android encrypted local-storage instrumentation execute locally. API liveness/authentication tests do not require PostgreSQL. Durable transaction tests require a dedicated real PostgreSQL database; CI is configured to provide one. Redis outage tests and end-to-end Android delivery remain outstanding. This is a development project, not an audited production system.