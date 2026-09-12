# CHRONOVAULT 1.0.3 — RELEASE REPORT

Date: 2026-09-12 · Author: Saurav Kumar Bichha

IDE integration version **1.0.3**; the core product/CLI stays **1.0.0** (no core
change in this release — the version split is intentional and verified by the
release gate). Status values in this report: **PASS** (verified locally),
**FAIL** (gate blocker — none in this release), **NOT RUN** (blocked or manual).

## Objective

Ship CHRONOVAULT 1.0.3 as a production-quality, IDE-native dashboard release
focused on (a) a verified **VS Code WebviewPanel life-cycle fix** so reopening
the dashboard no longer throws, (b) a **hardened, functional IntelliJ plugin**
with a loopback-only capable JCEF guest plus a live native fallback, and
(c) a **time-machine themed dashboard** added from the user's explicit product
direction this cycle. Release gate runs all existing validations, packages both
IDE artifacts with hashes, and stops short of any marketplace action.

## Summary of results

| Gate | Result |
| --- | --- |
| VS Code unit tests (resolver/dashboardServer/packageManifest/dashboardPanel/commands) | PASS (28, 0 fail) |
| Lint (`npm run lint` incl. `check-html-sync` + `check-dashboard-a11y`) | PASS |
| IntelliJ unit tests (pure-JDK + HTTP-stub) | PASS (28, 0 fail) |
| `buildPlugin` → `chronovault-intellij-1.0.3.zip` | PASS |
| Plugin Verifier (fresh) — IC-232.10227.8 / IC-243.22562.145 | PASS (both Compatible) |
| CLI/core build + core tests (15 files) | PASS |
| Artifact icon + structure + patched plugin.xml checks | PASS |
| Secret scan (source + packaged artifacts) | PASS |
| Headless dashboard render (8 screenshots, 1440×900@2x) | PASS |
| IntelliJ signing | **NOT RUN** — no marketplace certificates provided (manual step, see `docs/PUBLISHING.md`) |

## Broken / changed this cycle

### VS Code (`1.0.3`)

- **Fixed** the webview reveal crash: the extension now keeps one real
  `WebviewPanel` (`dashboardPanel.js`) instead of relying on the deprecated
  `WebviewPanelViewProvider` back-end; `reveal()` is only ever invoked on a
  live, non-disposed panel and the panel is rebuilt when the user closes it.
- **SSE reconnect hygiene**: the host closes the previous stream for an id
  before reopening one (no duplicate loopback listeners), and the webview
  bridge reconnects with capped exponential backoff and a `window.cvReconnectSSE`.
- Removed the `chronovault.dashboardView` static webview contribution; the
  `view/title` menus are anchored on the sidebar only. Commands restored to full
  surface (checkpoint, health, restore, diagnose, status, dashboard,
  dashboardBrowser, refreshDashboard, configureCli, locateRuntime, retryRuntime).
- Testability: `dashboardPanel.js` now loads through `_vscode.js` (real
  `vscode` module when available, else a fully stubbed double) enabling native
  Node unit tests; added `dashboardPanel.test.js` (5) and `commands.test.js` (3).

### IntelliJ (`1.0.3`)

- **`JcefSupport`** — reflective JCEF capacity probe (no hard dependency for
  non-JCEF IDEs) plus a `CefRequestHandler` proxy installed before the first
  load that **cancels any navigation away from loopback** (127.0.0.1 /
  localhost / `[::1]`) — the dashboard can never be pointed at a remote host.
- **`NativeDashboardPanel`** — replaced the static fallback message with a real
  console: status banner, checkpoint timeline, and one-click Checkpoint / Health /
  Diagnose / Restore (with confirm). Polls `/api/state` + `/api/checkpoints`
  every 5 seconds off the EDT; dispose-safe.
- **`DashboardModel`** — pure record model mapping dashboard JSON into display
  rows; zero IntelliJ imports, fully unit-tested.
- **`DashboardServer.request(method, path)`** — HTTP client returning the JSON
  body (throws on non-2xx), covered by stubbed-HTTP tests.
- **`MiniJson`** — minimal pure-JDK JSON reader so the plugin needs no Gson
  dependency; fully unit-tested.
- `build.gradle` + `pluginConfiguration.version` + `plugin.xml` change-notes → 1.0.3.

### Shared dashboard (canonical `cli/src/main/resources/web/`, synced to the webview)

- **Time-machine theme** (user-directed): warp rings behind the recovery wizard,
  chrono rings + stream-flow timeline, chrono halo / health-ring breathing,
  clock-glitch brand flicker — every decoration under `prefers-reduced-motion`.
- Runtime diagnostics modal (`#cvError` + `#diagModal`), paginated diff viewer
  (250 rows/page), directed health-state map (SVG state cycle with realized /
  active nodes), per-check inspector details, `/api/config` consumption
  (adapter, build/test commands, retention), and a watchdog that restarts a dead
  SSE stream.
- Footer and `/api/meta` report 1.0.3.

### Dashboard server (`ChronoServer.java`, core module)

- `activeOps` concurrency guard → duplicate simultaneous checkpoint / health /
  recover requests are rejected with `409`; `asyncOp`/`asyncOpStage` release
  their claim via an `onDone` hook.
- `/api/config` GET added; `/api/meta` version → 1.0.3; CLI version reported
  as 1.0.0 (`Main.VERSION` is `private`, mirrored as a literal in `config()`).

## Gap closure (follow-up to the honest acceptance review)

The earlier report listed five gaps between the shipped build and the product
claims. This cycle closed three of them and formally re-verified the feature
surface; the remaining two are environment/manual and stay **NOT RUN** by design.

- **Secure webview asset loading (`asWebviewUri`)** — the dashboard HTML no
  longer references relative `./app.js` / `./bridge.js` / `./styles.css`. The
  canonical source carries `__CV_ICON_URI__`, `__CV_STYLES_URI__`,
  `__CV_BRIDGE_URI__`, `__CV_APP_URI__` placeholders that `dashboardPanel.js`
  resolves through `web.asWebviewUri(vscode.Uri.joinPath(...))` at load time, so
  the webview works under a strict `default-src 'none'` CSP and survives VS
  Code's webview resource root rules. `check-html-sync.js` now rejects relative
  asset refs, and `packageManifest.test.js` asserts the placeholder contract.
- **Accessibility audit** — new `scripts/check-dashboard-a11y.js` verifies dialog
  roles (`role="dialog"` + `aria-modal`), labelled-by targets, the error
  `alertdialog`, polite live regions (`#toasts`, `#healthState`), per-control
  discernible labels (icon-only buttons now `aria-label="Close"`, command
  palette input labelled), decorative `aria-hidden`, and reduced-motion
  coverage. It is wired into `npm run lint`, so a11y regressions fail the lint.
- **Fresh, version-stamped screenshots** — all 8 views in `docs/screenshots/`
  were regenerated headlessly (Puppeteer, 1440×900@2x) against a live
  `chronovault ui` session driving a demo vault through verified → broken →
  diagnosed → recovered states, so the images now show the 1.0.3 time-machine
  theme rather than the pre-theme UI.
- **Feature checklist re-verification** — source-level confirmation that the
  claimed surface exists: `extension.js` registers the dashboard/locate/retry
  commands and surfaces READY/NOT_FOUND runtime states; `cliResolver.js` probes
  READY / NOT_FOUND / INVALID / INCOMPATIBLE; `bridge.js` reconnect backoff
  (`MAX_SSE_RETRIES=5`, `scheduleReconnect`, `reconnectNow`,
  `window.cvReconnectSSE`); `ChronoServer.java` enforces the `409` concurrency
  guard and serves `/api/config`.
- **Gate coverage fix** — `scripts/release.sh` gained step **5b** running the
  VS Code `npm test` and `npm run lint` suites; previously the release gate never
  exercised them even though they were green in CI-style runs.
- **Still NOT RUN (by design):** IntelliJ code signing (no certificates) and
  interactive GUI acceptance in real VS Code / IntelliJ (headless gate cannot
  drive IDE sessions). Both remain documented manual owner actions.

## Architecture notes

- The **dashboard is a single source of truth** in `cli/src/main/resources/web/`;
  the VS Code webview bundle is generated from it by `scripts/sync-dashboard.sh`
  and `scripts/check-html-sync.js` enforces byte-identical sync as part of the lint.
- Webview stays strict-CSP (`default-src 'none'`, per-load nonces, postMessage
  bridge only); every external request (API + SSE) funnels through the extension
  host to the loopback `chronovault ui` process, which is stopped on view close.
- IntelliJ: loopback server lifecycle bound to the project (`Disposer`), JCEF
  guest optionally replaced by the native panel; both paths share
  `DashboardServer` / `DashboardApiClient`.
- Release policy: IDE integrations carry the release version (1.0.3); core CLI
  stays pinned at 1.0.0 unless shipped with a core change.

## Validation

- `./gradlew clean build` (core) — tests computed dynamically; 15 core test
  classes, 0 failures.
- `npm test` — 28 VS Code tests, 0 failures; `npm run lint` clean (syntax,
  webview sync via `check-html-sync.js`, and a11y via `check-dashboard-a11y.js`).
- `:intellij-plugin test` — 28 tests, 0 failures; `buildPlugin` produced the zip
  containing exactly the plugin jar + libs; fresh `verifyPlugin` reported both
  target IDEs **Compatible** and dynamically installable.
- Artifact checks: VSIX icon present, plugin jar ships `META-INF/pluginIcon.svg`,
  patched `plugin.xml` carries id/version/232–251*/vendor, no dev junk in the
  distributions, no secrets scanned in sources or packaged artifacts.
- Live E2E for IDE embedding is reported honestly as manual acceptance in real
  IDEs; the release gate cannot drive GUI sessions.

## Artifacts

- VS Code: `dist/chronovault-1.0.3.vsix`
- IntelliJ: `dist/chronovault-intellij-1.0.3.zip`
- SHA-256 checksums are appended to `dist/RELEASE-REPORT.md` by `scripts/release.sh`.
- CLI: `cli/build/install/chronovault/bin/chronovault` (unchanged, 1.0.0).

Nothing was pushed or published. VS Code publishing and IntelliJ Marketplace
upload, plus IntelliJ code signing, remain manual owner actions
(`docs/PUBLISHING.md`).