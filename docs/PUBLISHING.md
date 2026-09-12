# Publishing

CHRONOVAULT is distributed as **three artifacts**: a CLI, a VS Code extension, and an
IntelliJ plugin. The automated pipeline gates everything; **publish steps are manual
and require your own marketplace credentials** — nothing is ever auto-published.

## 1. One-shot gate: `scripts/release.sh`

```bash
./scripts/release.sh                 # full gate, includes a fresh Plugin Verifier run
./scripts/release.sh --skip-verifier # everything except the IntelliJ verifier (NOT RUN)
```

The gate:

- runs the core/CLI test suite and computes the test count **dynamically** from the
  actual test-results XML (never a hard-coded number);
- builds the VSIX and the IntelliJ ZIP with the **IntelliJ Platform Gradle Plugin 2.x**
  build via the project's own Gradle wrappers (no global Gradle required);
- runs a **fresh** IntelliJ Plugin Verifier against the final artifact (stale verifier
  output is deleted before the run, so it can never masquerade as fresh);
- inspects both archives (icon presence, contents);
- reports IntelliJ **signing status** — `NOT RUN` when signing credentials are absent;
- secret-scans the tracked sources, validates documentation/screenshots, and checks
  version consistency across all manifests;
- writes `dist/RELEASE-REPORT.md` and fails the gate on any required failure.

## 2. Artifacts

| Artifact | Path |
| --- | --- |
| CLI distribution | `cli/build/install/chronovault/` |
| VS Code extension | `dist/chronovault-1.0.2.vsix` (stage: `vscode-extension/chronovault-1.0.2.vsix`) |
| IntelliJ plugin | `dist/chronovault-intellij-1.0.2.zip` (stage: `intellij-plugin/build/distributions/...`) |
| Release report | `dist/RELEASE-REPORT.md` and `docs/release/RELEASE-REPORT.md` |

Inspect before publishing:

```bash
unzip -l vscode-extension/chronovault-1.0.2.vsix
unzip -l intellij-plugin/build/distributions/chronovault-intellij-1.0.2.zip
unzip -p intellij-plugin/build/distributions/chronovault-intellij-1.0.2.zip \
  chronovault-intellij/lib/chronovault-intellij-1.0.2.jar META-INF/plugin.xml
```

## 3. VS Code Marketplace (MANUAL)

1. Claim the namespace `chronovault` at
   <https://marketplace.visualstudio.com/manage> (create the publisher "chronovault").
2. Create a PAT with Marketplace scope at <https://dev.azure.com>.
3. Login:
   ```bash
   cd vscode-extension
   npx -y @vscode/vsce login chronovault
   npx -y @vscode/vsce package      # -> chronovault-1.0.2.vsix
   ```
4. [Optional] Open VSX (open-source overlay marketplace):
   ```bash
   npx -y @vscode/vsce publish --packagePath chronovault-1.0.2.vsix
   ```
5. Promote 🖥️ → ✅ workspaces, when verified.

## 4. JetBrains Marketplace (MANUAL)

The IntelliJ build uses the **IntelliJ Platform Gradle Plugin 2.x** (`./gradlew` inside
`intellij-plugin/`). Since/until builds are patched by `patchPluginXml` from
`build.gradle` (`ideaVersion.sinceBuild = 232`, `untilBuild = 251.*`).

1. Register on <https://plugins.jetbrains.com> → **Upload plugin**.
2. **Signed plugins (2024.2+)**: first generate a local certificate/key:
   ```bash
   keytool -genkeypair -alias chronovault -keyalg RSA -keysize 4096 \
     -keystore chronovault.jks -storepass <pass> -validity 3650
   keytool -exportcert -alias chronovault -keystore chronovault.jks -file chronovault.cer
   curl -s -F "certFile=@chronovault.cer" \
     https://plugins.jetbrains.com/api/certificate/generate
   ```
   Publish the returned `.zip` (certificate chain + key) on the Marketplace, download
   the `.pem`/`.key`, then sign and verify the signature:
   ```bash
   cd intellij-plugin
   ./gradlew signPlugin \
     -Psigning.certChain="$(<$HOME/.jb/chronovault-cert.pem)" \
     -Psigning.privateKey="$(<$HOME/.jb/chronovault-key.pem)" \
     -Psigning.password="$CHRONOVAULT_SIGN_PASS"
   ./gradlew verifyPluginSignature
   ```
   until signing, the artifact is **unsigned** — do not call it Marketplace ready.
3. **Verify compatibility** (fresh run against the two supported IDE baselines):
   ```bash
   cd intellij-plugin
   ./gradlew verifyPlugin     # verified against IC 2023.2.5 and IC 2024.3.1
   ```
   Reports land in `intellij-plugin/build/reports/pluginVerifier/<IDE>/`.
4. **Publish** (never committed):
   ```bash
   cd intellij-plugin
   ./gradlew publishPlugin -Pintellij.token="$CHRONOVAULT_JB_TOKEN" -Pintellij.channel=stable
   ```

## 5. Credentials hygiene

Never commit tokens/keys. Use env vars (`CHRONOVAULT_VSCE_TOKEN`,
`CHRONOVAULT_JB_TOKEN`, `CHRONOVAULT_SIGN_PASS`) or `-P` on the command line. The
repository is public — keys and identities are **irrevocable cost** once leaked.

## 6. Checklist before release

- [ ] `./scripts/release.sh` green (secret scan clean, dynamic test count all-pass,
      fresh Plugin Verifier `Compatible` on both baselines)
- [ ] `git status` reviewed; no secrets, no personal paths
- [ ] README test badge matches the dynamically computed suite count
- [ ] VSIX + ZIP inspected (contents listing above; icon present in both)
- [ ] IntelliJ artifact signed and `verifyPluginSignature` green
- [ ] Screenshots present in `docs/screenshots/` and referenced with HTTPS URLs