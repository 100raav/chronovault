# CHRONOVAULT for VS Code

**Return to the moment your code still worked.**

CHRONOVAULT records *verified* checkpoints of your project while you work, tells you
**exactly what broke**, and lets you restore the last known-good state — straight from
your editor. Every recovery is protected by an automatic rollback.

> This extension is a thin wrapper around the [CHRONOVAULT CLI](https://github.com/100raav/chronovault).
> The CLI installs no cloud, adds no servers, and keeps everything in a local
> `.chronovault` directory beside your project.

![mark](icon.png)

## Features

- **Create checkpoint** — verify with your real build/test profile, then snapshot only
  if everything passes.
- **Restore last good state** — return to the last verified checkpoint; if the
  post-restore verification fails, CHRONOVAULT automatically rolls back to the exact
  state it protected.
- **What broke it?** — deterministic diagnosis: failing check, changed files, and the
  likely culprit.
- **Protection status** — vault summary in the status bar and command palette.
- **Open dashboard** — launch the local web dashboard (timeline, restore wizard,
  state map, diffs).

## Requirements

- [CHRONOVAULT CLI](https://github.com/100raav/chronovault) on your `PATH`
  (or set `chronovault.cliPath` in settings).
- **Java 21+** to run the CLI.
- VS Code **1.84.0** or newer.

## Usage

Open your project folder, then run any command from the command palette
(`Cmd/Ctrl+Shift+P`):

| Command | Description |
| --- | --- |
| `ChronoVault: Create checkpoint` | Run your build/tests, snapshot only on green |
| `ChronoVault: Restore last good state` | Restore to the last verified checkpoint |
| `ChronoVault: What broke it?` | Diagnose the last state change |
| `ChronoVault: Show protection status` | Vault summary |
| `ChronoVault: Open dashboard` | Open the web dashboard |

A status-bar item shows CHRONOVAULT presence — click it to view protection status.

## Getting started

```bash
cd your-project
chronovault init
chronovault checkpoint --label "baseline"
# ... edit and break something ...
chronovault diagnose
chronovault restore
```

Install the CLI and run `chronovault --help` for all commands.

## Settings

- `chronovault.cliPath` — path to the `chronovault` CLI executable
  (default: `chronovault`, resolved from `PATH`).

The extension sets `CHRONOVAULT_PROJECT` to the opened workspace folder so the vault
path always matches what you see in the editor.

## Publishing to the Marketplace

See [docs/PUBLISHING.md](https://github.com/100raav/chronovault/blob/main/docs/PUBLISHING.md)
in the repository for the full release checklist. The publisher namespace is
`chronovault`; you must claim it in the
[VS Code Marketplace management portal](https://marketplace.visualstudio.com/manage) before
`vsce publish`.

## License

Licensed under the
[CHRONOVAULT End User License Agreement](https://github.com/100raav/chronovault/blob/main/EULA.md)
© 2026 Saurav Kumar Bichha.