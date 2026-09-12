# Changelog

All notable changes to CHRONOVAULT are documented in this file. Dates are in
`YYYY-MM-DD` format.

## [1.0.3] — 2026-09-12

### Fixed

- **VS Code webview lifecycle** — the dashboard webview is now a true singleton
  `WebviewPanel` (`DashboardPanel`): reopening the view reuses the live panel
  (or rebuilds it only when it was closed), and the surviving `reveal()` calls no
  longer throw on stale `WebviewPanelViewProvider` back-ends. Duplicate SSE
  listeners across reconnects are eliminated by closing the previous stream for
  an id before opening a new one, and a capped exponential-backoff reconnect
  keeps the timeline live through server restarts.
- **Dashboard API concurrency** — `chronovault ui` now rejects duplicate
  simultaneous checkpoint/health/recover operations with `409` instead of
  corrupting vault state.
- Assertion-safe idle `verification` stats in the dashboard caused no harm but
  now report cleanly.

### Changed

- **Time-machine themed dashboard** — warp rings behind the recovery wizard,
  chrono rings and stream-flow timeline (ashes flowing along the ruler), chrono
  halo/pulse on the active health node and a clock-glitch brand flicker; all
  decorations are gated behind `prefers-reduced-motion`.
- **Hardened IDE embedding** — the IntelliJ JCEF panel blocks any navigation
  away from the loopback dashboard server, and the native fallback panel is now
  fully functional (live status banner, checkpoint timeline, one-click
  Checkpoint / Health / Diagnose / Restore) instead of a static message.
- The dashboard now surfaces runtime diagnostics from a single modal
  (component / cause / suggested fix + retry / reload / open diagnostics) and a
  paginated diff viewer, plus a directed health-state map and per-check
  inspector details.

### Added

- `/api/config` endpoint (adapter, build/test commands, retention policy) and
  a watchdog that detects a dead dashboard stream, restarts SSE and reloads.
- Decision to release IDE integrations as 1.0.3 while the core product/CLI
  stays 1.0.0 (no core change in this release).

## [1.0.2] — 2026-09-12

### Added

- **Embedded dashboards in both IDEs — no browser required.** The CHRONOVAULT
  Timeline, Health map, Storage visualization, recovery wizard and live SSE
  updates now run *inside* the IDE:
  - **VS Code** — a new **Dashboard** webview view in the Activity Bar loads the
    full temporal console through a strict-CSP, postMessage-only bridge
    (`default-src 'none'`, nonce scripts, no network from the webview). API and
    SSE traffic is proxied to the local `chronovault ui` server on a free
    loopback port; the server stops when the view closes.
  - **IntelliJ** — the CHRONOVAULT tool window now hosts the dashboard in-place
    via JCEF with a native fallback (action toolbar + Open-in-Browser) when JCEF
    or the runtime is unavailable. The dashboard server lifecycle is bound to the
    project.
- **Premium temporal visualization** (shared dashboard, one source of truth):
  hover tooltips on every timeline node, recovery markers from history, explicit
  wheel/gesture zoom + pan + Fit (no cursor auto-zoom), timeline zoom-out state
  map, and new-checkpoint materialize/reveal animations — all gated behind
  `prefers-reduced-motion`.
- **Health state machine** — the dashboard clearly distinguishes
  Idle / Healthy / Broken / Verifying with an animated ring and status chip.
- **Restore confirmation (IntelliJ)** — restoring now asks for confirmation
  before writing the verified checkpoint back; rollback protection is unchanged.

### Changed

- VS Code `ChronoVault: Open dashboard` now focuses the embedded Dashboard view;
  add `ChronoVault: Open dashboard in browser` for the classic browser path.
- IntelliJ `Open Dashboard` activates the tool window; new
  `Open Dashboard in Browser` action keeps the browser path.
- The dashboard API now exposes `recoveryCount` and hardened
  `Content-Security-Policy` / `Referrer-Policy` / `X-Frame-Options` /
  `X-Content-Type-Options` headers on every served asset (browser dashboard).

### Fixed

- An invalid CSS placeholder could affect `.tl-rec` styling in the timeline;
  removed.

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