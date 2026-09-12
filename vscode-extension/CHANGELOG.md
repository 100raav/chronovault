# Change Log

All notable changes are documented in chronological order.

## [1.0.1] — 2026-09-12

### Fixed

- **Runtime discovery** — the extension now finds the `chronovault` CLI through the
  configured `chronovault.cliPath`, a bundled runtime, `PATH`, then safe
  platform-specific locations (no hard-coded developer paths).
- **Direct access** — CHRONOVAULT Activity Bar/sidebar with one-click actions that
  reuse the existing CLI-backed commands; the view refreshes after every operation.
- **Actionable errors** — a missing runtime offers **Configure CLI / Locate
  Runtime / Retry** instead of a bare "not found", without spamming startup.
- **Workspace handling** — multi-root and active-editor folders are respected;
  `process.cwd()` is never assumed.
- **Verify Health** command added (existing health verification).

[1.0.1]: https://github.com/100raav/chronovault/releases/tag/v1.0.1

## [1.0.0] — 2026-09-12

### Added

- **Checkpoint** command — runs the project's real build/test verification and records
  a verified snapshot.
- **Restore last good state** command — protected, verifiable recovery with automatic
  rollback on failure.
- **What broke it?** diagnosis command with evidence cards.
- **Protection status** command + clickable status-bar item.
- **Open dashboard** command — launches the local CHRONOVAULT web dashboard.
- `chronovault.cliPath` setting for custom CLI locations.
- Marketplace metadata, listing README, icon, and LICENSE.

[1.0.0]: https://github.com/100raav/chronovault/releases/tag/v1.0.0