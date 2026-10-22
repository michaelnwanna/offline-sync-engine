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

## Android Encryption and Connection Identity

The SDK uses a random SQLCipher passphrase wrapped by Android Keystore AES-GCM, app-private nonbackup key material, and no plaintext fallback. Background keys do not require interactive biometric unlock. Hardware-backed keys depend on device support; no universal hardware protection is claimed.

Encryption does not protect a compromised running device or prevent an authorized user from reading their data. Missing/unusable keys produce a non-destructive recovery error. Emulator instrumentation covers encrypted reopen, ordinary SQLite/wrong-key rejection, missing/corrupt wrapped-key failure, and account isolation. Backup exclusion is configured, but a full OS backup/restore drill and physical-device key behavior remain separate acceptance work.

The sample encrypts connection credentials in its account-scoped vault, excludes them from backups, and binds an installation to one server/issuer/subject/workspace/replica identity. JWT payload checks in the connection UI prevent accidental identity mismatch; they are not signature validation or authorization. The server remains the authentication authority. Credential refresh must retain the same binding; the sample does not implement general account switching.

Production transport requires HTTPS. The debug sample allows cleartext only for explicit local/emulator origins. Interoperability coordinators use known public test-only signing keys and a disposable localhost backend; never deploy or expose that harness. Tests write short-lived credentials directly to the vault and do not establish production authentication/TLS correctness.

## Current Verification Boundary

Core merge/protocol, Android encryption/queue, and HTTP transport tests exist and have recorded local runs. Durable server tests require a dedicated real PostgreSQL database; CI is configured to provide one. Live Kestrel/PostgreSQL tests verify cross-installation convergence and staged replay/recovery, including forced Doze and reboot. See [interoperability](interoperability.md) for the exact boundary. Redis outage, physical-device/OEM behavior, pre-unlock execution, and production identity/TLS remain unverified. This is a development project, not an audited production system.