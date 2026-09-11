"use strict";

const vscode = require("vscode");
const { execFile } = require("child_process");

const CLI = "chronovault";

function getCliPath() {
  const cfg = vscode.workspace.getConfiguration("chronovault");
  return cfg.get("cliPath", CLI);
}

function runCli(args, projectRoot, cwd) {
  const cli = getCliPath();
  return new Promise((resolve, reject) => {
    const env = Object.assign({}, process.env);
    if (projectRoot) env.CHRONOVAULT_PROJECT = projectRoot;
    execFile(cli, args, { cwd: cwd || vscode.workspace.rootPath, env, maxBuffer: 1024 * 1024,
      encoding: "utf8" }, (error, stdout, stderr) => {
      const body = (stderr || "").trim();
      if (error) {
        reject(new Error(body || error.message));
      } else {
        resolve((stdout || "").trim());
      }
    });
  });
}

function activeProjectRoot() {
  const folder = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
  return folder ? folder.uri.fsPath : undefined;
}

async function run(command, args, options) {
  const root = (options && options.projectRoot) || activeProjectRoot();
  try {
    return await runCli([command].concat(args || []), root);
  } catch (e) {
    vscode.window.showErrorMessage("CHRONOVAULT: " + e.message);
    throw e;
  }
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
      vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: verifying + checkpointing…" },
        () => run("checkpoint", label ? ["--label", label] : []).then(output => {
          vscode.window.showInformationMessage("ChronoVault checkpoint complete.");
        }));
      refreshStatus(statusItem);
    }),

    vscode.commands.registerCommand("chronovault.restore", async () => {
      const answer = await vscode.window.showWarningMessage(
        "Restore project to the last verified state? Current work will be protected.",
        { modal: true }, "Restore", "Cancel");
      if (answer !== "Restore") return;
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Notification,
        title: "ChronoVault: restoring (verify + rollback protection)…" },
        () => run("restore", ["--yes"]).then(output => {
          const ok = /STATE RESTORED/.test(output);
          vscode.window.showInformationMessage(ok
            ? "ChronoVault: state restored and verified."
            : "ChronoVault: recovery did not complete — see output.");
        }));
      refreshStatus(statusItem);
    }),

    vscode.commands.registerCommand("chronovault.diagnose", async () => {
      await vscode.window.withProgress({ location: vscode.ProgressLocation.Window,
        title: "ChronoVault: diagnosing…" },
        () => run("diagnose").then(output => {
          const html = "<pre style='monospace'>" + stripAnsi(output).replace(/</g, "&lt;") + "</pre>";
          const panel = vscode.window.createWebviewPanel("chronovault.diagnose",
            "ChronoVault — What broke it?", vscode.ViewColumn.Active, { enableScripts: false });
          panel.webview.html = "<html><body style='background:#0b0e15;color:#d7e0f2'>" + html + "</body></html>";
        }));
    }),

    vscode.commands.registerCommand("chronovault.status", async () => {
      await run("state").then(output => {
        const summary = stripAnsi(output).split("\n").filter(l => /Status|Last verified|Checkpoints|Recoveries|Storage/.test(l)).join("\n");
        vscode.window.showInformationMessage(summary || stripAnsi(output));
        refreshStatus(statusItem);
      });
    }),

    vscode.commands.registerCommand("chronovault.dashboard", async () => {
      await run("ui", ["--open"]).then(() => {});
    })
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