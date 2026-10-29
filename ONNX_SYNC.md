# ONNX storage and collection sync

## Commands and safety defaults

The same executable JAR/image has three modes:

- Default: existing HTTP API.
- `--prepare-model`: download/verify a model revision and publish it on a filesystem/PVC; no Spring context or DB credentials.
- `--sync`: one-shot, non-web Spring context with only repository/search/model dependencies. No JWT, mail, media or Redis credentials required. Flyway is disabled: migrate through the normal application process first.

`--sync` requires **environment** `SYNC_ENABLED=true`. It defaults to dry-run; this flag prevents accidents and is not authentication. Restrict who can create Jobs, exec containers and access the required credentials. No extra shared "sync key" is used.

Exit codes: 0 = completed (including a successful dry-run), 1 = sync/preparation failed, 2 = sync rejected or could not initialize. Sync emits a final JSON report with a run ID, scan status and separate store counters. Logs contain IDs/stages/error classes, not environment dumps. A failed batch is conservatively counted failed even if a remote task may eventually finish after timeout.

HTTP full-sync routes are disabled unless `SYNC_HTTP_ENABLED=true`. When explicitly enabled they require ADMIN. Meilisearch's old GET mutation is removed; the same `/collection/create_index` path now accepts POST. CLI is the preferred operator interface.

## Filesystem contract

Default directory:

```text
/models/
  1110a243fdf4706b3f48f1d95db1a4f5529b4d41/
    model.onnx
    tokenizer.json
    SHA256SUMS
```

Pinned source: [sentence-transformers/all-MiniLM-L6-v2](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41). Its model card declares Apache-2.0 and English. No multilingual quality claim is made.

- ONNX SHA-256: `6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452`
- Tokenizer SHA-256: `be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037`

`EMBEDDING_MODEL_DIR` defaults to `/models`; `EMBEDDING_MODEL_REVISION`, `EMBEDDING_MODEL_SHA256` and `EMBEDDING_TOKENIZER_SHA256` default to the values above. The Java constants, Docker preparation and GitOps ConfigMap must be changed together for a new model. Never override only the revision and reuse old hashes.

Preparation locks the volume, downloads into a temporary directory on that volume, checks pinned hashes, then atomically renames it. A valid existing revision is reused without network access. A corrupt existing revision fails without overwriting it: investigate it and restore/repopulate that revision during maintenance. The loader independently verifies artifacts before loading native inference; it never trusts a downloaded SHA256SUMS file alone.

Backend and sync processes mount model artifacts read-only. Missing/corrupt models or invalid embeddings fail vector operations; the default API may stay available in degraded mode. An HTTP-ready API does not prove embedding readiness.

The filesystem loader replaces the old classpath loader, including when running with `java -jar`. Old models downloaded into `src/main/resources/models` are no longer used. Each process still has its own ONNX session and native memory.

Default Docker builds contain no model. `--build-arg INCLUDE_EMBEDDING_MODEL=true` remains supported and runs the same pinned preparation into `/models`; a PVC mounted there hides bundled files, so populate the PVC separately.

## Sync configuration

All entries below are environment variables:

- `SYNC_ENABLED`: false by default; must be true to enter CLI.
- `SYNC_TARGET`: `meilisearch`, `qdrant`, or `all` (default).
- `SYNC_DRY_RUN`: true by default. No settings/upserts/deletions; Qdrant mode still validates the model and computes embeddings. It does not prove remote write permissions.
- `SYNC_BATCH_SIZE`: 100, range 1–1000.
- `SYNC_MAX_ATTEMPTS`: 1, range 1–3, bounded exponential backoff for store operations. Validation errors are not retried.
- `SYNC_BATCH_PAUSE_MS`: 100, range 0–60000.
- `SYNC_STORE_TIMEOUT_SECONDS`: 60 for task/future waits.
- `SYNC_CLEANUP`: `none` (default), `preview`, or `delete`.
- `SYNC_MAINTENANCE_CONFIRMED`: false. Required for actual orphan deletion; stop application writers before setting it. This is an operator acknowledgement, not a mechanism that stops writers.

Existing datasource variables: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.

Meilisearch: `MEILI_SEARCH_HOST`, `MEILI_MASTER_KEY`. Qdrant: `QDRANT_HOST`, `QDRANT_PORT` (6334). This revision preserves the existing unauthenticated plaintext Qdrant connection for the private local lab; it does not add server authentication/TLS or expose the service.

Example after securely supplying the credentials and model configuration:

```powershell
$env:SYNC_ENABLED = 'true'
$env:SYNC_TARGET = 'all'
$env:SYNC_DRY_RUN = 'true'
java -jar target/mosquizto-api-0.0.1-SNAPSHOT.jar --sync
```

## Correctness and limitations

- PostgreSQL is authoritative. Both stores index active collections whose owner is active, including private collections; visibility remains a payload/filter field. This preserves the existing Meilisearch owner-search behavior and lets Qdrant retain positive points for recommendation.
- Scanning uses ID keyset pages and an upper ID boundary, with owner fetched inside the query. Rows created after the boundary are handled next run.
- Meilisearch success means task SUCCEEDED; Qdrant success means update Completed. One store can succeed while the other fails; the overall exit remains nonzero.
- Embeddings must have 384 finite components and nonzero norm. Empty text is a failed record, not a fabricated vector.
- PostgreSQL session advisory lock `54820261029` prevents overlapping sync commands/HTTP full sync. It does not lock ordinary application writes. The dedicated connection is held until completion/crash.
- Cleanup only starts after the entire scan succeeds. Preview lists counts; delete rechecks each ID against PostgreSQL and only removes inactive/missing records. A write maintenance window is required because there is no distributed snapshot across stores.
- Meilisearch cleanup adjusts offset pagination after deletion; Qdrant uses its scroll cursor. Unexpected point-ID formats abort cleanup.
- Upserts use the PostgreSQL ID, so retries replace that ID rather than append duplicates.
- Incremental CRUD remains best-effort across stores; there is no durable outbox or distributed transaction. Normal upsert runs can race live edits; use a quiet window when an exact reconciliation snapshot is needed.
- Changing model semantics requires a coordinated full re-embedding; do not mix revisions across API pods and CLI. Versioned vector collections/atomic cutover are not implemented.

## Tests

Use JDK 17 and `./mvnw test`. The optional real native inference test runs with:

```powershell
.\mvnw.cmd '-Dembedding.test.model-dir=C:/path/to/models' test
```

Tests cover invalid vectors/checksums, failed remote operations, masking/normalization, CRUD text/visibility changes, dry-run, cleanup guards, overlap rejection and isolated CLI initialization. Real Linux image/Minikube resource tests remain a separate acceptance step; passing JVM tests alone is not container validation.
