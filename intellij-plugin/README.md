# ChronoVault for IntelliJ IDEA

Snapshot, diagnose, and restore your project's verified states from the Tools menu.
The plugin wraps the `chronovault` CLI — the CLI stays the single source of truth.

## Build & install

```bash
cd intellij-plugin
./gradlew buildPlugin
```

Install `build/distributions/chronovault-intellij-1.0.1.zip` via
**Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Usage

The **Tools** menu gains:

- **Checkpoint** — verify + snapshot the current project.
- **What broke it?** — diagnostics evidence, logged to IDEA.
- **Restore last good state** — restore with rollback protection.
- **Open dashboard** — launch the local web dashboard.

The plugin runs the CLI with `CHRONOVAULT_PROJECT` set to the current project root, so
the vault matches the project you are editing. Set `CHRONOVAULT_CLI` if `chronovault`
is not on `PATH`.

## Requirements

- IntelliJ IDEA 2023.2+.
- `chronovault` CLI on `PATH` and Java 21+.