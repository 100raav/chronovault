# CLI reference

The CLI is a single executable: `chronovault`.

```
usage:
  chronovault init [--name NAME] [--yes]
  chronovault detect
  chronovault state
  chronovault health
  chronovault checkpoint [--label L] [--skip-health]
  chronovault checkpoints [--limit N]
  chronovault restore [--to ID] [--preview] [--no-verify] [--yes]
  chronovault plan [--to ID]
  chronovault compare [--a ID] [--b ID] [--file PATH]
  chronovault diagnose
  chronovault history
  chronovault storage
  chronovault gc [--dry-run]
  chronovault pin ID | unpin ID
  chronovault trust --policy ASK|ALLOWLIST_ONLY|ALLOW_ALL
  chronovault trust --allow "cmd" | --revoke "cmd" | --list
  chronovault ui [--port N] [--open]
  chronovault version
```

Global options:

- `--json` — machine-readable output for any command.
- Environment `CHRONOVAULT_PROJECT` — run against this project root instead of the
  current working directory.
- Environment `CHRONOVAULT_DEBUG` — print full stack traces on error.

## Lifecycle

### `init`
Creates `.chronovault/`, detects the project type (adapter with highest confidence),
and stores the auto-generated health profile. Re-running prints the vault summary.

### `detect`
Lists every adapter match with its confidence percentage (e.g. `Node.js (confidence 90%)`).

### `checkpoint [--label L] [--skip-health]`
Runs the health profile. On **PASS** the snapshot is recorded `VERIFIED HEALTHY` — the
only kind of checkpoint eligible for recovery. On **FAIL** it is recorded `BROKEN`
(safe to keep as a "this is where it broke" marker, never a recovery target).
`--skip-health` records an unverified snapshot without running any commands.

### `checkpoints [--limit N]`
Chronological list with status (`✓ VERIFIED`, `✗ BROKEN`, `◉ ACTIVE`) and health counts.

## Recovery

### `plan [--to ID]`
Dry-run. Shows file/byte totals for the restore: `N modified · M added · K deleted ·
R renamed · X B to restore`, plus the target checkpoint id.

### `restore [--to ID] [--preview] [--no-verify] [--yes]`
The full auto-recovery:
1. Resolves the target (last verified by default) and builds a `RestorePlan`.
2. **Protects** the current (broken) state with a snapshot.
3. Applies the restore.
4. Re-runs the health profile.
5. **Commits** on pass, or **rolls back** to the protected state on failure.

Interactive confirmation is skipped with `--yes` (required in scripts). `--preview`
is an alias for `plan`.

### `compare [--a ID] [--b ID] [--file PATH]`
Snapshot-to-snapshot diff with per-type change counts and a file list. Defaults to
comparing the newest verified checkpoint against the latest.

### `diagnose`
**WHAT BROKE IT?** Runs health, diffs the last verified checkpoint against the current
tree, and prints evidence cards:

```
[FACT] Tests failed (exit 1)
[OBS]  Modified src/index.js
[HYP]  Likely culprit — latest changed files
```

### `history`
Recovery operation log: operation ids, stages, targets, timestamps.

## Storage

### `storage`
Checkpoints, snapshots, object count, physical size, deduplication ratio,
recovery count.

### `gc [--dry-run]`
Applies the retention policy, then garbage-collects unreferenced objects. Pinned
checkpoints and the newest checkpoints are always retained.

### `pin ID` / `unpin ID`
Protect a checkpoint (and its snapshot) from garbage collection.

## Trust

### `trust --policy ASK|ALLOWLIST_ONLY|ALLOW_ALL`
Sets the trust policy applied to every health-check command before it runs:

- `ASK` — default; commands run after the interactive `init` prompt (or `--yes`).
- `ALLOWLIST_ONLY` — only commands listed in the allowlist may run.
- `ALLOW_ALL` — every command runs unguarded.

### `trust --allow "cmd"` / `--revoke "cmd"` / `--list`
Adds/removes an exact command line (e.g. `mvn test`) from the allowlist, or prints it.
Allowlist entries support a trailing `*` prefix wildcard (e.g. `python*`).

Persisted in `.chronovault/config.json`. See [configuration.md](CONFIGURATION.md).

## Web dashboard

### `ui [--port N] [--open]`
Starts the local server on port 7723 (change with `--port`, auto-open browser with
`--open`). See [web-ui.md](web-ui.md).

## Exit codes

| Code | Meaning |
| --- | --- |
| 0 | Success |
| 1 | Runtime error |
| 2 | Usage / argument error |

## Scripting example

```bash
CHRONOVAULT_PROJECT=$PWD chronovault checkpoint --label "nightly" --json |
  jq '.status'
```