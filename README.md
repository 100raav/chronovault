# CHRONOVAULT

**Return to the moment your code still worked.**

CHRONOVAULT is a local-first, zero-configuration development state recovery system.
It watches your project, records *verified* checkpoints, and — when your build breaks —
tells you **exactly what broke** and lets you restore the last known-good state with
one command.

No cloud. No servers in your tech stack. No 200 GB `.git` blobs. A single `.chronovault`
directory beside your project holds everything.

---

## What it does

```
chronovault checkpoint      # run your real build + tests, snapshot only if everything passes
chronovault diagnose        # WHAT BROKE IT? — evidence cards pointing at the culprit file
chronovault plan            # preview the exact restore before touching anything
chronovault restore         # verify → restore → verify again, or auto-rollback
chronovault ui              # local web dashboard on http://localhost:7723
```

A checkpoint is **verified**: your actual build and test commands must pass before the
snapshot is trusted for recovery. If a restore's post-check fails, CHRONOVAULT **rolls
back automatically** to the exact state it protected before touching a single file.

## Why

- Git history won't help when you need "the working version from yesterday, now".
- Chrono-style "revert to working commit" is manual, slow, and mixes concerns with
  version control.
- CHRONOVAULT is a **time vault for your working tree**, independent of Git, with
  content-addressed deduplication so re-checkpoints cost almost nothing.

## Features

- **Verified checkpoints** — snapshots only trusted when real build/test checks pass
- **Automatic rollback** — every recovery is protected and reversible, crash-safe via journal
- **What-broke-it diagnosis** — deterministic evidence: failing check, changed files, likely culprit
- **Deduplicated storage** — SHA-256 content addressing, gzip-compressed objects, pool `.chronovault`
- **9 project adapters** — Maven, Gradle, Node, Python, Rust, Go, .NET, C/C++, Generic
- **Web dashboard** — timeline, diffs, restore wizard, live event stream
- **Retention & GC** — pin checkpoints, prune old snapshots, reclaim space safely
- **Local-first** — everything lives in `.chronovault/` next to your project; no exfiltration

---

## Quick start

Requires **Java 21+**.

```bash
git clone https://github.com/YOUR-ORG/chronovault.git
cd chronovault
./gradlew :cli:installDist          # build the CLI (Java 21)

export PATH="$PWD/cli/build/install/chronovault/bin:$PATH"

cd /path/to/your/project
chronovault init
chronovault checkpoint --label "baseline"
# ... break something ...
chronovault checkpoints
chronovault diagnose
chronovault plan
chronovault restore --yes
```

Or use a ready-made sample that needs no toolchain install (Node; its tests are
self-contained):

```bash
cd sample-projects/node
chronovault init && chronovault checkpoint && chronovault state
```

## Commands

| Command | Description |
| --- | --- |
| `init` | Create a temporal vault for the project |
| `detect` | Report detected project types and confidence |
| `state` | Vault summary: checkpoints, recoveries, storage, protection status |
| `health` | Run the project's configured build/test verification |
| `checkpoint [--label L]` | Run health, snapshot, and record a verified checkpoint |
| `checkpoints [--limit N]` | List checkpoints with verification status |
| `plan [--to ID]` | Show the exact restore plan without executing |
| `restore [--to ID] [--no-verify] [--yes]` | Protect → restore → verify → commit or rollback |
| `compare [--a ID] [--b ID] [--file PATH]` | Diff two checkpoint snapshots |
| `diagnose` | "What broke it?" evidence cards |
| `history` | Recovery operation log |
| `gc [--dry-run]` | Apply retention + garbage collection |
| `storage` | Storage statistics and deduplication ratio |
| `pin ID` / `unpin ID` | Protect a checkpoint from GC |
| `ui [--port N] [--open]` | Launch the local web dashboard |
| `version` | Version information |

Every command accepts `--json` for scripting.

## Documentation

Full documentation lives in [`docs/`](docs/):

- [Architecture](docs/architecture.md)
- [CLI reference](docs/cli.md)
- [Web dashboard](docs/web-ui.md)
- [Security model](docs/security.md)
- [Recovery guide (restore + rollback walkthrough)](docs/recovery-guide.md)
- [IDE integrations](docs/ide-integrations.md)

## Building from source

```bash
./gradlew build          # compile everything + run all 37 core tests
./gradlew :cli:installDist   # ready-to-run CLI in cli/build/install/chronovault/bin
```

## Project structure

```
adapter-sdk/      Service-provider interface for project detection
core/             Domain, storage, snapshot, health, diff, recovery, retention
adapters/         9 project adapters (Maven, Gradle, Node, Python, Rust, Go, .NET, C/C++, Generic)
cli/              CLI + built-in web server + web dashboard (index.html, styles.css, app.js)
sample-projects/  Runnable demo projects (Java/Maven, Node, Python)
docs/             Architecture, CLI, web UI, security, recovery, IDE docs
vscode-extension/ VS Code companion extension
intellij-plugin/  IntelliJ IDEA companion plugin
branding/         Logo and brand assets
```

## License

[MIT](LICENSE) © 2026 CHRONOVAULT contributors.