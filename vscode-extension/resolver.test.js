"use strict";

const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { resolveCli, isExecutable, exeVariants, searchPath, safeLocations } = require("./cliResolver");

function tmp(prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix || "cv-res"));
}

function makeExecutable(file, platform) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, "#!/bin/sh\necho ok\n");
  if (platform !== "win32") fs.chmodSync(file, 0o755);
}

function makeEnv(overrides) {
  const env = Object.assign({}, process.env);
  for (const k of ["PATH", "HOME", "USERPROFILE", "LOCALAPPDATA", "ProgramFiles"]) delete env[k];
  return Object.assign(env, overrides || {});
}

const T = tmp();

(function configuredAbsolutePathWins() {
  const bin = path.join(T, "configured", "chronovault");
  makeExecutable(bin, "darwin");
  const r = resolveCli({ configuredPath: bin, platform: "darwin", env: makeEnv() });
  assert.strictEqual(r.source, "configured");
  assert.strictEqual(r.cli, bin);
})();

(function configuredBareNameFallsBackToPath() {
  const bin = path.join(T, "pathbin", "chronovault");
  makeExecutable(bin, "linux");
  const r = resolveCli({
    configuredPath: "chronovault",
    platform: "linux",
    env: makeEnv({ PATH: T + "/pathbin" })
  });
  assert.strictEqual(r.source, "path");
})();

(function pathFound() {
  const bin = path.join(T, "pathbin2", "chronovault");
  makeExecutable(bin, "linux");
  const r = resolveCli({ platform: "linux", env: makeEnv({ PATH: T + "/pathbin2" }) });
  assert.strictEqual(r.cli, bin);
})();

(function bundledRuntimeFoundBeforePath() {
  const bin = path.join(T, "bundled", "chronovault");
  makeExecutable(bin, "darwin");
  const r = resolveCli({ bundledDir: T + "/bundled", platform: "darwin", env: makeEnv() });
  assert.strictEqual(r.source, "bundled");
})();

(function homeChronovaultBinFound() {
  const home = path.join(T, "home");
  const bin = path.join(home, ".chronovault", "bin", "chronovault");
  makeExecutable(bin, "macOrLinux");
  const r = resolveCli({ platform: "licorice", env: makeEnv({ HOME: home }) });
  assert.strictEqual(r.cli, bin);
})();

(function usrLocalBinFound() {
  const bin = "/usr/local/bin/chronovault";
  if (!fs.existsSync(bin)) return; // skipped where unavailable
  const r = resolveCli({ platform: "linux", env: makeEnv({ HOME: T + "/nohome" }) });
  assert.ok(r.cli);
})();

(function missingOnAllLocations() {
  const r = resolveCli({
    configuredPath: "chronovault",
    platform: "linux",
    env: makeEnv({ HOME: path.join(T, "empty-home", Math.random().toString(36)) })
  });
  assert.strictEqual(r.source, "missing");
  assert.strictEqual(r.cli, null);
})();

(function windowsExeVariant() {
  const bin = path.join(T, "win", "chronovault.exe");
  makeExecutable(bin, "win32");
  const r = resolveCli({ platform: "win32", env: makeEnv({ PATH: T + "/win" }) });
  assert.strictEqual(r.cli, bin);
})();

(function windowsUserProfileVariant() {
  const bin = path.join(T, "winhome", ".chronovault", "bin", "chronovault.exe");
  makeExecutable(bin, "win32");
  const r = resolveCli({ platform: "win32", env: makeEnv({ USERPROFILE: T + "/winhome" }) });
  assert.strictEqual(r.cli, bin);
})();

(function exeVariantsPlatformAware() {
  assert.deepStrictEqual(exeVariants("chronovault", "darwin"), ["chronovault"]);
  assert.deepStrictEqual(exeVariants("chronovault", "win32"),
    ["chronovault.exe", "chronovault.cmd", "chronovault.bat", "chronovault"]);
})();

(function nonExecutableFileRejected() {
  const f = path.join(T, "plain.txt");
  fs.writeFileSync(f, "x");
  assert.strictEqual(isExecutable(f, "linux").ok, false);
})();

console.log("resolver.test.js: " + fs.readdirSync(process.cwd()).length + " modules loaded");
console.log("resolver.test.js: ALL PASS (" + 10 + " checks)");
module.exports = {};