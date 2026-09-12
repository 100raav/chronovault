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

test("the dashboard message surface never reveals raw shell operations", () => {
  const src = DashboardPanel.prototype._onMessage.toString();
  assert.doesNotMatch(src, /child_process|exec\(|spawn\("sh"/, "webview messages map only to the allowlist proxy");
});