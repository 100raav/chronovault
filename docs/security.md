# Security model

CHRONOVAULT is **local-first by design**. Your source and your recovery data never leave
your machine.

## Data residency

- Every vault lives inside your project: `<project>/.chronovault/`.
- `objects/` stores gzip-compressed, SHA-256-addressed blobs. Two identical file
  versions share one blob (deduplicated); there is no plaintext copy beyond your own
  working tree.
- SQLite (`metadata.db`) holds manifests and evidence; the journal is plain NDJSON.
  There are **no telemetry, analytics, or network calls** in the vault path. The only
  listener is the loopback-only UI server you start explicitly.

## Integrity

- **Content addressing** — a blob's filename is the SHA-256 of its content. Any
  tampering or bit-rot changes the hash and is detected on read.
- **Verify on read** — `DiskContentStore.verify()` checks every object before it is
  used; corruption is surfaced as failed verification, never a crash or silent restore.
- **Restore validation** — the diff/restore path validates target hashes against the
  manifest *before* writing, so a corrupted object can never silently poison your tree.

## Recovery safety

- **Protect-before-mutate** — the pre-recovery state is always snapshotted first.
- **Journal-before-act** — every recovery stage is fsync'd to `journal.ndjson` before
  the state change it guards; interrupted recoveries are reconciled on next open
  (`RecoveryGuard.reconcile`), never left half-applied.
- **Verify-after-restore** — a restore is only committed after the restored tree passes
  your real build/test profile.
- **Auto-rollback** — if verification fails, the tree is restored to the protected
  pre-recovery state and the operation is recorded `ROLLED_BACK`.

## Path safety

- All manifest paths are validated against the project root before materialization.
- Absolute paths and `..` traversal are rejected (`PathSafety`), preventing an object
  from escaping the project directory.

## Mitigations (by design, not by build dimension)

| Threat | Mitigation |
| --- | --- |
| Corrupted object restore | Hash validation before write + verify-on-read |
| Crash mid-recovery | fsync'd append-only journal + reconciliation |
| Path traversal / escape | `PathSafety` validation on every restore |
| Untrusted root files | only files under the project root are snapshotted/restored |
| Unauthorized UI access | loopback binding; typed-confirmation required for recovery |
| Malicious restore target | only `VERIFIED`/`ACTIVE` checkpoints are recovery targets |

## Threat model notes

- Vault content is not encrypted at rest; protect the vault directory the way you
  protect your working tree (full-disk encryption, filesystem permissions).
- Symlinks inside the project are restored as symlinks to the original link targets —
  configure adapter ignore rules for untrusted symlink farms if you restore in a
  different environment.
- The UI is a local developer tool, not a multi-user service: no authentication is
  included and it must not be exposed on a shared network.