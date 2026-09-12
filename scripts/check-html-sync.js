#!/usr/bin/env node
"use strict";
/*
 * Verify that the VS Code webview bundle is in sync with the canonical
 * CLI dashboard bundle. Exit 0 when in sync, 1 with a message otherwise.
 * Run:  node scripts/check-html-sync.js   (wired into `npm run lint`)
 */
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const SRC = path.join(ROOT, "cli/src/main/resources/web");
const DST = path.join(ROOT, "vscode-extension/webview");

function fail(msg) {
  process.stderr.write("check-html-sync: " + msg + "\n");
  process.exit(1);
}

const identical = ["app.js", "styles.css", "icon.svg"];
for (const f of identical) {
  const a = fs.readFileSync(path.join(SRC, f), "utf-8");
  const b = fs.readFileSync(path.join(DST, f), "utf-8");
  if (a !== b) fail(f + " differs from the canonical dashboard bundle — run scripts/sync-dashboard.sh");
}

const canonical = fs.readFileSync(path.join(SRC, "index.html"), "utf-8");
const webview = fs.readFileSync(path.join(DST, "index.html"), "utf-8");

// Local assets MUST be loaded through asWebviewUri() (displayed as placeholder
// tokens in the bundle; dashboardPanel.js resolves them at load time).
for (const [token, label] of [
  ["__CV_BRIDGE_URI__", "bridge script"],
  ["__CV_APP_URI__", "app script"],
  ["__CV_STYLES_URI__", "stylesheet"],
  ["__CV_ICON_URI__", "icon"],
]) {
  if (!webview.includes(token)) fail("webview/index.html missing " + token + " (" + label + ")");
}
if (!/src="__CV_BRIDGE_URI__" nonce="__CV_NONCE__"/.test(webview)) {
  fail("webview/index.html must carry the nonce on the bridge script tag");
}
if (!/src="__CV_APP_URI__" nonce="__CV_NONCE__"/.test(webview)) {
  fail("webview/index.html must carry the nonce on the app script tag");
}
if (webview.includes("/static/")) fail("webview/index.html must not reference /static/ paths");
if (/src="\.\/app\.js"|src="\.\/bridge\.js"|href="\.\/styles\.css"/.test(webview)) {
  fail("webview/index.html must reference assets via __CV_*_URI__ placeholders, not relative paths");
}
if (!/default-src 'none'/.test(webview)) fail("webview/index.html CSP must use default-src 'none'");
if (canonical.split('http-equiv="Content-Security-Policy"').length - 1 !== 1) {
  fail("canonical index.html must keep only one CSP meta");
}

process.stdout.write("check-html-sync: webview bundle in sync with canonical dashboard\n");