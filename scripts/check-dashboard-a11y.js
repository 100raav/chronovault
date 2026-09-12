#!/usr/bin/env node
"use strict";
/*
 * Static accessibility gate for the shared dashboard.
 * Not a substitute for a screen-reader audit, but enforces the core invariants
 * that keep the dashboard usable: dialog semantics, labeled controls, keyboard
 * reachable buttons, live regions, reduced-motion support, and no
 * information-carried-by-color-only.
 *
 * Run:  node scripts/check-dashboard-a11y.js   (wired into `npm run lint`)
 */
const fs = require("node:fs");
const path = require("node:path");

const ROOT = path.join(__dirname, "..");
const WEB = path.join(ROOT, "cli/src/main/resources/web");
const html = fs.readFileSync(path.join(WEB, "index.html"), "utf-8");
const css = fs.readFileSync(path.join(WEB, "styles.css"), "utf-8");

const errors = [];
const fail = (msg) => errors.push(msg);

// --- 1. Dialog & live-region semantics -------------------------------------
// Modals use the backdrop -> .modal pattern (role is on the .modal child of
// the id'd backdrop overlay); we assert presence + the labelled-by targets.
const dialogCount = (html.match(/role="dialog" aria-modal="true"/g) || []).length;
if (dialogCount < 4) fail("expected >=4 role=dialog+aria-modal dialogs, found " + dialogCount);
for (const title of ["restoreTitle", "diffTitle", "wizardTitle", "diagModalTitle"]) {
  if (!html.includes(`id="${title}"`)) fail("dialog missing labelled-by target: " + title);
}
if (!/id="cvError"[^>]*role="alertdialog"[^>]*aria-modal="true"/.test(html))
  fail("error screen must be an alertdialog");
if (!/id="toasts"[^>]*aria-live="polite"/.test(html)) fail("toasts must be aria-live");
if (!/id="healthState"[^>]*role="status"[^>]*aria-live="polite"/.test(html))
  fail("health state must be a polite live region");

// --- 2. Every button is reachable and has discernible text ------------------
const buttons = html.match(/<button\b[^>]*>[\s\S]*?<\/button>/g) || [];
if (buttons.length === 0) fail("no buttons found");
for (const b of buttons) {
  const content = b.replace(/<[^>]+>/g, "").replace(/&[a-z]+;/g, "").trim();
  const semantic = /aria-label="[^"]+"/.test(b) || /title="[^"]+"/.test(b);
  const readable = [...content].length >= 3;
  if (!semantic && !readable) {
    fail("button has no discernible label: " + b.slice(0, 90));
  }
}

// --- 3. Inputs must have a label -------------------------------------------
for (const m of html.matchAll(/<input\b[^>]*>/g)) {
  const tag = m[0];
  if (!/aria-label="[^"]+"/.test(tag) && !/id="[^"]+"/.test(tag) && !/placeholder="[^"]+"/.test(tag))
    fail("input without accessible name: " + tag.slice(0, 90));
}

// --- 4. Informative table has real headers ---------------------------------
if (/id="cpTable"/.test(html) && !/<thead>[\s\S]*?<th>/.test(html))
  fail("checkpoint table must declare <th> headers");

// --- 5. Reduced-motion support ---------------------------------------------
if (!/@media\s*\(prefers-reduced-motion\s*:\s*reduce\)/.test(css))
  fail("styles.css must define a prefers-reduced-motion media query");
const reduced = css.match(/@media\s*\(prefers-reduced-motion\s*:\s*reduce\)[\s\S]*?\}/g) || [];
const block = reduced.join(" ");
if (!/(animation\s*:\s*none|animation-duration|animation-iteration-count\s*:\s*1|transition-duration\s*:\s*0)/.test(block))
  fail("reduced-motion block must neutralise animations");
if (!/animation-duration\s*:/.test(block) && !/transition-duration\s*:/.test(block))
  fail("reduced-motion block must shorten durations");

// --- 6. Decorative elements are hidden from AT ------------------------------
for (const selector of ['class="scanlines"', 'id="fx"', 'class="warp"']) {
  if (!new RegExp(selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + '[^>]*aria-hidden="true"').test(html))
    fail("decorative element (" + selector + ") must be aria-hidden");
}

// --- 7. No information by color alone --------------------------------------
if (!/id="healthStateText"/.test(html)) fail("health state needs a text twin for color-free status");
if (!/class="store-k (physical|dedup)"><i><\/i>physical/.test(html) && !/store-k physical/.test(html))
  fail("storage legend must label colors with text");
if (!/id="storeRetention"/.test(html)) fail("retention must be conveyed in text (storeRetention)");

if (errors.length) {
  for (const e of errors) process.stderr.write("a11y: " + e + "\n");
  process.exit(1);
}
process.stdout.write("check-dashboard-a11y: " + buttons.length + " buttons, dialogs/labels/live-regions/reduced-motion OK\n");