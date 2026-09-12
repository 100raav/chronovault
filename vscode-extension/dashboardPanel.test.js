"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const { DashboardPanel, API_ALLOWLIST, allowed, PANEL_VIEW_TYPE } = require("./dashboardPanel");

test("panel identity constant targets the WebviewPanel view type", () => {
  assert.equal(PANEL_VIEW_TYPE, "chronovault.dashboardPanel");
});

test("allowlist gates the exact production API surface", () => {
  const rows = API_ALLOWLIST.map((r) => r.method + ":" + r.path.source.replace(/\\\//g, "/"));
  assert.ok(rows.some((r) => r.startsWith("GET:") && r.includes("/api/meta")), "missing /api/meta");
  assert.ok(rows.some((r) => r.startsWith("GET:") && r.includes("/api/checkpoint/")), "missing checkpoint detail");

  assert.equal(allowed("GET", "/api/meta"), true);
  assert.equal(allowed("GET", "/api/config"), true);
  assert.equal(allowed("GET", "/api/state"), true);
  assert.equal(allowed("GET", "/api/events"), true);
  assert.equal(allowed("POST", "/api/checkpoint"), true);
  assert.equal(allowed("POST", "/api/health"), true);
  assert.equal(allowed("POST", "/api/recover"), true);

  // reject everything the dashboard must never reach
  assert.equal(allowed("POST", "/api/config"), false, "config is read-only");
  assert.equal(allowed("DELETE", "/api/checkpoints"), false);
  assert.equal(allowed("GET", "/api/rm-rf"), false);
  assert.equal(allowed("GET", "/api/checkpoint/../../etc/passwd"), false);
  assert.equal(allowed("GET", "/"), false);
  assert.equal(allowed("GET", "/api/state/extra"), false, "no path-suffix trick");
  assert.equal(allowed("GET", "/api/checkpoint/%2e%2e%2f"), false);
});

test("DashboardPanel never reveals anything but a real WebviewPanel", () => {
  // The regression: 1.0.2 called view.reveal() on a WebviewView. The fix keeps
  // reveal() exclusively inside open() guarded by panel existence+disposal.
  const src = DashboardPanel.toString();
  assert.match(src, /\.reveal\(/);
  assert.match(src, /this\.panel && !this\.panel\.disposed/, "reveal only when a live panel exists");
  assert.match(src, /createWebviewPanel/, "the panel is created as a WebviewPanel");
});

test("SSE handling closes a prior stream before reopening the same id", () => {
  // bridge re-posts the same id after a reconnect; the panel must never leave a
  // duplicate loopback listener behind.
  const src = DashboardPanel.prototype._handleSse.toString();
  assert.match(src, /_closeStream\(id\)/, "close old stream before opening a new one");
});

test("dashboard html loads local assets through webview.asWebviewUri()", () => {
  // Security requirement: the webview must never guess its own origin with
  // relative ./ paths — every local asset goes through asWebviewUri() with an
  // explicit nonce, so the bundle cannot fetch anything outside the extension.
  const path = require("node:path");
  const panel = new DashboardPanel({ extensionPath: path.join(__dirname) }, {
    cliResolver: null,
    getProjectRoot: () => undefined,
  });
  const web = panel._createPanel().webview;
  const html = panel._dashboardHtml(web);
  assert.match(html, /vscode-webview:\/\/attachment\/app\.js/, "app.js loaded via asWebviewUri");
  assert.match(html, /vscode-webview:\/\/attachment\/bridge\.js/, "bridge.js loaded via asWebviewUri");
  assert.match(html, /vscode-webview:\/\/attachment\/styles\.css/, "styles.css loaded via asWebviewUri");
  assert.match(html, /vscode-webview:\/\/attachment\/icon\.svg/, "icon loaded via asWebviewUri");
  assert.doesNotMatch(html, /src="\.\/bridge\.js"|src="\.\/app\.js"|href="\.\/styles\.css"/,
    "no relative asset paths survive into the panel html");
  assert.match(html, /script-src 'nonce-[A-Za-z0-9+\/=]+'/, "per-load nonce CSP present");
  assert.doesNotMatch(html, /__CV_(NONCE|APP|BRIDGE|STYLES|ICON)_URI__/, "no unresolved placeholders remain");
  panel.dispose();
});

test("the dashboard message surface never reveals raw shell operations", () => {
  const src = DashboardPanel.prototype._onMessage.toString();
  assert.doesNotMatch(src, /child_process|exec\(|spawn\("sh"/, "webview messages map only to the allowlist proxy");
});