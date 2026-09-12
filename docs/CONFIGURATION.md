# Configuration

All vault configuration lives in `.chronovault/config.json` in your project root.
It is created by `chronovault init`.

```json
{
  "version": 1,
  "projectName": "my-app",
  "ignorePatterns": ["build", "target", "dist", "out", ".git", ".env", ".env.*", "*.pem", "*.key", ".ssh", ".aws", "id_rsa", "id_ed25519", "..."],
  "ignoreFiles": [".gitignore", ".git"],
  "trustPolicy": "ASK",
  "symlinkPolicy": "FOLLOW_SAME_PROJECT",
  "allowAllCommands": false,
  "retention": { "keepLatest": 50, "keepDays": 120, "keepPinned": true, "keepLastHealthy": true },
  "healthProfiles": { "default": { ... } },
  "activeHealthProfile": "default",
  "allowlist": []
}
```

## Ignore rules

- `ignorePatterns` — name/dir patterns excluded from snapshots. Defaults already
  cover build output (`build`, `target`, `node_modules`, `.gradle`, …), cache
  directories (`__pycache__`, `.pytest_cache`, …), VCS metadata (`.git`, `.svn`,
  `.hg`), and secrets (`.env`, `.env.*`, `*.pem`, `*.key`, `*.jks`, `*.p12`,
  `*.keystore`, `.aws`, `.ssh`, `.npmrc`, `.yarnrc`, `secrets`, `credentials`,
  `*.local.yaml`, `id_rsa`, `id_ed25519`).
- `ignoreFiles` — text files whose lines (one glob per line) are also respected;
  `.gitignore` and `.git` are respected by default.
- Ignored files are never snapshotted, and a **strict restore never deletes** an
  ignored file — so your `.env` and `.git` survive a restore sweep.

## Health profiles

Keyed by name; `activeHealthProfile` selects one. Each profile:

```json
{
  "matchMarkers": ["pom.xml"],
  "runOnMarkers": true,
  "checks": [
    { "id": "build", "name": "Build", "kind": "BUILD",
      "command": ["mvn", "-q", "compile"], "required": true,
      "timeoutSeconds": 300, "workingSubdir": ".", "failFast": true }
  ]
}
```

- `matchMarkers` — files that identify the project; the profile applies when
  `runOnMarkers` is true and all markers exist.
- `command` — argv array; command-line string is built by joining with spaces.
  See [HEALTH_PROFILES.md](HEALTH_PROFILES.md).

## Trust policy

Controls which health-check commands may run:

| Policy | Behavior |
| --- | --- |
| `ASK` (default) | Commands run after the interactive `init` consent prompt (or `--yes`). |
| `ALLOWLIST_ONLY` | Only command lines present in `allowlist` run; everything else is refused with an ERROR check. |
| `ALLOW_ALL` | Every command runs unguarded. |

Manage from the CLI — never edit JSON by hand:

```bash
chronovault trust --policy ALLOWLIST_ONLY
chronovault trust --allow "mvn test"
chronovault trust --allow "python*"      # trailing * prefix wildcard
chronovault trust --revoke "python*"
chronovault trust --list
```

## Symlink policy

`FOLLOW_SAME_PROJECT` (default) follows symlinks that stay inside the project root
and refuses links that escape it (`ERROR`). Other options: `SKIP`, `RESTORE_AS_LINK`.

## Retention & garbage collection

- Defaults keep the newest **50** checkpoints (`keepLatest`), anything younger than
  **120** days (`keepDays`), **pinned** checkpoints, and the **last healthy**
  checkpoint.
- `chronovault gc` applies retention then deletes unreferenced content-store
  objects. `--dry-run` previews deletions.

## Editing

`chronovault init` regenerates an auto profile on first run; afterwards edit
`config.json` directly (safe: a malformed JSON file aborts commands with a clear
error and never truncates the vault).

See also [security.md](security.md) for the local-first threat model.