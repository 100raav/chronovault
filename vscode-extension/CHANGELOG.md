# Change Log

All notable changes are documented in chronological order.

## [1.0.3] — 2026-09-12

### Fixed

- **Webview lifecycle** — the Dashboard view is now a singleton `WebviewPanel`;
  `reveal()` is only ever called on a live, non-disposed panel, eliminating the
  stale `WebviewPanelViewProvider` errors when reopening the view.
- **SSE reconnect hygiene** — the extension closes the previous stream for an
  id before reopening it, and the webview bridge reconnects with capped
  exponential backoff, so reconnects no longer stack duplicate event listeners.
- Concurrency guard: the dashboard server now rejects duplicate simultaneous
  checkpoint/health/recover operations with `409`.

### Changed

- **Time-machine theme** — warp rings, chrono rings, stream-flow timeline,
  chrono halo and clock-glitch flicker (all `prefers-reduced-motion`-gated).
- Dashboard adds runtime diagnostics modal, paginated diff viewer, directed
  health-state map and per-check inspector details; watchdog restarts a dead
  SSE stream automatically.
- New `/api/config` endpoint (adapter, build/test commands, retention).

### Added

- Native dashboard panel (`dashboardPanel.test.js`, `commands.test.js`) and
  `scripts/check-html-sync.js` lint coverage; 27 unit tests total.

[1.0.3]: https://github.com/100raav/chronovault/releases/tag/v1.0.3

## [1.0.2] — 2026-09-12

### Added

- **Embedded Dashboard** — a new **Dashboard** webview view in the CHRONOVAULT Activity
  Bar loads the full temporal console inside VS Code (timeline with hover tooltips, health
  map, storage visualization, recovery wizard, live SSE): no browser required.
- **Dashboard server lifecycle** — picks a free loopback port, starts
  `chronovault ui --port <n>`, and stops it when the view closes.
- **Proxy bridge** — `webview/` runs a strict-CSP (`default-src 'none'`,
  `connect-src 'none'`) copy of the shared dashboard with a `bridge.js`
  `fetch`/`EventSource` shim over the webview postMessage bus; no network from the
  webview.
- **Commands** — `ChronoVault: Open dashboard in browser`,
  `ChronoVault: Refresh dashboard`, `ChronoVault: Configure CLI path`,
  `ChronoVault: Locate CLI runtime`, `ChronoVault: Retry locating runtime`.
- **Embedded setup pages** — when a project or runtime is missing the Dashboard view
  offers Configure CLI / Locate Runtime / Retry / Open in Browser.
- **Sync integrity** — `scripts/sync-dashboard.sh` + `packageManifest.test.js` keep the
  webview bundle byte-identical to the canonical dashboard served by `chronovault ui`.

### Changed

- `ChronoVault: Open dashboard` now focuses the embedded Dashboard view instead of opening
  a browser; use **Open dashboard in browser** for the browser path.
- Dashboard now carries a health state machine, hover tooltips, recovery markers,
  explicit-only zoom/pan/fit and reduced-motion-safe animations.

[1.0.2]: https://github.com/100raav/chronovault/releases/tag/v1.0.2

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