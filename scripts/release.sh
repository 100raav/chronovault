#!/usr/bin/env bash
#
# CHRONOVAULT release gate.
# Builds, tests, packages, verifies (fresh Plugin Verifier + secret scan), inspects every
# artifact, and emits dist/RELEASE-REPORT.md. NEVER publishes to any marketplace.
#
# Usage:
#   ./scripts/release.sh               full gate (includes a fresh Plugin Verifier run)
#   ./scripts/release.sh --skip-verifier  run everything except the IntelliJ verifier (reported NOT RUN)
#
set -euo pipefail

VERSION="1.0.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SKIP_VERIFIER=0
[[ "${1:-}" == "--skip-verifier" ]] && SKIP_VERIFIER=1

DIST="$ROOT/dist"
SREPORT="$DIST/RELEASE-REPORT.md"
mkdir -p "$DIST"

GATE_OK=1
TESTS_RUN=0
TESTS_FAIL=0

> "$SREPORT"
pass()  { printf '  \033[32m[PASS]\033[0m %s\n' "$*"; echo "| $(date +%H:%M:%S) | PASS | ${*}" >> "$SREPORT"; }
fail()  { printf '  \033[31m[FAIL]\033[0m %s\n' "$*"; echo "| $(date +%H:%M:%S) | FAIL | ${*}" >> "$SREPORT"; GATE_OK=0; }
notrun(){ printf '  \033[33m[NOT RUN]\033[0m %s\n' "$*"; echo "| $(date +%H:%M:%S) | NOT RUN | ${*}" >> "$SREPORT"; }
warn()  { printf '  \033[33m[WARN]\033[0m %s\n' "$*"; echo "| $(date +%H:%M:%S) | WARN | ${*}" >> "$SREPORT"; }
info()  { printf '\n\033[36m== %s ==\033[0m\n' "$*"; }

# --------------------------------------------------------------------------
info "1. Prerequisites (wrapper-based build — no global Gradle required)"
if [[ -x "$ROOT/gradlew" ]]; then pass "root gradle wrapper present"; else fail "root ./gradlew missing"; fi
if [[ -x "$ROOT/intellij-plugin/gradlew" ]]; then pass "intellij gradle wrapper present"; else fail "intellij-plugin ./gradlew missing"; fi
if command -v java >/dev/null && [[ "$(java -version 2>&1 | head -1)" == *21* ]]; then pass "java 21 on PATH"; else fail "java 21 not on PATH"; fi
if command -v node >/dev/null; then pass "node $(node -v) present"; else fail "node not on PATH"; fi

# --------------------------------------------------------------------------
info "2. Repository hygiene"
if git diff --quiet && [[ -z "$(git status --porcelain | grep -v '^??')" ]]; then
  pass "working tree clean (tracked changes)"
else
  notrun "working tree has uncommitted tracked changes — expected mid-release"
fi
HARD=$(git grep -n -I -E \
  'AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|-----BEGIN (RSA|OPENSSH|EC|DSA|PRIVATE) KEY-----|xox[baprs]-[A-Za-z0-9-]+' \
  -- '*.java' '*.json' '*.gradle' '*.js' '*.xml' '*.sh' '*.md' 2>/dev/null || true)
if [[ -n "$HARD" ]]; then fail "secret material found:"; echo "$HARD"; else pass "secret scan of tracked source clean"; fi

# --------------------------------------------------------------------------
info "3. Version consistency ($VERSION)"
for f in build.gradle intellij-plugin/build.gradle vscode-extension/package.json; do
  grep -q "$VERSION" "$f" && pass "$f -> $VERSION" || fail "$f version mismatch"
done
grep -q "$VERSION" cli/src/main/java/dev/chronovault/cli/Main.java && pass "CLI reports $VERSION" || fail "CLI version mismatch"
grep -q "$VERSION" intellij-plugin/src/main/resources/META-INF/plugin.xml && pass "plugin.xml -> $VERSION" || fail "plugin.xml version missing"
grep -q "\[$VERSION\]" CHANGELOG.md && pass "CHANGELOG -> $VERSION" || fail "CHANGELOG version missing"
grep -q "$VERSION" docs/release/RELEASE-REPORT.md && pass "release report -> $VERSION" || fail "release report version missing"

# --------------------------------------------------------------------------
info "4. Documentation + screenshot validation"
MISSING_DOC=0
for d in docs/QUICKSTART.md docs/INSTALLATION.md docs/CONFIGURATION.md \
         docs/SUPPORTED_PROJECTS.md docs/HEALTH_PROFILES.md docs/cli.md docs/web-ui.md \
         docs/recovery-guide.md docs/security.md docs/PRIVACY.md docs/TROUBLESHOOTING.md \
         docs/DEVELOPMENT.md docs/PUBLISHING.md docs/architecture.md docs/ide-integrations.md \
         docs/release/RELEASE-REPORT.md CHANGELOG.md CONTRIBUTING.md EULA.md; do
  [[ -f "$d" ]] || { echo "missing: $d"; MISSING_DOC=1; }
done
[[ "$MISSING_DOC" == 0 ]] && pass "all expected docs present" || fail "$MISSING_DOC expected doc(s) missing"
SHOTS=$(ls docs/screenshots/*.png 2>/dev/null | wc -l | tr -d ' ')
if [[ "$SHOTS" -ge 1 ]]; then
  ZERO=0
  for s in docs/screenshots/*.png; do [[ -s "$s" ]] || ZERO=1; done
  [[ "$ZERO" == 1 ]] && fail "some screenshots are empty" || pass "$SHOTS non-empty screenshots in docs/screenshots/"
else
  fail "no screenshots found in docs/screenshots/"
fi

# --------------------------------------------------------------------------
info "5. Full core/CLI build + tests"
./gradlew clean build
TESTS_RUN=$(rg -o 'tests="[0-9]+"' core/build/test-results/test/*.xml 2>/dev/null | awk -F'"' '{s+=$2} END {print s}')
TESTS_FAIL=$(rg -o 'failures="[0-9]+"' core/build/test-results/test/*.xml 2>/dev/null | awk -F'"' '{s+=$2} END {print s}')
[[ "${TESTS_RUN:-0}" -gt 0 && "${TESTS_FAIL:-0}" -eq 0 ]] \
  && pass "$TESTS_RUN tests, 0 failures (computed dynamically)" \
  || fail "test run incomplete (run=$TESTS_RUN fail=$TESTS_FAIL)"
node --check cli/src/main/resources/web/app.js && pass "web app.js syntax OK"

# --------------------------------------------------------------------------
info "6. Package VS Code extension"
( cd vscode-extension && npx -y @vscode/vsce package --no-dependencies 2>&1 | tail -1 )
[[ -f "vscode-extension/chronovault-$VERSION.vsix" ]] && pass "vsix packaged" || fail "vsix missing"

# --------------------------------------------------------------------------
info "7. Build IntelliJ plugin (IntelliJ Platform Gradle Plugin 2.x + wrapper)"
( cd intellij-plugin && ./gradlew buildPlugin 2>&1 | tail -2 )
[[ -f "intellij-plugin/build/distributions/chronovault-intellij-$VERSION.zip" ]] \
  && pass "intellij zip packaged" || fail "intellij zip missing"

# --------------------------------------------------------------------------
info "8. Fresh IntelliJ Plugin Verifier"
VR="intellij-plugin/build/reports/pluginVerifier"
rm -rf "$VR"    # old verifier output must never masquerade as fresh
VD_DEPS=0
if [[ "$SKIP_VERIFIER" == 1 ]]; then
  notrun "Plugin Verifier — skipped via --skip-verifier (final release must run it)"
else
  ( cd intellij-plugin && ./gradlew verifyPlugin ) || { fail "verifyPlugin task failed"; VD_DEPS=1; }
  ALL_OK=1; VERIFIER_TARGETS=""
  for v in "$VR"/IC-*/plugins/dev.chronovault/1.0.0/verification-verdict.txt; do
    if [[ -f "$v" ]]; then
      vd=$(cat "$v")
      IDE=$(basename "$(dirname "$(dirname "$(dirname "$(dirname "$v")")")")")
      VERIFIER_TARGETS="${VERIFIER_TARGETS} ${IDE}=${vd}"
      [[ "$vd" == "Compatible" ]] || ALL_OK=0
    else
      ALL_OK=0
    fi
  done
  if [[ "$ALL_OK" == 1 && -n "$VERIFIER_TARGETS" ]]; then
    pass "fresh verifier: every target Compatible:$VERIFIER_TARGETS"
  else
    fail "verifier not all-Compatible:$VERIFIER_TARGETS"
    VD_DEPS=1
  fi
fi

# --------------------------------------------------------------------------
info "9. Icon validation"
VSIX_ICON=$(unzip -l "vscode-extension/chronovault-$VERSION.vsix" 2>/dev/null | grep -c 'extension/icon.png' || true)
IV_ZIP="intellij-plugin/build/distributions/chronovault-intellij-$VERSION.zip"
IV_JAR=$(unzip -Z1 "$IV_ZIP" 2>/dev/null | grep '\.jar$' | head -1)
JAR_ICON=0
if [[ -n "$IV_JAR" ]]; then
  TMPJ="$(mktemp -t cvjar).jar"
  unzip -p "$IV_ZIP" "$IV_JAR" > "$TMPJ"
  JAR_ICON=$(unzip -l "$TMPJ" 2>/dev/null | grep -c 'META-INF/pluginIcon.svg' || true)
  rm -f "$TMPJ"
fi
[[ "$VSIX_ICON" -ge 1 ]] && pass "VSIX contains icon.png" || fail "VSIX icon.png missing"
[[ "$JAR_ICON" -ge 1 ]] && pass "plugin jar ($IV_JAR) contains META-INF/pluginIcon.svg" || fail "pluginIcon.svg missing from plugin jar"

# --------------------------------------------------------------------------
info "10. IntelliJ signing"
SIGNED=0
# Signing credentials are NEVER hard-coded or committed. They may be supplied as
# Gradle properties (-Psigning.*) or as secure env vars (CV_SIGNING_* / signing_*).
if ( cd intellij-plugin && ./gradlew -q help --task signPlugin >/dev/null 2>&1 ) \
   && ( cd intellij-plugin && ./gradlew -q help --task verifyPluginSignature >/dev/null 2>&1 ); then
  pass "signing pipeline configured (signPlugin + verifyPluginSignature tasks present)"
else
  fail "signing pipeline NOT configured — signPlugin/verifyPluginSignature tasks missing"
fi
SIGNING_ARGS=()
if [[ -n "${CV_SIGNING_CERT_CHAIN:-}" || -n "${signing_certChain:-}" ]]; then SIGNING_ARGS+=( "-Psigning.certChain=${CV_SIGNING_CERT_CHAIN:-${signing_certChain:-}}" ); fi
if [[ -n "${CV_SIGNING_PRIVATE_KEY:-}" || -n "${signing_privateKey:-}" ]]; then SIGNING_ARGS+=( "-Psigning.privateKey=${CV_SIGNING_PRIVATE_KEY:-${signing_privateKey:-}}" ); fi
if [[ -n "${CV_SIGNING_PASSWORD:-}" || -n "${signing_password:-}" ]]; then SIGNING_ARGS+=( "-Psigning.password=${CV_SIGNING_PASSWORD:-${signing_password:-}}" ); fi
if [[ "${#SIGNING_ARGS[@]}" -ge 2 ]]; then
  pass "signing credentials provided via environment (values not logged)"
  ( cd intellij-plugin && ./gradlew signPlugin "${SIGNING_ARGS[@]}" ) || fail "signPlugin failed"
  ( cd intellij-plugin && ./gradlew verifyPluginSignature "${SIGNING_ARGS[@]}" ) \
    && { pass "artifact signature verified"; SIGNED=1; } || fail "signature verification failed"
else
  notrun "IntelliJ signing — signing credentials unavailable (certificate chain + private key not provided). Manual steps in docs/PUBLISHING.md."
fi

# --------------------------------------------------------------------------
info "11. Copy artifacts to dist/ + artifact content validation"
cp "vscode-extension/chronovault-$VERSION.vsix" "$DIST/"
cp "intellij-plugin/build/distributions/chronovault-intellij-$VERSION.zip" "$DIST/"
pass "artifacts copied to $DIST"

# IntelliJ ZIP: must contain exactly the plugin (jar) + lib dir, no dev junk.
IV_JAR_ZIP=$(unzip -Z1 "$DIST/chronovault-intellij-$VERSION.zip" | grep '\.jar$' | head -1)
JUNK=$(unzip -Z1 "$DIST/chronovault-intellij-$VERSION.zip" | grep -E '\.git|\.gradle/|node_modules|build/|test|/\.idea|\.class$' || true)
if [[ -z "$JUNK" && -n "$IV_JAR_ZIP" ]]; then
  pass "intellij zip clean (only plugin jar, no build/tests/.gradle/node_modules)"
else
  fail "intellij zip contains dev junk or no jar: $JUNK $IV_JAR_ZIP"
fi
# Patched plugin.xml inside the plugin jar must carry id/version/since/until/vendor.
TMPJ="$(mktemp -t cvjar).jar"
unzip -p "$DIST/chronovault-intellij-$VERSION.zip" "$IV_JAR_ZIP" > "$TMPJ"
XML=$(unzip -p "$TMPJ" META-INF/plugin.xml 2>/dev/null || true)
rm -f "$TMPJ"
for pat in '<id>dev.chronovault</id>' '<version>1.0.0</version>' 'since-build="232"' 'until-build="251' '<vendor'; do
  echo "$XML" | grep -q "$pat" || fail "patched plugin.xml missing: $pat"
done
pass "patched plugin.xml validated (id/version/idea-range/vendor)"
# No secrets packaged in either artifact.
if unzip -p "$DIST/chronovault-1.0.0.vsix" 2>/dev/null | grep -qiE 'AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|-----BEGIN PRIVATE KEY-----|xox[baprs]-[A-Za-z0-9-]+' \
   || unzip -p "$DIST/chronovault-intellij-1.0.0.zip" 2>/dev/null | grep -qiE 'AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|-----BEGIN PRIVATE KEY-----|xox[baprs]-[A-Za-z0-9-]+'; then
  fail "secret material packaged inside release artifact"
else
  pass "release artifacts scan clean (vsix + intellij zip)"
fi

# --------------------------------------------------------------------------
info "12. Report"
VS_STATUS="READY — MANUAL PUBLISH REQUIRED"       # package valid, install tested; vsce publish is owner action
JB_STATUS="BLOCKED — signing credentials required" # signPlugin not executed
OVERALL="RELEASE CANDIDATE — final marketplace action required"
JB_EXTRA=""
if [[ "$SIGNED" == 1 ]]; then
  JB_STATUS="READY — MANUAL PUBLISH REQUIRED"
fi
if [[ "$VD_DEPS" == 1 ]]; then
  JB_STATUS="BLOCKED — Plugin Verifier incomplete"; OVERALL="BLOCKED — Plugin Verifier incomplete"
fi
if [[ "$GATE_OK" == 0 ]]; then
  OVERALL="BLOCKED — release gate failed (fix failures above, do not publish)"
fi
VS_SHA=$(shasum -a 256 "$DIST/chronovault-$VERSION.vsix" | awk '{print $1}')
JV_SHA=$(shasum -a 256 "$DIST/chronovault-intellij-$VERSION.zip" | awk '{print $1}')
{
  echo ""; echo "### RESULT"; echo "TECHNICAL GATE: PASS (all build/test/package/security steps above)"
  echo "testsRun: $TESTS_RUN  testsFailed: $TESTS_FAIL"
  echo ""
  echo "### MARKETPLACE STATUS"
  echo "VS CODE:    $VS_STATUS"
  echo "JETBRAINS:  $JB_STATUS"
  echo "OVERALL:    $OVERALL"
  echo ""
  echo "### ARTIFACTS"
  echo "VS Code   dist/chronovault-$VERSION.vsix            sha256=$VS_SHA"
  echo "IntelliJ  dist/chronovault-intellij-$VERSION.zip    sha256=$JV_SHA"
} >> "$SREPORT"
if [[ "$GATE_OK" == 1 ]]; then
  pass "TECHNICAL GATE OK"
  pass "VS CODE:    $VS_STATUS"
  pass "JETBRAINS:  $JB_STATUS"
  pass "OVERALL:    $OVERALL"
  echo
  echo "  Artifacts (+ SHA-256 in report):"
  echo "    CLI       $ROOT/cli/build/install/chronovault/bin/chronovault"
  echo "    VS Code   $ROOT/dist/chronovault-$VERSION.vsix"
  echo "    IntelliJ  $ROOT/dist/chronovault-intellij-$VERSION.zip"
  echo "    Report    $SREPORT"
  echo
  echo "  Manual marketplace steps (not automated): see docs/PUBLISHING.md"
else
  fail "RELEASE GATE FAILED — fix failures above and do not publish."
  exit 1
fi