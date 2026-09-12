"use strict";

const fs = require("fs");
const path = require("path");

const DEFAULT_NAME = "chronovault";

function platformOf(platform) {
  return platform || process.platform;
}

function exeVariants(name, platform) {
  if (platformOf(platform) === "win32") {
    return [name + ".exe", name + ".cmd", name + ".bat", name];
  }
  return [name];
}

function isExecutable(file, platform) {
  if (!file) return { ok: false, reason: "no-file" };
  try {
    const stat = fs.statSync(file);
    if (!stat.isFile()) return { ok: false, reason: "not-file" };
    if (platformOf(platform) === "win32") return { ok: true, reason: "file" };
    fs.accessSync(file, fs.constants.X_OK);
    return { ok: true, reason: "executable" };
  } catch (e) {
    return { ok: false, reason: String(e.code || e.message) };
  }
}

function searchPath(name, opts) {
  const platform = platformOf(opts && opts.platform);
  const env = (opts && opts.env) || process.env;
  const dirs = String(env.PATH || "").split(path.delimiter).filter(Boolean);
  for (const dir of dirs) {
    for (const variant of exeVariants(name, platform)) {
      const candidate = path.join(dir, variant);
      if (isExecutable(candidate, platform).ok) return candidate;
    }
  }
  return null;
}

function safeLocations(name, opts) {
  const platform = platformOf(opts && opts.platform);
  const env = (opts && opts.env) || process.env;
  const dirs = [];
  const push = (dir) => {
    if (dir) dirs.push(dir);
  };

  for (const home of [env.USERPROFILE, env.HOME]) {
    if (!home) continue;
    push(path.join(home, ".chronovault", "bin"));
    push(path.join(home, ".local", "bin"));
    if (platform !== "win32") push(path.join(home, "bin"));
  }
  if (platform === "win32") {
    push(env.LOCALAPPDATA && path.join(env.LOCALAPPDATA, "chronovault", "bin"));
    push(env.ProgramFiles && path.join(env.ProgramFiles, "Chronovault", "bin"));
  } else {
    push("/usr/local/bin");
    if (platform === "darwin") push("/opt/homebrew/bin");
    push("/opt/bin");
  }

  const seen = new Set();
  for (const dir of dirs) {
    for (const variant of exeVariants(name, platform)) {
      const candidate = path.join(dir, variant);
      if (seen.has(candidate)) continue;
      seen.add(candidate);
      const check = isExecutable(candidate, platform);
      if (check.ok) return { file: candidate, source: dir };
    }
  }
  return null;
}

function lookup(name, opts) {
  if (!name) return null;
  for (const folder of (opts && opts.folders) || []) {
    if (!folder) continue;
    for (const variant of exeVariants(name, platformOf(opts && opts.platform))) {
      const candidate = path.join(folder, variant);
      if (isExecutable(candidate, opts && opts.platform).ok) {
        return { file: candidate, source: "folder" };
      }
    }
  }
  return null;
}

/**
 * Resolve the chronovault CLI in priority order:
 *   1. explicitly configured path (cliPath / CHRONOVAULT_CLI) when valid
 *   2. bundled runtime next to the extension (if present)
 *   3. the operating system PATH
 *   4. safe, platform-specific, user-scoped locations
 * Never hard-codes a developer home directory; always derives from env.
 */
function resolveCli(opts) {
  const configuredRaw = (opts && opts.configuredPath) || "";
  const configured = configuredRaw.trim();
  const name = configured && configured !== DEFAULT_NAME ? configured : DEFAULT_NAME;
  const platform = platformOf(opts && opts.platform);
  const env = (opts && opts.env) || process.env;

  if (configured && configured !== DEFAULT_NAME) {
    const direct = isExecutable(configured, platform);
    if (direct.ok) return { cli: configured, source: "configured", detail: configured };
    const onPath = searchPath(name, { env: env, platform: platform });
    if (onPath) return { cli: onPath, source: "configured-path", detail: onPath };
  }

  const bundled = lookup(DEFAULT_NAME, {
    folders: [opts && opts.bundledDir],
    platform: platform
  });
  if (bundled && bundled.file) {
    return { cli: bundled.file, source: "bundled", detail: bundled.file };
  }

  const found = searchPath(DEFAULT_NAME, { env: env, platform: platform });
  if (found) return { cli: found, source: "path", detail: found };

  const located = safeLocations(DEFAULT_NAME, { env: env, platform: platform });
  if (located) return { cli: located.file, source: "location", detail: located.file + " (" + located.source + ")" };

  return { cli: null, source: "missing", detail: configured || DEFAULT_NAME };
}

module.exports = {
  DEFAULT_NAME: DEFAULT_NAME,
  resolveCli: resolveCli,
  isExecutable: isExecutable,
  searchPath: searchPath,
  safeLocations: safeLocations,
  exeVariants: exeVariants,
  platformOf: platformOf
};