# Architecture

CHRONOVAULT is a local-first, Java 21 multi-module application. Everything runs on your
machine. The full write path is: **detect → verify → snapshot → store (hash-addressed) →
journal**. The recovery path is: **plan → protect → restore → verify → commit | rollback**.

## Modules

| Module | Responsibility |
| --- | --- |
| `adapter-sdk` | `ProjectAdapter` SPI + `DetectedProject` record (build/test/typecheck commands, confidence, metadata) |
| `core` | Domain model, storage engine, snapshot engine, health engine, diff engine, recovery, retention, diagnostics |
| `adapters/*` | 9 ServiceLoader-registered project adapters |
| `cli` | Command-line interface + embedded web server + UI assets |

`core` depends only on `adapter-sdk`. The CLI wires everything together; adapters are
discovered at runtime via `ServiceLoader`.

## Vault layout

A project vault lives at `<project>/.chronovault/`:

```
.chronovault/
├── config.json        # project name, and the "detected" health profile
├── metadata.db        # SQLite: checkpoints, snapshots, manifests, operations
├── objects/           # content-addressed, gzip-compressed blob store
│   └── aa/bb/<sha-256 hex>           # sharded by first 2 + next 2 hex chars
└── journal.ndjson     # append-only, fsync'd recovery journal (crash safety)
```

`objects/` is a pool shared by the project. Two files with identical content store
exactly one object (content-addressing gives deduplication for free).

## Storage engine

- **`SqliteMetadataStore`** — checkpoints (id, label, status, snapshot ref, evidence JSON),
  snapshot manifests (ordered file entries with hash + size), and recovery operations.
  Every read/write uses try-with-resources so no statement or connection ever leaks;
  busy-locks on SQLite are avoided.
- **`DiskContentStore`** — SHA-256 of content → `objects/aa/bb/<hash>`; gzip after
  writing, integrity-checked on every read. `verify()` reports corruption as `false`
  rather than throwing, so diagnostics can distinguish "missing" from "corrupt".
- **`RecoveryJournal`** — append-only NDJSON of recovery stages, written and fsync'd
  before each *visible* state change. On vault open, `RecoveryGuard.reconcile`
  compares journal vs metadata and rolls forward any interrupted operation. The journal
  keeps the latest entry per operation id, so finished operations always win.

## Snapshot engine

`DefaultSnapshotEngine` walks the project (respecting ignore rules), deduplicates, and
writes a `SnapshotManifest` of `(path, kind, size, sha256)`. Directory and symlink
entries are modeled explicitly (`DIRECTORY`, `SYMLINK`) so restores are faithful and
diffs never attempt to write files to directories.

## Health engine

`DefaultHealthProfileBuilder` turns a `DetectedProject` into a runnable profile:
`Build`, `Tests`, optional `Typecheck`, and custom commands (each with a working dir,
timeout, and required/optional flag). `HealthEngine.run` executes them with output
capture and **fail-fast** on required failures.

A checkpoint is only `VERIFIED` when the whole profile passes at creation time.
`findLastVerified` trusts the checkpoint's authoritative status, never a stale
`passed` flag in evidence JSON.

## Diff engine

`DiffEngine` compares two snapshot manifests and emits typed changes
(`PutFile`, `PutSymlink`, `Delete`, `Rename`) with a precomputed `RestorePlan`
totalling added/removed bytes. Directories produce no changes; symlinks restore as
symlinks.

## Recovery flow

```
plan  ->  RestorePlan (diff target vs current)
protect ->   snapshot current state (protective checkpoint, status UNVERIFIED)
restore ->   apply diffs ATOMICALLY (validate hashes before writing)
verify  ->   run the health profile against the restored tree
if pass:  commit   -> mark target VERIFIED, record COMPLETED operation
if fail:  rollback -> restore the protective snapshot, record ROLLED_BACK
```

Every stage is written to the journal *before* the side effect it protects, so a crash
between stages is reconciled on the next open. A failed verification never leaves a
half-restored tree.

## Retention

`RetentionService` applies the configured policy: keep pinned and latest N checkpoints,
drop older snapshots, then garbage-collect unreferenced objects bottom-up (deepest
directories first) so empty directory pruning never hits `DirectoryNotEmptyException`.

## Web server

The CLI embeds a JDK `com.sun.net.httpserver` server (default port 7723) exposing a
JSON API and the static dashboard from `cli/src/main/resources/web/`. It streams
vault events over SSE (`/api/events`) so the timeline updates live. See
[web-ui.md](web-ui.md).

## Safety invariants

1. Protect before mutate: the pre-recovery state is always snapshotted first.
2. Journal before act: no visible state change without a prior fsync'd journal entry.
3. Verify after restore: recovery is not complete until the restored tree passes health.
4. Rollback on doubt: any verification failure reverts to the protected state.
5. Path safety: restored paths are validated against the project root; traversal and
   absolute-path escapes are rejected (`PathSafety`).