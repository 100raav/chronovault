"use strict";

const fs = require("fs");
const path = require("path");
const vscode = require("vscode");
const { execFile, spawn } = require("child_process");
const resolver = require("./cliResolver");

const DEFAULT_TIMEOUT_MS = 30 * 1000;
const RECOVERY_TIMEOUT_MS = 5 * 60 * 1000;
const RUNTIME_WARNING_COOLDOWN_MS = 10 * 60 * 1000;

let activeRuntime = null;
let lastRuntimeWarningAt = -Infinity;

function resolveRuntime() {
  if (activeRuntime) return activeRuntime;
  const cfg = vscode.workspace.getConfiguration("chronovault");
  const configured = cfg.get("cliPath", resolver.DEFAULT_NAME);
  const ext = vscode.extensions.getExtension("chronovault.chronovault");
  activeRuntime = resolver.resolveCli({
    configuredPath: configured,
    bundledDir: ext && ext.extensionPath ? path.join(ext.extensionPath, "bin") : undefined,
    env: process.env,
    platform: process.platform
  });
  return activeRuntime;
}

function invalidateRuntime() {
  activeRuntime = null;
}

function activeProjectRoot() {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) return undefined;
  if (folders.length === 1) return folders[0].uri.fsPath;

  const editor = vscode.window.activeTextEditor;
  if (editor) {
    const folder = vscode.workspace.getWorkspaceFolder(editor.document.uri);
    if (folder) return folder.uri.fsPath;
  }
  for (const folder of folders) {
    try {
      if (fs.existsSync(path.join(folder.uri.fsPath, ".chronovault"))) return folder.uri.fsPath;
    } catch (e) { /* keep scanning */ }
  }
  return folders[0].uri.fsPath;
}

function cliEnv(projectRoot) {
  const env = Object.assign({}, process.env);
  if (projectRoot) env.CHRONOVAULT_PROJECT = projectRoot;
  return env;
}

function friendlyError(error, stderr) {
  const body = (stderr || "").trim();
  return body || (error ? error.message : "UNKNOWN_ERROR");
}

function runCli(args, projectRoot, timeoutMs) {
  const runtime = resolveRuntime();
  if (!runtime.cli) {
    const err = new Error("CHRONOVAULT runtime could not be located.");
    err.runtimeMissing = true;
    return Promise.reject(err);
  }
  return new Promise((resolve, reject) => {
    const env = cliEnv(projectRoot);
    const before = args[0];
    const t = before === "restore" ? RECOVERY_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
    execFile(runtime.cli, args, {
      cwd: projectRoot,
      env,
      maxBuffer: 8 * 1024 * 1024,
      encoding: "utf8",
      timeout: timeoutMs || t,
      killSignal: "SIGTERM"
    }, (error, stdout, stderr) => {
      if (error && error.killed) {
        reject(new Error("CHRONOVAULT operation timed out after " + (timeoutMs || t) / 1000 + "s"));
        return;
      }
      if (error) {
        if (error.code === "ENOENT") {
          invalidateRuntime();
          const missing = new Error("CHRONOVAULT runtime could not be located.");
          missing.runtimeMissing = true;
          reject(missing);
          return;
        }
        reject(new Error(friendlyError(error, stderr)));
      } else {
        resolve((stdout || "").trim());
      }
    });
  });
}

function showRuntimeNeeded() {
  const now = Date.now();
  if (now - lastRuntimeWarningAt < RUNTIME_WARNING_COOLDOWN_MS) return;
  lastRuntimeWarningAt = now;
  vscode.window.showWarningMessage(
    "CHRONOVAULT runtime could not be located.",
    { modal: false },
    "Configure CLI", "Locate Runtime", "Retry"
  ).then(choice => {
    if (choice === "Configure CLI") {
      vscode.window.showInputBox({ prompt: "Path to the chronovault CLI executable", value: "" })
        .then(value => {
          if (!value) return;
          vscode.workspace.getConfiguration("chronovault")
            .update("cliPath", value, vscode.ConfigurationTarget.Global)
            .then(() => invalidateRuntime());
        });
    } else if (choice === "Locate Runtime") {
      vscode.window.showOpenDialog({ canSelectFiles: true, canSelectFolders: false, canSelectMany: false,
        openLabel: "Select chronovault executable" }).then(selected => {
        if (!selected || !selected[0]) return;
        vscode.workspace.getConfiguration("chronovault")
          .update("cliPath", selected[0].fsPath, vscode.ConfigurationTarget.Global)
          .then(() => invalidateRuntime());
      });
    } else if (choice === "Retry") {
      invalidateRuntime();
    }
  });
}

async function run(command, args, options) {
  const root = (options && options.projectRoot) || activeProjectRoot();
  if (!root) {
    vscode.window.showErrorMessage("CHRONOVAULT: Open a project folder first.");
    throw new Error("no-project");
  }
  const candidate = await runCli([command].concat(args || []), root, options && options.timeoutMs).catch(e => {
    if (e && e.runtimeMissing) {
      showRuntimeNeeded();
      throw e;
    }
    vscode.window.showErrorMessage("CHRONOVAULT: " + e.message);
    throw e;
  });
  return candidate;
}

function openDashboard() {
  const runtime = resolveRuntime();
  const root = activeProjectRoot();
  if (!runtime.cli) {
    showRuntimeNeeded();
    return;
  }
  if (!root) {
    vscode.window.showErrorMessage("CHRONOVAULT: Open a project folder first.");
    return;
  }
  const child = spawn(runtime.cli, ["ui", "--open"], {
    cwd: root,
    env: cliEnv(root),
    detached: true,
    stdio: "ignore"
  });
  child.unref();
  child.once("error", err => {
    if (err.code === "ENOENT") {
      invalidateRuntime();
      showRuntimeNeeded();
      return;
    }
    vscode.window.showErrorMessage("CHRONOVAULT: " + err.message);
  });
  vscode.window.showInformationMessage("ChronoVault dashboard launched.");
}

class ChronovaultSidebarProvider {
  constructor() {
    this._onDidChangeTreeData = new vscode.EventEmitter();
    this.onDidChangeTreeData = this._onDidChangeTreeData.event;
  }

  getTreeItem(element) {
    return element;
  }

  getChildren() {
    const runtime = resolveRuntime();
    const runtimeItem = new vscode.TreeItem(runtime && runtime.cli
      ? "Runtime: ready"
      : "Runtime: not located");
    runtimeItem.description = runtime && runtime.cli
      ? shortPath(runtime.source) + " · " + runtime.detail
      : "click to configure";
    runtimeItem.command = { command: "chronovault.checkRuntime", title: "Check runtime", arguments: [] };
    runtimeItem.contextValue = runtime && runtime.cli ? "runtime-ready" : "runtime-missing";

    return [
      actionItem("$(save) Create checkpoint", "verified snapshot", "chronovault.checkpoint"),
      actionItem("$(check) Verify health", "run build/test verification", "chronovault.health"),
      actionItem("$(debug-alt) What broke it?", "diagnose last change", "chronovault.diagnose"),
      actionItem("$(history) Restore last good", "protected recovery", "chronovault.restore"),
      actionItem("$(shield) Protection status", "current vault state", "chronovault.status"),
      actionItem("$(globe) Open dashboard", "web dashboard", "chronovault.dashboard"),
      runtimeItem
    ];
  }

  refresh() {
    this._onDidChangeTreeData.fire();
  }
}

function actionItem(label, description, command) {
  const item = new vscode.TreeItem(label, vscode.TreeItemCollapsibleState.None);
  item.description = description;
  item.command = { command: command, title: label, arguments: [] };
  return item;
}

function shortPath(source) {
  const map = { configured: "configured", "configured-path": "configured", bundled: "bundled",
    path: "on PATH", location: "safe location" };
  return map[source] || source;
}

function activate(context) {
  let statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  statusItem.command = "chronovault.status";
  statusItem.text = "$(shield) ChronoVault";
  statusItem.tooltip = "Click to check protection status";
  statusItem.show();

  const sidebar = new ChronovaultSidebarProvider();
  const treeView = vscode.window.createTreeView("chronovault.sidebar", { treeDataProvider: sidebar });

  const handler = (fn) => {
    return async function wrapped() {
      try {
        await fn();
      } finally {
        sidebar.refresh();
        refreshStatus(statusItem);
      }
    };
  };

  context.subscriptions.push(
    vscode.commands.registerCommand("chronovault.checkRuntime", handler(async () => {
      invalidateRuntime();
      const runtime = resolveRuntime();
      if (runtime.cli) {
        vscode.window.showInformationMessage("CHRONOVAULT runtime ready: " + runtime.detail);
      } else {
        showRuntimeNeeded();
      }
    })),

    vscode.commands.registerCommand("chronovault.checkpoint", handler(async () => {
      const label = await vscode.window.showInputBox({ prompt: "Checkpoint label (optional)" });
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: verifying + checkpointing…" },
        () => run("checkpoint", label ? ["--label", label] : [], { timeoutMs: RECOVERY_TIMEOUT_MS })
          .then(() => vscode.window.showInformationMessage("ChronoVault checkpoint complete.")))
        .catch(err => { if (!err.runtimeMissing) vscode.window.showErrorMessage("CHRONOVAULT: " + err.message); });
    })),

    vscode.commands.registerCommand("chronovault.health", handler(async () => {
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: verifying health…" },
        () => run("health").then(output => {
          const summary = stripAnsi(output).trim() || "(no output)";
          vscode.window.showInformationMessage("CHRONOVAULT: " + summary.split("\n")[0]);
        }))
        .catch(err => { if (!err.runtimeMissing) vscode.window.showErrorMessage("CHRONOVAULT: " + err.message); });
    })),

    vscode.commands.registerCommand("chronovault.restore", handler(async () => {
      const answer = await vscode.window.showWarningMessage(
        "Restore project to the last verified state? Current work will be protected.",
        { modal: true }, "Restore", "Cancel");
      if (answer !== "Restore") return;
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: restoring (verify + rollback protection)…" },
        () => run("restore", ["--yes"], { timeoutMs: RECOVERY_TIMEOUT_MS }).then(output => {
          const ok = /STATE RESTORED/.test(output);
          vscode.window.showInformationMessage(ok
            ? "ChronoVault: state restored and verified."
            : "ChronoVault: recovery did not complete — see output.");
        }))
        .catch(err => { if (!err.runtimeMissing) vscode.window.showErrorMessage("CHRONOVAULT: " + err.message); });
    })),

    vscode.commands.registerCommand("chronovault.diagnose", handler(async () => {
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Window,
        title: "ChronoVault: diagnosing…" },
        () => run("diagnose").then(output => {
          const body = stripAnsi(output)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
          const panel = vscode.window.createWebviewPanel("chronovault.diagnose",
            "ChronoVault — What broke it?", vscode.ViewColumn.Active, { enableScripts: false });
          panel.webview.html = "<!DOCTYPE html><html><head><meta http-equiv='Content-Security-Policy' " +
            "content=\"default-src 'none'; style-src 'unsafe-inline'\"></head>" +
            "<body style='background:#0b0e15;color:#d7e0f2'><pre style='font-family:monospace'>" +
            body + "</pre></body></html>";
        }))
        .catch(err => { if (!err.runtimeMissing) vscode.window.showErrorMessage("CHRONOVAULT: " + err.message); });
    })),

    vscode.commands.registerCommand("chronovault.status", handler(async () => {
      await run("state").then(output => {
        const summary = stripAnsi(output).split("\n")
          .filter(l => /Status|Last verified|Checkpoints|Recoveries|Storage/.test(l)).join("\n");
        vscode.window.showInformationMessage(summary || stripAnsi(output));
      }).catch(err => { if (!err.runtimeMissing) vscode.window.showErrorMessage("CHRONOVAULT: " + err.message); });
    })),

    vscode.commands.registerCommand("chronovault.dashboard", handler(async () => {
      openDashboard();
    })),

    treeView
  );

  context.subscriptions.push(statusItem);
}

function refreshStatus(item) {
  Promise.resolve().then(() => {
    try {
      const runtime = resolveRuntime();
      item.text = "$(shield) ChronoVault";
      item.tooltip = runtime && runtime.cli
        ? "chronovault ready (" + shortPath(runtime.source) + ")"
        : "chronovault runtime not located";
      item.show();
    } catch (e) { /* status bar best effort */ }
  });
}

function stripAnsi(s) {
  return s.replace(/\u001b\[[0-9;]*m/g, "");
}

function deactivate() {}

module.exports = { activate, deactivate };