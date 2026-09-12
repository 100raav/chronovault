#!/usr/bin/env bash
#
# Sync the shared CHRONOVAULT dashboard into the VS Code webview bundle.
# The dashboard implementation lives ONCE in cli/src/main/resources/web
# (served by `chronovault ui`); this copies app.js/styles.css into the VS Code
# extension so the embedded webview uses the exact same UI (plus a thin
# postMessage API bridge) — no duplicated dashboard logic.
#
# Run:   ./scripts/sync-dashboard.sh
# Check: run it, then `git diff --exit-code vscode-extension/webview/` to verify
#        the committed bundle is in sync.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/cli/src/main/resources/web"
DST="$ROOT/vscode-extension/webview"
mkdir -p "$DST"

cp "$SRC/app.js"     "$DST/app.js"
cp "$SRC/styles.css" "$DST/styles.css"
cp "$SRC/icon.svg"   "$DST/icon.svg"

# Generate the webview index.html from the canonical dashboard index:
#  - same markup (single source of truth)
#  - CSP variants with a per-load nonce placeholder for the bundled scripts
#  - asset references are __CV_*_URI__ placeholders that dashboardPanel.js
#    resolves at load time via webview.asWebviewUri() (no relative paths, no
#    assumptions about the webview origin)
#  - a bridge script injected before app.js (fetch/EventSource -> postMessage)
python3 - <<'PY'
import re
src = open("cli/src/main/resources/web/index.html", encoding="utf-8").read()
src = re.sub(r'<link rel="icon"[^>]*/>', '<link rel="icon" type="image/svg+xml" href="__CV_ICON_URI__" />', src)
src = src.replace('href="/static/styles.css"', 'href="__CV_STYLES_URI__"')
src = src.replace('src="/static/app.js"', 'src="__CV_BRIDGE_URI__" nonce="__CV_NONCE__"')
src = re.sub(
    r'<meta http-equiv="Content-Security-Policy" content="[^"]*" />',
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'nonce-__CV_NONCE__\'; '
    'style-src \'self\' \'unsafe-inline\'; img-src \'self\' data:; font-src \'self\'; '
    'connect-src \'none\'; object-src \'none\'; frame-src \'none\'; base-uri \'none\'; form-action \'none\'" />',
    src)
src = src.replace('</script>\n</body>', '</script>\n<script src="__CV_APP_URI__" nonce="__CV_NONCE__"></script>\n</body>')
open("vscode-extension/webview/index.html", "w", encoding="utf-8").write(src)
print("webview/index.html generated from canonical dashboard index")
PY

echo "dashboard synced -> $DST"