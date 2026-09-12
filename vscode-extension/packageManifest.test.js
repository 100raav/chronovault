"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const EXT = __dirname;
const ROOT = path.join(EXT, "..");
const CANONICAL = path.join(ROOT, "cli", "src", "main", "resources", "web");

const pkg = JSON.parse(fs.readFileSync(path.join(EXT, "package.json"), "utf-8"));

test("manifest version is 1.0.2", () => {
  assert.equal(pkg.version, "1.0.2");
});

test("manifest exposes the embedded dashboard view", () => {
  const views = pkg.contributes.views.chronovault;
  const dash = views.find((v) => v.id === "chronovault.dashboardView");
  assert.ok(dash, "dashboardView webview contribution missing");
  assert.equal(dash.type, "webview");
});

test("manifest wires dashboard lifecycle commands", () => {
  const ids = pkg.contributes.commands.map((c) => c.command);
  for (const id of [
    "chronovault.dashboard",
    "chronovault.dashboardBrowser",
    "chronovault.refreshDashboard",
    "chronovault.configureCli",
    "chronovault.locateRuntime",
    "chronovault.retryRuntime",
  ]) {
    assert.ok(ids.includes(id), `command ${id} missing`);
  }
  assert.ok(
    pkg.activationEvents.includes("onView:chronovault.dashboardView"),
    "activation event for the webview view missing"
  );
});

test("webview dashboard is in sync with the canonical CLI dashboard", () => {
  for (const name of ["app.js", "styles.css", "icon.svg"]) {
    const a = fs.readFileSync(path.join(CANONICAL, name), "utf-8");
    const b = fs.readFileSync(path.join(EXT, "webview", name), "utf-8");
    assert.equal(b, a, `webview/${name} drifted from the canonical dashboard — run scripts/sync-dashboard.sh`);
  }
});

test("webview index.html is locked down: CSP none + nonce + bridge", () => {
  const html = fs.readFileSync(path.join(EXT, "webview", "index.html"), "utf-8");
  assert.match(html, /default-src 'none'/);
  assert.match(html, /script-src 'nonce-__CV_NONCE__'/);
  assert.match(html, /connect-src 'none'/);
  assert.match(html, /base-uri 'none'/);
  assert.match(html, /<script src="\.\/bridge\.js" nonce="__CV_NONCE__">/);
  assert.match(html, /<script src="\.\/app\.js" nonce="__CV_NONCE__">/);
  assert.doesNotMatch(html, /\/static\//, "webview must not reference server-absolute assets");
  assert.doesNotMatch(html, /onclick=/i);
  assert.doesNotMatch(html, /javascript:/i);
});

test("webview scripts parse as strict JavaScript (no eval, no network)", () => {
  for (const name of ["bridge.js", "setup.js", "app.js"]) {
    const src = fs.readFileSync(path.join(EXT, "webview", name), "utf-8");
    new Function(src); // eslint-disable-line no-new-func
    assert.doesNotMatch(src, /\beval\s*\(/);
  }
});

test("bridge + server for the webview reference loopback only", () => {
  const bridge = fs.readFileSync(path.join(EXT, "webview", "bridge.js"), "utf-8");
  const server = fs.readFileSync(path.join(EXT, "dashboardServer.js"), "utf-8");
  assert.doesNotMatch(bridge, /http:\/\//, "bridge must not open network connections itself");
  assert.doesNotMatch(server, /0\.0\.0\.0/);
  assert.ok(/127\.0\.0\.1/.test(server));
  assert.ok(!/\.localhost/.test(server));
});

test("extension does not require Node built-ins in the webview bundle", () => {
  const bridge = fs.readFileSync(path.join(EXT, "webview", "bridge.js"), "utf-8");
  for (const builtin of ["require(", "node:fs", "node:http", "child_process"]) {
    assert.doesNotMatch(bridge, new RegExp(builtin.replace(/\(/, "\\(")));
  }
});