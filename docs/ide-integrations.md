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

- **Checkpoint** — `ChronoVault: Create checkout` command (runs the CLI in the opened
  workspace folder).
- **Restore** — `ChronoVault: Restore last good state` command.
- **Diagnose** — `ChronoVault: What broke it?` runs `chronovault diagnose` and surfaces
  the evidence cards in a notification/panel.
- **Dashboard** — `ChronoVault: Open dashboard` runs `chronovault ui --open`.
- **Status bar** — shows protection status; click to act.

### Requirements

- `chronovault` CLI on `PATH` (or set `chronovault.cliPath` in settings).
- Java 21+.

## IntelliJ plugin (`intellij-plugin/`)

A Gradle-based IntelliJ Platform plugin scaffold. Run `./gradlew buildPlugin` and
install the generated zip via **Settings → Plugins → ⚙ → Install Plugin from Disk**.

### Capabilities

- Toolbox actions: `ChronoVault: Checkpoint`, `ChronoVault: Restore`, `ChronoVault: Diagnose`,
  `ChronoVault: Open Dashboard`.
- Executes the CLI in the current project's root with `CHRONOVAULT_PROJECT` set, so the
  vault path matches the editor's view of the project.

### Requirements

- IntelliJ IDEA 2023.2+ (2023.1+ for compatibility wizard).
- `chronovault` CLI on `PATH`, Java 21+.

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

Expected result: **37 tests, 0 failures** (`BUILD SUCCESSFUL`).

> Note: the system default JVM on this machine is Java 25. Always target the Temurin 21
> JDK, either via `JAVA_HOME` or by letting Gradle resolve its toolchain automatically.