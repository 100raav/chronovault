"use strict";

/**
 * CHRONOVAULT embedded dashboard (sidebar webview view).
 *
 * Serves the shared dashboard bundle (vscode-extension/webview/*.html, synced
 * from cli/src/main/resources/web via scripts/sync-dashboard.sh) and proxies the
 * webview's API traffic (fetch/EventSource shims in bridge.js) to a local
 * `chronovault ui --port <p>` server. Strict CSP: no inline scripts, nonce on
 * every script, no network from the webview.
 */

const vscode = require("vscode");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { DashboardServer } = require("./dashboardServer");

const CONFIGURE_CLI = "chronovault.configureCli";
const LOCATE_RUNTIME = "chronovault.locateRuntime";

class DashboardViewProvider {
  /**
   * @param {vscode.ExtensionContext} context
   * @param {{ cliResolver: any, getProjectRoot: () => string|undefined, server?: DashboardServer }} options
   */
  constructor(context, options) {
    this.context = context;
    this.options = options;
    this.server = options.server || new DashboardServer();
    this.view = null;
    this._streams = new Map();

    this.server.onExit = () => {
      if (this.view) {
        this._post(this.view, { type: "hello", theme: this._theme(), disconnected: true });
      }
    };

    this._themeSub = vscode.window.onDidChangeActiveColorTheme(() => {
      if (this.view) this._post(this.view, { type: "theme", theme: this._theme() });
    });
    this._disposables = [this._themeSub];
  }

  dispose() {
    for (const d of this._disposables) d.dispose();
    this.disposeView();
  }

  disposeView() {
    for (const c of this._streams.values()) c.close();
    this._streams.clear();
    this.server.stop().catch(() => {});
  }

  async reload() {
    if (!this.view) return;
    await this.server.stop().catch(() => {});
    if (this.view && this.view.webview) {
      await this._render(this.view);
    }
  }

  async refresh() {
    if (!this.view) {
      vscode.window.showInformationMessage("CHRONOVAULT dashboard is not open.");
      return;
    }
    if (this.view.webview) {
      this._post(this.view, { type: "refresh" });
    }
  }

  _theme() {
    const kind = vscode.window.activeColorTheme.kind;
    if (kind === vscode.ColorThemeKind.Light) return "light";
    if (kind === vscode.ColorThemeKind.HighContrast) return "dark";
    return "dark";
  }

  _projectRoot() {
    return this.options.getProjectRoot ? this.options.getProjectRoot() : undefined;
  }

  async resolveWebviewView(webviewView) {
    this.view = webviewView;
    webviewView.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.file(path.join(this.context.extensionPath, "webview"))],
    };
    webviewView.webview.onDidReceiveMessage((msg) => this._onMessage(webviewView, msg));
    webviewView.onDidDispose(() => {
      if (this.view === webviewView) {
        this.disposeView();
        this.view = null;
      }
    });
    await this._render(webviewView);
  }

  async _render(webviewView) {
    const root = this._projectRoot();
    if (!root) {
      webviewView.webview.html = this._setupHtml({ msg: "Open a project to surface the CHRONOVAULT timeline." });
      return;
    }
    let cli = null;
    try {
      cli = (await this.options.cliResolver.resolve(root)) || null;
    } catch (err) {
      cli = null;
    }
    if (!cli) {
      webviewView.webview.html = this._setupHtml({
        msg: "CHRONOVAULT runtime not found. Configure the CLI path to enable embedded dashboards.",
        actions: [
          { id: "configure-cli", label: "Configure CLI" },
          { id: "locate-runtime", label: "Locate Runtime" },
          { id: "retry", label: "Retry" },
        ],
      });
      return;
    }
    try {
      await this.server.start({ cli, projectRoot: root });
      webviewView.webview.html = this._dashboardHtml(webviewView.webview);
    } catch (err) {
      webviewView.webview.html = this._setupHtml({
        msg: "Could not start the CHRONOVAULT dashboard server: " + (err.message || String(err)),
        actions: [
          { id: "retry", label: "Retry" },
          { id: "open-browser", label: "Open in Browser" },
        ],
      });
    }
  }

  async _onMessage(webviewView, msg) {
    if (!msg || typeof msg.type !== "string") return;
    switch (msg.type) {
      case "ready":
        this._post(webviewView, { type: "hello", theme: this._theme(), dashboardVersion: "1.0.2" });
        break;
      case "setup":
        await this._handleSetup(webviewView, msg.action);
        break;
      case "api":
        await this._handleApi(webviewView, msg);
        break;
      case "sse":
        this._handleSse(webviewView, msg);
        break;
      case "sse-close":
        this._closeStream(msg.id);
        break;
      default:
        break;
    }
  }

  async _handleSetup(webviewView, action) {
    switch (action) {
      case "configure-cli":
        await vscode.commands.executeCommand(CONFIGURE_CLI);
        break;
      case "locate-runtime":
        await vscode.commands.executeCommand(LOCATE_RUNTIME);
        break;
      case "retry":
        await this._render(webviewView);
        break;
      case "open-browser":
        await vscode.commands.executeCommand("chronovault.dashboardBrowser");
        break;
      default:
        break;
    }
  }

  async _handleApi(webviewView, msg) {
    try {
      const res = await this.server.request(msg.path, {
        method: msg.method || "GET",
        headers: msg.headers || undefined,
        body: msg.body || undefined,
      });
      this._post(webviewView, { type: "api", id: msg.id, status: res.status, body: res.body });
    } catch (err) {
      this._post(webviewView, { type: "api", id: msg.id, error: err.message || String(err) });
    }
  }

  _handleSse(webviewView, msg) {
    const id = msg.id;
    const ctl = this.server.openEventStream(msg.path, {
      onOpen: () => this._post(webviewView, { type: "sse-open", id }),
      onEvent: (ev) => this._post(webviewView, { type: "sse", id, data: ev.data }),
      onError: (err) => this._post(webviewView, { type: "sse-error", id, error: err.message || String(err) }),
    });
    this._streams.set(id, ctl);
    webviewView.onDidDispose(() => this._closeStream(id));
  }

  _closeStream(id) {
    const ctl = this._streams.get(id);
    if (ctl) {
      ctl.close();
      this._streams.delete(id);
    }
  }

  _post(webviewView, msg) {
    if (webviewView && webviewView.webview && !webviewView.webview.isDisposed) {
      webviewView.webview.postMessage(msg);
    }
  }

  _nonce() {
    return crypto.randomBytes(16).toString("base64");
  }

  _dashboardHtml(web) {
    const onDisk = path.join(this.context.extensionPath, "webview", "index.html");
    let html = fs.readFileSync(onDisk, "utf-8");
    const nonce = this._nonce();
    html = html.split("__CV_NONCE__").join(nonce);
    return html;
  }

  _setupHtml(opts) {
    opts = opts || {};
    const nonce = this._nonce();
    const actions = (opts.actions || [])
      .map(
        (a) =>
          `<button class="store-btn" data-cv-setup="${a.id}">${a.label}</button>`
      )
      .join("");
    const escaped = String(opts.msg || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
    return `<!DOCTYPE html>
<html lang="en" data-theme="${this._theme()}">
<head>
<meta charset="UTF-8" />
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none';" />
<title>CHRONOVAULT</title>
<style>
  body { margin:0; padding:24px; font-family: system-ui, "Segoe UI", sans-serif; color: #cfd8e3; background: #0a0e15; }
  h1 { font-size: 15px; letter-spacing: 2px; color: #57d9a3; margin: 0 0 12px 0; }
  p { font-size: 13px; line-height: 1.5; color: #93a2b4; margin: 0 0 18px 0; }
  .store-btn { display: inline-block; margin: 4px 8px 4px 0; padding: 8px 14px; border: 1px solid #26415a;
    border-radius: 6px; background: #10161f; color: #a9e0fb; font-size: 12px; cursor: pointer; }
  .store-btn:hover { background: #15202e; }
</style>
</head>
<body>
<h1>CHRONOVAULT</h1>
<p>${escaped}</p>
${actions}
<script src="./setup.js" nonce="${nonce}"></script>
</body>
</html>`;
  }
}

module.exports = { DashboardViewProvider };