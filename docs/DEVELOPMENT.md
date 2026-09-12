# Development

## Layout

```
adapter-sdk/       ProjectAdapter + DetectedProject contracts
adapters/          built-in adapters (9 modules via ServiceLoader)
core/              domain logic: snapshots, diffs, restore, health, retention, storage
cli/               entry point: commands + embedded web server + web dashboard assets
intellij-plugin/   IntelliJ IDEA plugin (standalone Gradle project)
vscode-extension/  VS Code extension (TypeScript-free, plain Node)
branding/          logo + icon sources
sample-projects/   runnable Node.js & Python demo trees used by the test suite
```

## Prerequisites

- Java 21 (build toolchain: JDK 17+ for the IntelliJ plugin)
- Gradle via the wrapper (`./gradlew`)
- `node` (used only for `node --check` on the web dashboard and VS Code packaging)

## Build & test

```bash
./gradlew build          # compiles everything + runs the core test suite
./gradlew :cli:installDist
node --check cli/src/main/resources/web/app.js
```

The CLI test suite covers: snapshot ignore rules, strict-restore preservation of
ignored files, zero-check health profiles, trust allowlist enforcement, crash-safety,
rollback, retention, diffing, and path safety.

## Web dashboard

Assets live in `cli/src/main/resources/web/` (`index.html`, `app.js`, `styles.css`)
and are served by the CLI at runtime. Edit the flat files, then re-run
`./gradlew :cli:installDist` to pick them up. `node --check` catches syntax errors
fast; there is no bundler.

## IDE plugins

- **VS Code** — `vscode-extension/` is a plain Node extension (no TypeScript).
  Package with `vsce package` (or `npm exec -y @vscode/vsce package`).
- **IntelliJ** — standalone project with the **IntelliJ Platform Gradle Plugin 2.x** and
  its own Gradle wrapper (Gradle 9.x, Java 17 toolchain, Platform 2024.3.1):
  `cd intellij-plugin && ./gradlew buildPlugin`.
  The plugin's `pluginVerification` block verifies against IC 2023.2.5 and IC 2024.3.1;
  run it with `cd intellij-plugin && ./gradlew verifyPlugin` (reports in
  `intellij-plugin/build/reports/pluginVerifier/<IDE>/`).

## Adding an adapter

1. Create `adapters/<name>/` as its own Gradle module (copy an existing one).
2. Implement `ProjectAdapter`; declare `META-INF/services/dev.chronovault.sdk.ProjectAdapter`.
3. Ensure module is included by the composite (`settings.gradle` — the root build
   includes `adapters/*` via `includeBuild`).
4. Add a runnable fixture under `sample-projects/` and (optionally) a check in the
   CLI test suite.

## Releasing

`scripts/release.sh` runs the full gate: prerequisites, secret scan, version
consistency, `./gradlew clean build` with dynamically computed test counts, `node
--check`, VSIX packaging, IntelliJ `buildPlugin` (wrapper-based, IPGP 2.x), a **fresh**
`verifyPlugin` run, icon + signing status, documentation/screenshot validation, then
prints a report with the manual marketplace steps. It never publishes. Manual steps:
[docs/PUBLISHING.md](PUBLISHING.md).

## Style

- Plain Java, records for value types, no framework DI.
- Public API stays minimal and typed; JSON binding via Jackson in the CLI module
  only.
- Keep the CLI/`core` module free of IDE plugins (plugins are thin wrappers).