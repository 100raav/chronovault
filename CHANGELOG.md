# Changelog

All notable changes to CHRONOVAULT are documented in this file. Dates are in
`YYYY-MM-DD` format.

## [1.0.1] — 2026-09-12

### Fixed

- **VS Code CLI discovery** — the extension now resolves the `chronovault` runtime
  platform-aware (configured `cliPath` → bundled → `PATH` → safe user-scoped
  locations on macOS/Linux/Windows) instead of requiring a bare `chronovault` on
  `PATH`. No developer-specific paths.
- **VS Code direct access** — CHRONOVAULT Activity Bar/sidebar with one-click
  Checkpoint, Verify Health, What Broke It?, Restore, Status, and Dashboard; the
  sidebar and status bar refresh automatically after every operation (no
  Reload Window).
- **VS Code actionable errors** — a missing runtime now shows
  "CHRONOVAULT runtime could not be located" with **Configure CLI / Locate
  Runtime / Retry** actions (rate-limited, so it never spams every startup).
- **VS Code workspace resolution** — single-folder, active-editor, vault-marker,
  and multi-root workspaces are handled; `process.cwd()` is never assumed.
- **IntelliJ runtime discovery** — same class of fix: `CHRONOVAULT_CLI` override,
  then `PATH`, then safe platform locations, with a clear message when missing.
- **IntelliJ direct access** — minimal native CHRONOVAULT Tool Window exposing the
  existing actions for one-click use; all commands still run off the UI thread.

[1.0.1]: https://github.com/100raav/chronovault/releases/tag/v1.0.1

## [1.0.0] — 2026-09-12

### Added

- **Verified checkpoints** — real build/test verification before every snapshot;
  only `VERIFIED HEALTHY` snapshots are recovery targets.
- **Evidence-based diagnosis** — `chronovault diagnose` with FACT/OBS/HYP evidence
  cards.
- **Protected recovery** — restore → re-verify → commit, or automatic rollback to the
  exact pre-restore state.
- **Trust policy** — `ASK` / `ALLOWLIST_ONLY` / `ALLOW_ALL` gating for health-check
  commands and the new `chronovault trust` command.
- **Local web dashboard** — timeline, restore wizard, diffs, storage stats,
  loopback-only, CORS-restricted (No remote access).
- **9 built-in adapters** — Maven, Gradle, Node.js, Python, Rust, Go, .NET, C/C++,
  and Generic (covering 10 project types), discovered via `ServiceLoader`.
- **IDE plugins** — VS Code extension and IntelliJ plugin wrapping the CLI.
- **Crash-safety & integrity** — append-only snapshots, recovery journal,
  content-addressed, deduplicated store.

### Security

- Secrets excluded from snapshots by default: `.env`, `.env.*`, `.ssh`, `.aws`,
  `*.pem`, `*.key`, `*.jks`, `*.p12`, `*.keystore`, `id_rsa`, `id_ed25519`,
  `.npmrc`, `.yarnrc`, `credentials`, `secrets`, `*.local.yaml`.
- Strict restores never delete ignored files (`.git`/`.env` survive the sweep).
- Web server binds loopback only; scheme restricted to localhost origins.

### Fixed

- Incremental snapshot reuse now skips unchanged files (perf).
- Unreadable files fail snapshot creation loudly instead of silently.
- Zero-check health profiles report ERROR (never falsely healthy).
- Vault storage stats now report real logical bytes (summed from snapshot
  manifests) instead of mirroring physical disk usage.
- IntelliJ build migrated to the IntelliJ Platform Gradle Plugin 2.x (Gradle 9.x
  wrapper, Platform 2024.3.1); `verifyPlugin` replaces the deprecated 1.x verifier
  task. `buildPlugin` now produces the IPGP 2.x zip layout.
- Test suite expanded (48 tests) with storage-stats regression coverage and the
  web dashboard exercised by real browser captures (screenshots in
  `docs/screenshots/`).

[1.0.0]: https://github.com/100raav/chronovault/releases/tag/v1.0.0