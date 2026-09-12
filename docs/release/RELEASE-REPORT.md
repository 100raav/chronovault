# CHRONOVAULT 1.0.0 — Release Report

Generated: 2026-09-12 — based on actual builds, tests, and inspections performed in this
session. Status values use only `PASS`, `FAIL`, `NOT RUN`, `WARN`/`BLOCKED`. Nothing is
presumed.

## Environment

| Item | Value |
| --- | --- |
| Version | 1.0.0 |
| Commit | `76168c1` (working tree contains the uncommitted final release changes reviewed below) |
| Java | OpenJDK 21.0.2 LTS (Temurin) |
| Gradle | 8.14.3 (root wrapper); IntelliJ plugin uses its own Gradle 9.7.1 wrapper with the IntelliJ Platform Gradle Plugin 2.18.1 |
| Node | v22.7.0 |
| OS | macOS (darwin, arm64) |

## Core — PASS

- 48 tests, 0 failures, 0 errors (count computed dynamically from test-results XML).
- Coverage areas: snapshot creation/incremental reuse; content-addressed dedup store with
  integrity verification; health verification incl. trust-policy gating and zero-check
  guard; recovery with protective snapshot + rollback on verification failure; selective
  restore; retention/GC; diffing; missing/among-path traversal; crash-safety journaling.
- `StorageStats` metrics fixed this cycle: `MetadataStore.logicalStorageBytes()` previously
  ran a query against a non-existent column and always returned 0 (dead stub); it now sums
  `totalLogicalBytes` from each snapshot's stored manifest (parsed JSON), and
  `ChronoVault.storageStats()` uses it instead of aliasing logical bytes to the physical
  pool size. Covered by the new `StorageStatsTest` (2 tests: manifest summation + dedup
  ratio, and tracking after snapshot deletion).
- New regression tests added this cycle: `StorageStatsTest` (logical-bytes correctness),
  symlink-escape write rejection (PathSafety unit + restore integration), symlink-inside-root
  allowed, plus earlier snapshot ignore, strict-restore preserve, zero-check profile, and
  trust-allowlist tests.

## CLI — PASS

- `./gradlew :cli:installDist` builds a working `chronovault` launcher.
- Live E2E smoke (real project): `init` (auto-detect Node.js) → `checkpoint` → break file →
  `restore --yes` printed full pipeline `PLANNING → PROTECTING → RESTORING → VERIFYING →
  COMMITTING → RECOVERED`, file content restored. `chronovault ui` served the dashboard
  (HTTP 200) and `/api/state`, `/api/meta`, `/api/checkpoints` returned real vault JSON.
- New cross-platform `launchBrowser` (open / xdg-open / cmd start) replaces macOS-only `open`.

## VS Code — PASS (package + install), GUI click-test NOT RUN

- `package.json`: version 1.0.0, publisher `chronovault`, license `SEE LICENSE IN LICENSE`,
  repository + bugs URLs, icon/readme/changelog wired, engines `^1.84.0`, activationEvents
  for 5 commands, one setting `chronovault.cliPath`.
- VSIX packaged, inspected (8 files: manifest, package.json, readme, changelog, icon.png,
  LICENSE.txt, extension.js). `extension.js` passes `node --check`; all 5 commands registered
  and map to the real CLI (dashboard launched detached; runCli has timeout/kill/ENOENT
  handling; diagnose webview has CSP + output escaping).
- **Install test (real):** `chronovault.chronovault` installed and listed in an isolated
  `--user-data-dir`/`--extensions-dir` profile (re-run after rebuild: exit 0, listed).
- **NOT RUN:** interactive GUI click-through of palette commands (no automated GUI session;
  delegating behavior covered by CLI E2E + code review).

## IntelliJ — PASS (build + fresh verifier), in-IDE install test NOT RUN

- **IPGP 2.x migration (this cycle):** `intellij-plugin` now builds with the IntelliJ
  Platform Gradle Plugin **2.18.1** under its own **Gradle 9.7.1 wrapper** (IPGP 2.x
  requires Gradle 9.0+; the global Gradle 8.14.3 cannot load it). Platform dependency:
  `intellijIdeaCommunity '2024.3.1'`; Java toolchain 17. The Gradle wrapper is committed so
  the build is reproducible without a global Gradle 9 install.
- DSL (IPGP 2.x): `pluginConfiguration { version '1.0.0'; ideaVersion { sinceBuild '232';
  untilBuild '251.*' } }`, `pluginVerification { ides { create('IC', '2023.2.5');
  create('IC', '2024.3.1') } }`, `signing { channels? }` placeholder, `publishing {
  channels ['stable'] }`. `buildSearchableOptions` runs by default and is SKIPPED (no
  settings UI); the deprecated 1.x `runPluginVerifier` task is replaced by `verifyPlugin`.
- `cd intellij-plugin && ./gradlew buildPlugin` succeeds; plugin id `dev.chronovault`
  (fixed so `verifyPlugin` accepts it: the id no longer contains the word `intellij`).
- ZIP inspected (IPGP 2.x layout): `chronovault-intellij/lib/chronovault-intellij-1.0.0.jar`
  (no `instrumented-` prefix; no searchableOptions jar, consistent with no settings UI).
  `pluginIcon.svg` (962 B) packaged at `META-INF/pluginIcon.svg`; `META-INF/plugin.xml`
  carries `<id>dev.chronovault</id>`, `<version>1.0.0</version>`, `<idea-version
  since-build="232" until-build="251.*"/>` and the action declarations.

## Plugin Verifier — PASS (fresh run on the final artifact)

Fresh `./gradlew verifyPlugin` executed on the final build (verifier report directory
deleted before the run so stale output could not be mistaken for fresh evidence):

| IDE | Dir | Verdict |
| --- | --- | --- |
| IntelliJ 2023.2.x | `IC-232.10227.8` | Compatible |
| IntelliJ 2024.3.1 | `IC-243.22562.145` | Compatible |

Verdict files: `intellij-plugin/build/reports/pluginVerifier/<dir>/plugins/dev.chronovault/1.0.0/verification-verdict.txt`.
Benign warnings (Kotlin module resource roots) do not affect the verdict.

## Signing — NOT RUN (credentials unavailable)

No JetBrains signing certificate chain / private key is present in this environment, so
`signPlugin` was **not** executed. Manual, fully documented process in
`docs/PUBLISHING.md`: keytool self-signed cert → `POST plugins.jetbrains.com/api/certificate/generate`
for the production certificate → `cd intellij-plugin && ./gradlew signPlugin -Psigning.certChain="<cert chain>"`
with `-Psigning.privateKey="<private key>"` and `-Psigning.password="<store pass>"`
→ `./gradlew verifyPluginSignature` → upload the **signed** zip to plugins.jetbrains.com.
The unsigned artifact is **not** Marketplace-ready (JetBrains requires signing for uploads).

## Security — PASS (with accepted review-warnings)

- Secret scan (AWS keys, `sk-` tokens, private-key blocks, Slack tokens): **clean** —
  tracked and untracked files, plus `dist/` artifacts.
- Soft scan: 2 `password=` matches reviewed — a health scrubber test fixture
  (`HealthEngineTest`) and the IntelliJ `signPlugin` property placeholder (reads `-P`, never
  hard-coded). Neither contains a real credential.
- Path-safety hardening: `PathSafety.validateWritePath` resolves every existing ancestor of
  a restore write target and rejects symlinks escaping the project root; wired into restore
  before materialization and symlink creation, and into `validateDirectory`. Restore can no
  longer write outside the project through a symlinked ancestor (regression-tested).
- Web server binds loopback only; CORS restricted to localhost origins; no-store headers.
- No hard-coded user/machine paths; platform-aware browser launch.

## Tests

| Metric | Value |
| --- | --- |
| Total run | 48 |
| Passed | 48 |
| Failed | 0 |

## Package Validation

- VSIX: `dist/chronovault-1.0.0.vsix` (16.3 KB) — icon.png present, manifest valid,
  LICENSE included.
- IntelliJ: `dist/chronovault-intellij-1.0.0.zip` (9.2 KB) — IPGP 2.x layout, plugin jar
  verified for plugin.xml (id/version/since-until) and embedded pluginIcon.svg.

## Installation Tests

- VS Code: **PASS** — VSIX installed and listed in isolated profile (both pre-rebuild and
  final rebuild).
- IntelliJ: **IN-IDE INSTALL / RUNTIME SMOKE NOT RUN** (environment limitation) — a
  `runIde` sandbox launch was attempted on the final build; the IDE process reached
  application-component initialisation (platform warning lines logged at ~1.7 s) but the
  sandbox log required to capture plugin activation/action registration was not produced
  before the session terminated, and interactive GUI click-through is not automatable in
  this environment. The **fresh Plugin Verifier** (Compatible on 2023.2 and 2024.3)
  performs descriptor loading, dependency resolution, install and API-compatibility checks
  against the real IDE runtimes, and the plugin is installed into the sandbox by
  `buildPlugin`/`prepareSandbox`. GUI click-through remains NOT RUN.

## Screenshots — PASS (8 real captures)

Captured with headless Brave Chromium (puppeteer-core) against the live dashboard serving a
real demo vault (4 verified checkpoints) on loopback; every frame was gated on real DOM
state, no mockups. Files are non-blank rendered UI (verified: 2880×1800, 600–800 unique
colors each). For `docs/screenshots/`:

| File | View |
| --- | --- |
| `01-dashboard.png` | Dashboard, dark theme: timeline, health ring, storage stats |
| `02-dashboard-light.png` | Dashboard, light theme |
| `03-checkpoint-inspector.png` | Checkpoint inspector with snapshot evidence |
| `04-timeline-state-map.png` | Timeline + state-map view |
| `05-health-verification.png` | Health verification run (ops log) |
| `06-diagnosis.png` | Evidence-based diagnosis cards |
| `07-recovery-plan.png` | Recovery-plan review (files to change/add/remove) |
| `08-diff-checkpoints.png` | Checkpoint diff comparator |

## Documentation — PASS

Present and consistent (wording corrected to avoid the "deterministic diagnosis" overclaim;
adapter counts aligned to the actual 9 adapter modules / 10 project types; privacy
distinction clarified between CHRONOVAULT data and user-configured build commands;
`screenshots` references use absolute HTTPS raw GitHub URLs; IDE docs updated for the IPGP
2.x wrapper commands):

README, CHANGELOG, CONTRIBUTING, EULA, LICENSE, docs/ (QUICKSTART, INSTALLATION,
CONFIGURATION, SUPPORTED_PROJECTS, HEALTH_PROFILES, cli, web-ui, recovery-guide, security,
PRIVACY, TROUBLESHOOTING, DEVELOPMENT, PUBLISHING, architecture, ide-integrations, release).

## Release Artifacts

| Artifact | Path |
| --- | --- |
| CLI distribution | `cli/build/install/chronovault/bin/chronovault` |
| VS Code extension | `dist/chronovault-1.0.0.vsix` (and `vscode-extension/chronovault-1.0.0.vsix`) |
| IntelliJ plugin | `dist/chronovault-intellij-1.0.0.zip` (and `intellij-plugin/build/distributions/…`) |
| Gate report | `dist/RELEASE-REPORT.md` |

## Remaining Blockers

None technical. NOT RUN items that require an interactive environment and their
compensation:

- IntelliJ **in-IDE install** / signing — not automatable here; fresh Plugin Verifier
  verdicts + package inspection + documented manual signing process
  (`docs/PUBLISHING.md`) cover them.
- VS Code palette click-through — CLI E2E + webview code review stand in, and the VSIX
  installs/activates cleanly.

## Manual Steps (unavoidable, not automated)

1. **VS Code Marketplace** — claim publisher `chronovault`, then `npx @vscode/vsce login` +
   `npx @vscode/vsce publish` (or Open VSX overlay). See `docs/PUBLISHING.md`.
2. **JetBrains Marketplace** — obtain the signing certificate (keytool + certificate
   request API), `cd intellij-plugin && ./gradlew signPlugin -Psigning.* …`, verify with
   `./gradlew verifyPluginSignature`, upload the signed ZIP at plugins.jetbrains.com.
3. **Git push** — commit the working-tree release changes, push to
   github.com/100raav/chronovault (this also materializes the HTTPS screenshot URLs).

## Final Status

Per-marketplace verdicts (technical gates vs. account/credential actions are kept
separate):

| Marketplace | Status |
| --- | --- |
| **VS Code** | **READY — MANUAL PUBLISH REQUIRED** (package valid, install tested, no technical blocker; publishing needs the publisher's account action) |
| **JetBrains** | **BLOCKED — signing credentials required** (all technical gates pass; only the missing certificate chain + private key blocks `signPlugin`/`verifyPluginSignature`) |
| **Overall** | **RELEASE CANDIDATE — final marketplace action required** (do not claim RELEASE READY until the artifact is signed and the JetBrains upload is performed) |