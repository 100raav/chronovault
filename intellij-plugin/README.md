# ChronoVault for IntelliJ IDEA

Snapshot, diagnose, and restore your project's verified states from the ChronoVault tool
window. The plugin wraps the `chronovault` CLI — the CLI stays the single source of truth.

## Build & install

```bash
cd intellij-plugin
./gradlew buildPlugin
```

Install `build/distributions/chronovault-intellij-1.0.3.zip` via
**Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Usage

The **ChronoVault** tool window hosts the dashboard in-place (JCEF), and the **Tools**
menu gains:

- **Create Checkpoint** — verify + snapshot the current project.
- **Verify Health** — run the project's build/test checks.
- **What Broke It?** — diagnostics evidence.
- **Restore Last Good State** — restore with rollback protection (asks for confirmation).
- **Open Dashboard** — focus the embedded dashboard tool window.
- **Open Dashboard in Browser** — open the dashboard in your browser.

The embedded dashboard loads the same console served by `chronovault ui`
(`http://127.0.0.1:<free-port>/`, loopback only); the server is started on demand and
stopped when the project closes. If JCEF or the CLI runtime is unavailable, the tool
window falls back to a native panel with the action toolbar and an **Open in Browser** link.

The plugin runs the CLI with `CHRONOVAULT_PROJECT` set to the current project root, so
the vault matches the project you are editing. Set `CHRONOVAULT_CLI` if `chronovault`
is not on `PATH`.

## Develop

```bash
cd intellij-plugin
./gradlew test            # DashboardServer unit tests
./gradlew buildPlugin     # build the installable zip
./gradlew verifyPlugin    # JetBrains Plugin Verifier (IC 2023.2 / 2024.3)
```

## Requirements

- IntelliJ IDEA 2023.2–2025.1 (`sinceBuild 232`, `untilBuild 251.*`).
- `chronovault` CLI on `PATH` (or `CHRONOVAULT_CLI`) and Java 21+.