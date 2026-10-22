# Sync Protocol v1

Status: contracts, merge engines, persistent endpoints, and the Android transport/response pipeline are implemented. Live Android/.NET interoperability is verified within the [documented test boundary](interoperability.md). The initial schema is the checklist schema, not an arbitrary user-defined field registry.

## Identity and Wire Format

Use UTF-8 JSON with exact camelCase member names. Unknown members, duplicate members, null mandatory values, numeric strings, unsupported versions, and noncanonical UUIDs are rejected. IDs are nonempty lowercase UUIDs with hyphens. Integers must fit signed 64-bit storage; local sequences and counter components are additionally bounded at 10^12.

Every mutation contains `protocolVersion`, `replicaId`, `mutationId`, `localSequence`, `entityId`, and `delta`. These values are immutable across retries. A changed local edit gets a new mutation ID. A device reinstallation receives a new replica ID.

## Delta Policies

`delta.registers` maps field names to `{ "value": ..., "stamp": { "physicalMs": ..., "logical": ..., "replicaId": ... } }`. The checklist supports string `title` (500 characters) and boolean `completed`. Timestamps more than five minutes ahead of the server produce `CLOCK_SKEW`; old offline timestamps remain admissible.

`delta.counters.quantity` contains `positive` and `negative` maps. Send only the sending replica's changed absolute components, not a numeric increment and not another replica's components. Each component is bounded to 10^12 and merges with maximum. Quantities are derived from positive totals minus negative totals.

`delta.sets.tags` contains `adds` (dot-to-tag map) and `removes` (observed dot array). An add dot is the mutation ID; one mutation can add at most one new tag. Tags are at most 100 characters. A removal lists observed add dots, not a tag-name deletion instruction. Retain removed dots, including when removal arrives before its corresponding addition. Do not resend prior additions under a new mutation ID.

`delta.deleted: true` creates a permanent tombstone. Late updates can merge metadata but cannot make the entity visible again. Recreating an item requires a new entity ID.

## Payload Fingerprints

The server deserializes the typed mutation, serializes all protocol fields with defaults, sorts JSON object members ordinally at every level, preserves array order, and SHA-256 hashes the UTF-8 canonical JSON. Counter/set metadata has a deterministic sorted representation. Clients need not compute the hash; they must retain and resend the same mutation content. This is a server-defined typed fingerprint, not a claim of general RFC 8785 compatibility.

## Push

`POST /v1/sync/push` accepts `{ "protocolVersion": 1, "replicaId": "...", "mutations": [...] }`. The batch is ordered and bounded to 100 mutations and 1 MiB decompressed JSON. JWT claims establish workspace and replica ownership; identifiers in the body are not authorization.

Each mutation has its own transaction. Earlier mutations may commit even if a later request operation is interrupted. The response contains ordered `acknowledgments` with mutation ID, status, revision, authoritative entity state, and optional error code. An interrupted request must be retried with the same mutations. A `SYNCED` receipt acknowledges deterministic merging, including a losing LWW update; it does not assert every proposed field won.

An immutable ID reused with another payload returns `IDEMPOTENCY_MISMATCH` and does not overwrite the original receipt. Validation and clock-skew results are actionable rejected acknowledgments; correcting the input requires a new mutation ID.

## Pull

`GET /v1/sync/pull?cursor=...&limit=100` returns `changes`, `nextCursor`, and `hasMore`. Omit the cursor to bootstrap from retained history. Signed/versioned cursors bind workspace and revision. A wrong-workspace, malformed, or ahead-of-history cursor is rejected explicitly. There is no history expiry or snapshot reset in v1.

Clients merge changes into their confirmed base and replay pending local overlays. Commit the page application and next cursor atomically. Push acknowledgments must not advance the pull cursor: unrelated changes may precede the acknowledged revision.

## Compression and Errors

GZIP request compression is explicit: send `Content-Encoding: gzip` with a gzipped JSON body and `Content-Type: application/json`. Response compression is negotiated with `Accept-Encoding: gzip`. Ordinary JSON requests remain supported. Compressed and expanded sizes are bounded independently.

Transport/authentication failures use HTTP status and Problem Details. Per-mutation semantic failures use ordered acknowledgments. Retry transient network/server failures with the same content; pause authentication failures for credential recovery. Never turn an unknown outcome into a new operation ID.