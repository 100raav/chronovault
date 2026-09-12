"use strict";

const vscode = require("vscode");
const { execFile, spawn } = require("child_process");

const CLI = "chronovault";
const DEFAULT_TIMEOUT_MS = 30 * 1000;
const RECOVERY_TIMEOUT_MS = 5 * 60 * 1000;

function getCliPath() {
  const cfg = vscode.workspace.getConfiguration("chronovault");
  return cfg.get("cliPath", CLI);
}

function activeProjectRoot() {
  const folder = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
  return folder ? folder.uri.fsPath : undefined;
}

function cliEnv(projectRoot) {
  const env = Object.assign({}, process.env);
  if (projectRoot) env.CHRONOVAULT_PROJECT = projectRoot;
  return env;
}

function friendlyError(error, stderr) {
  if (error && error.code === "ENOENT") {
    return "The 'chronovault' CLI was not found. Install it or set 'chronovault.cliPath'.";
  }
  const body = (stderr || "").trim();
  return body || (error ? error.message : "UNKNOWN_ERROR");
}

function runCli(args, projectRoot, timeoutMs) {
  const cli = getCliPath();
  return new Promise((resolve, reject) => {
    const env = cliEnv(projectRoot);
    const before = args[0];
    const t = before === "restore" ? RECOVERY_TIMEOUT_MS : DEFAULT_TIMEOUT_MS;
    execFile(cli, args, {
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
      if (error) reject(new Error(friendlyError(error, stderr)));
      else resolve((stdout || "").trim());
    });
  });
}

async function run(command, args, options) {
  const root = (options && options.projectRoot) || activeProjectRoot();
  if (!root) {
    throw new Error("Open a project folder first.");
  }
  try {
    return await runCli([command].concat(args || []), root, options && options.timeoutMs);
  } catch (e) {
    vscode.window.showErrorMessage("CHRONOVAULT: " + e.message);
    throw e;
  }
}

/** Launches the dashboard without blocking activation or leaking a hung process. */
function openDashboard() {
  const cli = getCliPath();
  const root = activeProjectRoot();
  if (!root) {
    vscode.window.showErrorMessage("CHRONOVAULT: Open a project folder first.");
    return;
  }
  const child = spawn(cli, ["ui", "--open"], {
    cwd: root,
    env: cliEnv(root),
    detached: true,
    stdio: "ignore"
  });
  child.unref();
  child.once("error", err => {
    vscode.window.showErrorMessage("CHRONOVAULT: " + friendlyError(err));
  });
  vscode.window.showInformationMessage("ChronoVault dashboard launched.");
}

function activate(context) {
  let statusItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  statusItem.command = "chronovault.status";
  statusItem.text = "$(shield) ChronoVault";
  statusItem.tooltip = "Click to check protection status";
  statusItem.show();
  statusItem.text = "$(loading~spin) ChronoVault …";

  refreshStatus(statusItem);

  context.subscriptions.push(
    vscode.commands.registerCommand("chronovault.checkpoint", async () => {
      const label = await vscode.window.showInputBox({ prompt: "Checkpoint label (optional)" });
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: verifying + checkpointing…" },
        () => run("checkpoint", label ? ["--label", label] : [], { timeoutMs: RECOVERY_TIMEOUT_MS })
          .then(() => vscode.window.showInformationMessage("ChronoVault checkpoint complete.")))
        .catch(err => vscode.window.showErrorMessage("CHRONOVAULT: " + err.message));
      refreshStatus(statusItem);
    }),

    vscode.commands.registerCommand("chronovault.restore", async () => {
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
        .catch(err => vscode.window.showErrorMessage("CHRONOVAULT: " + err.message));
      refreshStatus(statusItem);
    }),

    vscode.commands.registerCommand("chronovault.diagnose", async () => {
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
        .catch(err => vscode.window.showErrorMessage("CHRONOVAULT: " + err.message));
    }),

    vscode.commands.registerCommand("chronovault.status", async () => {
      await run("state").then(output => {
        const summary = stripAnsi(output).split("\n").filter(l => /Status|Last verified|Checkpoints|Recoveries|Storage/.test(l)).join("\n");
        vscode.window.showInformationMessage(summary || stripAnsi(output));
        refreshStatus(statusItem);
      }).catch(err => vscode.window.showErrorMessage("CHRONOVAULT: " + err.message));
    }),

    vscode.commands.registerCommand("chronovault.dashboard", () => openDashboard())
  );
}

function refreshStatus(item) {
  Promise.resolve().then(() => {
    try {
      item.text = "$(shield) ChronoVault";
      item.show();
    } catch (e) { /* status bar best effort */ }
  });
}

function stripAnsi(s) {
  return s.replace(/\u001b\[[0-9;]*m/g, "");
}

function deactivate() {}

module.exports = { activate, deactivate };