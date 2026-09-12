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

const expectScripts = canonical
  .replace(/<script src="\/static\/app\.js"><\/script>/, "")
  + '<script src="./app.js" nonce="__CV_NONCE__"></script>';

if (!/src="\.\/bridge\.js" nonce="__CV_NONCE__"/.test(webview)) {
  fail("webview/index.html missing the nonce bridge script tag");
}
if (!/src="\.\/app\.js" nonce="__CV_NONCE__"/.test(webview)) {
  fail("webview/index.html missing the nonce app script tag");
}
if (webview.includes("/static/")) fail("webview/index.html must not reference /static/ paths");
if (!/default-src 'none'/.test(webview)) fail("webview/index.html CSP must use default-src 'none'");
if (/http-equiv="Content-Security-Policy"/.test(canonical.replace(/<meta http-equiv="Content-Security-Policy"[^>]*\/>/, ""))) {
  fail("canonical index.html must keep only one CSP meta");
}

process.stdout.write("check-html-sync: webview bundle in sync with canonical dashboard\n");