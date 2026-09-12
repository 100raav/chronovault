<div align="center">

# CHRONOVAULT

<img src="branding/chronovault-mark-128.png" alt="CHRONOVAULT mark" width="96" />

**Return to the moment your code still worked.**

[![License](https://img.shields.io/badge/license-EULA-blue.svg)](EULA.md)
[![Java](https://img.shields.io/badge/java-21+-orange.svg)](https://adoptium.net)
[![Tests](https://img.shields.io/badge/tests-48%2F48-green.svg)](docs/DEVELOPMENT.md)
[![Build](https://img.shields.io/badge/build-Gradle%208.14-blueviolet.svg)](build.gradle)
[![IDE](https://img.shields.io/badge/IDE-VS%20Code%20%2B%20IntelliJ-2ea44f.svg)](docs/PUBLISHING.md)

Local-first, zero-configuration **development state recovery**. Record *verified*
checkpoints of your project, get told **exactly what broke**, and restore the last
known-good working tree with one command — protected by automatic rollback.

</div>

## What it does

```
chronovault checkpoint      run your real build + tests; snapshot only if everything passes
chronovault diagnose        WHAT BROKE IT? — evidence cards pointing at the culprit file
chronovault plan            preview the exact restore before touching a single file
chronovault restore         protect → restore → verify → commit, or auto-rollback
chronovault ui              local web dashboard on http://localhost:7723
```

A checkpoint is **verified**: your actual build and test commands must pass before the
snapshot is trusted for recovery — no fake green. If a restore's post-check fails,
CHRONOVAULT **rolls back automatically** to the exact state it protected before touching
a single file.

## Why CHRONOVAULT?

- Git history won't help when you need *"the working version from yesterday, now"`.
- Reverting to a known-good commit is manual, slow, and mixes recovery with version control.
- CHRONOVAULT is a **time vault for your working tree** — independent of Git, built on
  content-addressed deduplication so re-checkpoints cost almost nothing.

## Features

- **Verified checkpoints** — snapshots are trusted only when real build/test checks pass.
- **Automatic rollback** — every recovery is protected and reversible; the recovery
  journal makes it crash-safe.
- **What-broke-it diagnosis** — evidence-based: failing check, changed files, likely
  culprit.
- **Deduplicated storage** — SHA-256 content addressing + gzip-compressed objects in a
  pool `.chronovault` directory. No cloud, no servers.
- **9 project adapters** — Maven, Gradle, Node, Python, Rust, Go, .NET, C/C++, Generic.
- **Web dashboard** — zoomable temporal timeline, state map, checkpoint inspector,
  restore wizard, live event stream, command palette.
- **Selective restore** — recover only the files you need (`restore --only=src/lib.js`).
- **Retention & GC** — pin checkpoints, prune old snapshots, reclaim space safely.
- **IDE integrations** — companion extension for **VS Code** and plugin for
  **IntelliJ IDEA**.

## Quick start

Requires **Java 21+**.

```bash
git clone https://github.com/100raav/chronovault.git
cd chronovault
./gradlew :cli:installDist          # build the CLI

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

Or try it immediately on a self-contained sample (Node, no toolchain install):

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
| `restore [--to ID] [--only P,P] [--no-verify] [--yes]` | Protect → restore → verify → commit or rollback |
| `compare [--a ID] [--b ID] [--file PATH]` | Diff two checkpoint snapshots |
| `diagnose` | "What broke it?" evidence cards |
| `history` | Recovery operation log |
| `gc [--dry-run]` | Apply retention + garbage collection |
| `storage` | Storage statistics and deduplication ratio |
| `pin ID` / `unpin ID` | Protect a checkpoint from GC |
| `ui [--port N] [--open]` | Launch the local web dashboard |
| `version` | Version information |

Every command accepts `--json` for scripting.

## IDE integrations

- **VS Code** — install `vscode-extension/chronovault-1.0.2.vsix` (Extensions → … →
  *Install from VSIX*), or [find it on the marketplace](docs/PUBLISHING.md).
- **IntelliJ IDEA** — install
  `intellij-plugin/build/distributions/chronovault-intellij-1.0.2.zip` (Settings →
  Plugins → ⚙ → *Install Plugin from Disk*).

Both are thin wrappers over the CLI; full instructions in
[docs/ide-integrations.md](docs/ide-integrations.md).

## Documentation

| Guide | What it covers |
| --- | --- |
| [Getting started](docs/QUICKSTART.md) | 5-minute tour |
| [Installation](docs/INSTALLATION.md) | Binary, source, and IDE installs |
| [Configuration](docs/CONFIGURATION.md) | `config.json`, profiles, trust policy |
| [Supported projects](docs/SUPPORTED_PROJECTS.md) | The 9 adapters |
| [Health profiles](docs/HEALTH_PROFILES.md) | How verification works |
| [CLI reference](docs/cli.md) | All commands and flags |
| [Web dashboard](docs/web-ui.md) | Timeline, wizard, state map |
| [Recovery guide](docs/recovery-guide.md) | Restore + rollback walkthrough |
| [Security model](docs/security.md) | Local-first, permissions, journaling |
| [Architecture](docs/architecture.md) | Core design |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Common issues and fixes |
| [Development](docs/DEVELOPMENT.md) | Building, testing, extending |
| [Publishing](docs/PUBLISHING.md) | Releasing to VS Code / JetBrains marketplaces |
| [Privacy](docs/PRIVACY.md) | What CHRONOVAULT stores and sends |

## Building from source

```bash
./gradlew build             # compile everything + run all 48 core tests
./gradlew :cli:installDist  # ready-to-run CLI in cli/build/install/chronovault/bin
```

Requires Java 21 (Temurin recommended) and Gradle 8.x (wrapper included).

## Project structure

```
adapter-sdk/      Service-provider interface for project detection
core/             Domain, storage, snapshot, health, diff, recovery, retention
adapters/         9 project adapters (Maven, Gradle, Node, Python, Rust, Go, .NET, C/C++, Generic)
cli/              CLI + built-in web server + web dashboard
sample-projects/  Runnable demo projects (Java/Maven, Node, Python)
docs/             Architecture, CLI, web UI, security, recovery, publishing
vscode-extension/ VS Code companion extension
intellij-plugin/  IntelliJ IDEA companion plugin
branding/         Logo and brand assets
```

## License

[CHRONOVAULT EULA](EULA.md) © 2026 [Saurav Kumar Bichha](https://github.com/100raav).

By using CHRONOVAULT you agree to the End User License Agreement (see
[EULA.md](EULA.md)). CHRONOVAULT is local-first by design: your code and history never
leave your machine.