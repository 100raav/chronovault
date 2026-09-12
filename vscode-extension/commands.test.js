"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const pkg = JSON.parse(fs.readFileSync(path.join(__dirname, "package.json"), "utf-8"));

test("commands are all present and registered for the palette", () => {
  const ids = pkg.contributes.commands.map((c) => c.command);
  for (const id of [
    "chronovault.checkpoint",
    "chronovault.health",
    "chronovault.restore",
    "chronovault.diagnose",
    "chronovault.status",
    "chronovault.dashboard",
    "chronovault.refreshDashboard",
    "chronovault.configureCli",
    "chronovault.locateRuntime",
    "chronovault.retryRuntime",
  ]) {
    assert.ok(ids.includes(id), `command ${id} missing`);
  }
  const palette = pkg.contributes.menus.commandPalette.map((m) => m.command);
  for (const id of ids) {
    assert.ok(palette.includes(id), `command ${id} not exposed in the palette`);
  }
});

test("view/title toolbar anchors on the sidebar, not the removed dashboardView", () => {
  const menu = pkg.contributes.menus["view/title"] || [];
  assert.ok(menu.length > 0, "expected title-bar toolbar entries");
  for (const m of menu) {
    assert.ok(m.when.includes("chronovault.sidebar"), `title command bound to '${m.when}' is not sidebar-only`);
    assert.doesNotMatch(m.when, /dashboardView/, "dashboardView must never be a toolbar host");
  }
  const views = pkg.contributes.views.chronovault.map((v) => v.id);
  assert.ok(views.includes("chronovault.sidebar"), "sidebar view contribution missing");
  assert.ok(!views.includes("chronovault.dashboardView"), "dashboardView webview contribution must be gone");
});

test("the embedded dashboard is command-opened (panel), not view-resolved", () => {
  assert.ok(!(pkg.activationEvents || []).some((e) => e.includes("dashboardView")), "no onView activation for dashboardView");
});