# IDE integrations

CHRONOVAULT ships two companion integrations:

- `vscode-extension/` — a VS Code extension that wraps the CLI.
- `intellij-plugin/` — an IntelliJ IDEA plugin scaffold that wraps the CLI.

Both are thin wrappers: the CLI (`chronovault`) is the single source of truth, so the
integrations stay small, portable, and always consistent with the core.

## VS Code extension (`vscode-extension/`)

Pure JavaScript, no build step. Load it via **Extensions → … → Install from VSIX** after
`vsce package`.

### Capabilities

- **Checkpoint** — `ChronoVault: Create checkpoint` command (runs the CLI in the opened
  workspace folder).
- **Restore** — `ChronoVault: Restore last good state` command.
- **Diagnose** — `ChronoVault: What broke it?` runs `chronovault diagnose` and surfaces
  the evidence cards in a panel.
- **Embedded dashboard** — a **Dashboard** webview view in the CHRONOVAULT Activity Bar
  loads the full console (timeline, health map, storage, recovery wizard, live SSE) inside
  VS Code. No browser needed. `ChronoVault: Open dashboard` focuses it;
  `ChronoVault: Open dashboard in browser` opens the classic browser path.
- **Refresh dashboard** — `ChronoVault: Refresh dashboard` (view-title action).
- **Status bar** — shows protection status; click to act.

### Embedded dashboard internals

- The dashboard is the **same code** served by `chronovault ui` (`cli/src/main/resources/web`),
  copied into `vscode-extension/webview/` by `scripts/sync-dashboard.sh` and verified in-sync
  by `node packageManifest.test.js`. Never edit the webview copy by hand.
- The webview runs under a strict CSP (`default-src 'none'`, per-load script nonces,
  `connect-src 'none'`). `bridge.js` shims `window.fetch` and `EventSource` to the
  extension's `postMessage` bus; `dashboardServer.js` launches `chronovault ui --port <free>`
  and proxies HTTP/SSE. The server is stopped when the view closes.
- If no project or no CLI runtime is found, the view shows an embedded setup page with
  **Configure CLI / Locate Runtime / Retry / Open in Browser**.

### Requirements

- `chronovault` CLI on `PATH` (or set `chronovault.cliPath` in settings).
- Java 21+.

## IntelliJ plugin (`intellij-plugin/`)

A Gradle-based IntelliJ Platform plugin. Run `./gradlew buildPlugin` and install the
generated zip via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

### Capabilities

- Toolbox actions: `ChronoVault: Checkpoint`, `ChronoVault: Restore`, `ChronoVault: Diagnose`,
  `ChronoVault: Open Dashboard`, `ChronoVault: Open Dashboard in Browser`.
- **Embedded dashboard** — the CHRONOVAULT tool window hosts the dashboard in-place via
  JCEF (`http://127.0.0.1:<port>/`, same shared code, loopback-only). If JCEF or the CLI
  runtime is unavailable, the tool window falls back to a native panel with the action
  toolbar and an **Open in Browser** link.
- **Restore confirmation** — `Restore` asks for confirmation before writing the verified
  checkpoint back; rollback protection is unchanged.
- Executes the CLI in the current project's root with `CHRONOVAULT_PROJECT` set, so the
  vault path matches the editor's view of the project. The dashboard server's lifecycle is
  bound to the project and stops on close.

### Requirements

- IntelliJ IDEA 2023.2+ (2023.1+ for the compatibility matrix; `sinceBuild 232`, `untilBuild 251.*`).
- `chronovault` CLI on `PATH` (or `CHRONOVAULT_CLI`), Java 21+.

## Running CHRONOVAULT's own test suite from the IDE

CHRONOVAULT is a Gradle project. Open the repo root, then run the tests.

### From IntelliJ IDEA

1. **File → Open** → select the `chronovault` directory → Open as Project (Gradle import).
   Use **JDK 21** (`/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` on macOS).
2. Wait for Gradle sync to finish.
3. Open the **Gradle** tool window → `chronovault` → `core` → `Tasks` → `verification` →
   double-click **test**.
4. Or open any test under `core/src/test/java/dev/chronovault/core/...` and click the
   green run gutter icon **Run 'ClassName'** (JUnit 5 built-in).

### From VS Code

With the **Gradle for Java** extension installed:

1. `File → Open Folder` → `chronovault`.
2. Open `core/src/test/java/dev/chronovault/core/recovery/SnapshotAndRecoveryTest.java`.
3. Click **Run Test** above the class declaration, or use the Test Explorer
   (Ctrl/Cmd+Shift+P → **Test: Run All Tests**).

### From the terminal (reference)

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home \
  ./gradlew :core:test
```

Expected result: **48 tests, 0 failures** (`BUILD SUCCESSFUL`).

> Note: the system default JVM on this machine is Java 25. Always target the Temurin 21
> JDK, either via `JAVA_HOME` or by letting Gradle resolve its toolchain automatically.