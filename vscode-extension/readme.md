# CHRONOVAULT for VS Code

**Return to the moment your code still worked.**

You had it working. Then a change broke it — and you're not sure when, or what did it.
CHRONOVAULT records *verified* checkpoints of your project while you work, tells you
**exactly what changed since the last healthy state**, and restores it — straight from
your editor, with an automatic rollback that protects anything you were doing.

Everything stays on your machine in a local `.chronovault` vault beside your project.
No cloud, no accounts, no telemetry.

---

## How it works

1. **Checkpoint** — CHRONOVAULT runs your real build/test health profile. If it
   passes, the current state is snapshotted as a verified checkpoint (with the full
   evidence trail: which checks ran, how long, toolchain fingerprint).
2. **Diagnose** — compare against the last verified checkpoint. CHRONOVAULT lists
   the exact files that changed, the failing check, and the likely culprit.
3. **Restore** — select the state you want (or a subset of files). Current work is
   protected with a snapshot first, files are restored, and the project is
   re-verified. If verification fails, CHRONOVAULT **rolls everything back** to the
   state it protected — you lose nothing.

## Features

- **Verified checkpoints** — snapshots only recorded when your build/test checks pass.
- **What broke it?** — evidence-based diagnosis: failing check, changed files, culprit.
- **Selective restore** — choose individual files/folders to recover.
- **Automatic rollback protection** — a protective snapshot of your current state is
  taken and restored if post-recovery verification fails.
- **Temporal web dashboard** — zoomable timeline, recovery wizard, diffs, storage stats.
- **Local-first vault** — content-addressable, deduplicated, ~no cloud.
- **Protection status** — status-bar indicator showing whether your project is protected.

## Screenshots

> Real captures of the dashboard running against a live vault (dark theme).

![CHRONOVAULT dashboard — timeline, verified checkpoints, health ring, and storage stats](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/01-dashboard.png)

Dashboard, light theme:

![CHRONOVAULT dashboard in light theme](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/02-dashboard-light.png)

Checkpoint inspector with verified snapshot evidence:

![Checkpoint inspector — status, evidence, health profile](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/03-checkpoint-inspector.png)

Timeline state-map view:

![Timeline state map](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/04-timeline-state-map.png)

Health verification run against the project build:

![Health verification operations log](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/05-health-verification.png)

Evidence-based diagnosis ("What broke it?"):

![Detective diagnosis — evidence cards](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/06-diagnosis.png)

Recovery plan review before restore:

![Recovery plan modal — files to change/add/remove](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/07-recovery-plan.png)

Checkpoint diff:

![Checkpoint diff comparator](https://raw.githubusercontent.com/100raav/chronovault/main/docs/screenshots/08-diff-checkpoints.png)

## Requirements

- **CHRONOVAULT CLI** on your `PATH` (or set `chronovault.cliPath` in settings).
- **Java 21+** to run the CLI.
- **VS Code 1.84.0** or newer.

Install the CLI:

```bash
# From the repository: build the CLI into ./cli/build/install/chronovault/bin/chronovault
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/chronovault/bin:$PATH"
```

Or follow [docs/INSTALLATION.md](https://github.com/100raav/chronovault/blob/main/docs/INSTALLATION.md)
for a system-wide install.

## Getting started

```bash
cd your-project
chronovault init          # detect your project type + health profile
chronovault checkpoint    # record your first verified baseline
```

Then open the folder in VS Code and use the commands below.

## Commands

Open the command palette (`Cmd/Ctrl+Shift+P`):

| Command | Description |
| --- | --- |
| `ChronoVault: Create checkpoint` | Run build/tests, snapshot only on green |
| `ChronoVault: Restore last good state` | Restore to the last verified checkpoint |
| `ChronoVault: What broke it?` | Diagnose the last state change |
| `ChronoVault: Show protection status` | Vault summary |
| `ChronoVault: Open dashboard` | Open the web dashboard (timeline, restore, diffs) |

The status-bar shield shows CHRONOVAULT presence — click it for protection status.

## Settings

- `chronovault.cliPath` — path to the `chronovault` CLI executable
  (default: `chronovault`, resolved from `PATH`).

The extension runs every command with `CHRONOVAULT_PROJECT` set to the opened
workspace folder, so the vault always matches what you see in the editor.

## CLI reference

| Command | Description |
| --- | --- |
| `chronovault init` | Initialize a vault + detected health profile |
| `chronovault checkpoint` | Verify and record a snapshot |
| `chronovault checkpoints` | List checkpoints |
| `chronovault health` | Run the health profile |
| `chronovault diagnose` | What broke it? |
| `chronovault restore` | Restore + verify + rollback protection |
| `chronovault compare` | Diff two checkpoints |
| `chronovault gc` | Retention + garbage collection |
| `chronovault ui` | Open the web dashboard |

Run `chronovault --help` for the full list.

## Privacy & security

- All snapshots, health evidence, and recovery logs live in `.chronovault` inside
  your project. Nothing is transmitted anywhere.
- Secrets are excluded by default: `.env*`, `*.pem`, `*.key`, `.ssh`, `.aws`,
  `id_rsa`, `id_ed25519`, and more.
- The web dashboard binds only to `127.0.0.1`.
- Health checks are gated by a trust policy (`ASK`, `ALLOWLIST_ONLY`, `ALLOW_ALL`)
  configurable via `chronovault trust`.

See [docs/security.md](https://github.com/100raav/chronovault/blob/main/docs/security.md)
and [EULA.md](https://github.com/100raav/chronovault/blob/main/EULA.md).

## Support & feedback

Found a bug or have a feature request? Open an issue at
[github.com/100raav/chronovault/issues](https://github.com/100raav/chronovault/issues).
Source: [github.com/100raav/chronovault](https://github.com/100raav/chronovault).

## Release notes

See [CHANGELOG.md](https://github.com/100raav/chronovault/blob/main/vscode-extension/CHANGELOG.md).

## License

Licensed under the
[CHRONOVAULT End User License Agreement](https://github.com/100raav/chronovault/blob/main/EULA.md)
© 2026 Saurav Kumar Bichha.