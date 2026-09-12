"use strict";

/**
 * CHRONOVAULT main dashboard — a real editor {@code WebviewPanel}.
 *
 * This is the architectural fix for the 1.0.2 defect where `chronovault.dashboard`
 * called {@code reveal()} on the WebviewView exposed by a WebviewViewProvider.
 * {@code reveal()} is a {@code WebviewPanel} API and is only ever called on the
 * panel instance managed here — never on any provider or view.
 *
 * Data/security model (identical to the sidebar webview):
 *   1. the panel loads the shared dashboard bundle with a strict CSP and a
 *      per-load nonce; the page makes NO network calls itself (connect-src 'none'),
 *   2. `bridge.js` shims fetch/EventSource over webview postMessage,
 *   3. every incoming message is validated against an explicit command/path
 *      allowlist before it is proxied to the local loopback `chronovault ui`.
 *
 * The local dashboard server is shared, reference-counted, and torn down when
 * the last consumer (panel or sidebar) goes away, so closing VS Code does not
 * leak a `chronovault ui` process.
 */

const vscode = require("./_vscode");
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const { DashboardServer } = require("./dashboardServer");

const PANEL_VIEW_TYPE = "chronovault.dashboardPanel";
const DASHBOARD_VERSION = "1.0.3";

/**
 * Explicit allowlist of API operations the webview may trigger.
 * A message that does not match one of these rows is rejected with 403 and
 * never forwarded to the loopback server.
 */
const API_ALLOWLIST = [
  // getDashboardState / getTimeline / getStorage / getHistory (read-only)
  { method: "GET", path: /^\/api\/meta$/ },
  { method: "GET", path: /^\/api\/state$/ },
  { method: "GET", path: /^\/api\/checkpoints$/ },
  { method: "GET", path: /^\/api\/checkpoint\/[A-Za-z0-9_-]+$/ },
  { method: "GET", path: /^\/api\/history$/ },
  { method: "GET", path: /^\/api\/storage$/ },
  { method: "GET", path: /^\/api\/config$/ },
  { method: "GET", path: /^\/api\/plan(\?.*)?$/ },
  { method: "GET", path: /^\/api\/diff(\?.*)?$/ },
  { method: "GET", path: /^\/api\/diagnose$/ },
  // actions (POST)
  { method: "POST", path: /^\/api\/checkpoint$/ },
  { method: "POST", path: /^\/api\/health$/ },
  { method: "POST", path: /^\/api\/recover(\?.*)?$/ },
  // SSE stream
  { method: "GET", path: /^\/api\/events$/ },
];

function allowed(method, pathName) {
  const m = String(method || "GET").toUpperCase();
  return API_ALLOWLIST.some(
    (row) => row.method === m && row.path.test(String(pathName))
  );
}

class DashboardPanel {
  /**
   * @param {vscode.ExtensionContext} context
   * @param {{ cliResolver: any, getProjectRoot: () => string|undefined,
   *           server?: DashboardServer, dashboardVersion?: string }} deps
   */
  constructor(context, deps) {
    this.context = context;
    this.deps = deps;
    this._server = deps.server || new DashboardServer();
    this._serverRefs = 0;
    this.panel = null;
    this._streams = new Map();
    this._disposables = [];

    this._server.onExit = () => {
      this._broadcast({ type: "hello", theme: this._theme(), disconnected: true });
    };

    this._disposables.push(
      vscode.window.onDidChangeActiveColorTheme(() => {
        this._broadcast({ type: "theme", theme: this._theme() });
      })
    );
  }

  get running() {
    return !!this.panel;
  }

  dispose() {
    for (const d of this._disposables) d.dispose();
    this._disposables = [];
    this.disposePanel();
  }

  disposePanel() {
    for (const c of Array.from(this._streams.values())) c.close();
    this._streams.clear();
    this._releaseServer();
    if (this.panel && !this.panel.disposed) {
      this.panel.dispose();
    }
    this.panel = null;
  }

  _acquireServer() {
    this._serverRefs++;
  }

  _releaseServer() {
    if (this._serverRefs <= 0) return;
    this._serverRefs--;
    if (this._serverRefs === 0) {
      this._server.stop().catch(() => {});
    }
  }

  /**
   * Open (create or reveal) the dashboard panel. The main entry point for the
   * `chronovault.dashboard` command.
   */
  async open() {
    const root = this._projectRoot();
    if (this.panel && !this.panel.disposed) {
      // The reusable panel already exists — reveal it. `reveal()` is a
      // WebviewPanel API, and this.panel is always a WebviewPanel here.
      this.panel.reveal(vscode.ViewColumn.Active);
      await this._render(this.panel);
      return this.panel;
    }

    const panel = this._createPanel();
    this.panel = panel;
    this._wireDispose(panel);
    this._post(panel, { type: "hello", theme: this._theme(), dashboardVersion: DASHBOARD_VERSION });

    if (!root) {
      panel.webview.html = this._setupHtml({
        msg: "Open a project to surface the CHRONOVAULT timeline.",
        actions: [],
      });
      return panel;
    }

    let cli = null;
    try {
      cli = (await this.deps.cliResolver.resolve(root)) || null;
    } catch (err) {
      cli = null;
    }

    panel.webview.html = this._setupHtml({
      msg: cli
        ? "Starting CHRONOVAULT dashboard…"
        : "CHRONOVAULT runtime not found. Locate the CLI to enable the embedded dashboard.",
      actions: cli ? [] : [{ id: "configure-cli", label: "Configure CLI" }, { id: "locate-runtime", label: "Locate Runtime" }, { id: "retry", label: "Retry" }],
    });

    if (cli) {
      try {
        await this._renderDashboard(panel, cli, root);
      } catch (err) {
        panel.webview.html = this._setupHtml({
          msg: "Could not start the CHRONOVAULT dashboard: " + (err.message || String(err)),
          actions: [
            { id: "retry", label: "Retry" },
            { id: "open-browser", label: "Open in Browser" },
          ],
        });
      }
    }
    return panel;
  }

  _createPanel() {
    const panel = vscode.window.createWebviewPanel(
      PANEL_VIEW_TYPE,
      "CHRONOVAULT — Temporal Recovery Console",
      vscode.ViewColumn.Active,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [
          vscode.Uri.file(path.join(this.context.extensionPath, "webview")),
        ],
      }
    );
    return panel;
  }

  _wireDispose(panel) {
    panel.onDidDispose(() => {
      if (this.panel === panel) {
        for (const c of Array.from(this._streams.values())) c.close();
        this._streams.clear();
        this._releaseServer();
        this.panel = null;
      }
    });
    panel.webview.onDidReceiveMessage((msg) => this._onMessage(panel, msg));
  }

  async _render(panel) {
    if (this.panel && this.panel === panel && !this.panel.disposed) {
      const root = this._projectRoot();
      let cli = null;
      try {
        cli = (await this.deps.cliResolver.resolve(root)) || null;
      } catch (err) { cli = null; }
      if (cli) {
        await this._renderDashboard(panel, cli, root);
      }
    }
  }

  async refresh() {
    if (!this.panel || this.panel.disposed) return;
    this._broadcast({ type: "refresh" });
  }

  async reload() {
    if (this.panel && !this.panel.disposed) {
      await this._render(this.panel);
    }
  }

  /** Fully rebuild the current panel webview (used by the dashboard Retry action). */
  async restart() {
    if (!this.panel || this.panel.disposed) return;
    this._releaseServer();
    await this.open();
  }

  _projectRoot() {
    return this.deps.getProjectRoot ? this.deps.getProjectRoot() : undefined;
  }

  async _renderDashboard(panel, cli, root) {
    this._acquireServer();
    await this._server.start({ cli, projectRoot: root });
    if (panel.disposed) {
      this._releaseServer();
      return;
    }
    panel.webview.html = this._dashboardHtml(panel.webview);
  }

  _theme() {
    const kind = vscode.window.activeColorTheme.kind;
    if (kind === vscode.ColorThemeKind.Light) return "light";
    if (kind === vscode.ColorThemeKind.HighContrast) return "dark";
    return "dark";
  }

  /* ---------------------------- messages ---------------------------- */

  async _onMessage(panel, msg) {
    if (!msg || typeof msg.type !== "string") return;
    switch (msg.type) {
      case "ready":
        this._post(panel, { type: "hello", theme: this._theme(), dashboardVersion: DASHBOARD_VERSION });
        break;
      case "setup":
        await this._handleSetupPanel(panel, msg.action);
        break;
      case "api":
        await this._handleApi(panel, msg);
        break;
      case "sse":
        this._handleSse(panel, msg);
        break;
      case "sse-close":
        this._closeStream(msg.id);
        break;
      default:
        break;
    }
  }

  async _handleSetupPanel(panel, action) {
    switch (action) {
      case "configure-cli":
        await vscode.commands.executeCommand("chronovault.configureCli");
        break;
      case "locate-runtime":
        await vscode.commands.executeCommand("chronovault.locateRuntime");
        break;
      case "retry":
        await this._render(panel);
        break;
      case "open-browser":
        await vscode.commands.executeCommand("chronovault.dashboardBrowser");
        break;
      default:
        break;
    }
  }

  async _handleApi(panel, msg) {
    const pathName = String(msg.path || "");
    const method = String(msg.method || "GET").toUpperCase();

    if (!allowed(method, pathName)) {
      this._post(panel, {
        type: "api",
        id: msg.id,
        status: 403,
        error: "Operation not allowed by the dashboard allowlist: " + method + " " + pathName,
      });
      return;
    }
    try {
      const res = await this._server.request(pathName, {
        method,
        headers: msg.headers || undefined,
        body: typeof msg.body === "string" ? msg.body : undefined,
      });
      this._post(panel, { type: "api", id: msg.id, status: res.status, body: res.body });
    } catch (err) {
      this._post(panel, { type: "api", id: msg.id, error: err.message || String(err) });
    }
  }

  _handleSse(panel, msg) {
    const pathName = String(msg.path || "");
    if (!allowed("GET", pathName)) return;
    const id = msg.id;
    this._closeStream(id);
    const ctl = this._server.openEventStream(pathName, {
      onOpen: () => this._post(panel, { type: "sse-open", id }),
      onEvent: (ev) => this._post(panel, { type: "sse", id, data: ev.data }),
      onError: (err) => this._post(panel, { type: "sse-error", id, error: err.message || String(err) }),
    });
    this._streams.set(id, ctl);
  }

  _closeStream(id) {
    const ctl = this._streams.get(id);
    if (ctl) {
      ctl.close();
      this._streams.delete(id);
    }
  }

  _broadcast(msg) {
    if (this.panel && !this.panel.disposed) {
      this._post(this.panel, msg);
    }
  }

  _post(panel, msg) {
    if (panel && panel.webview && !panel.webview.isDisposed) {
      panel.webview.postMessage(msg);
    }
  }

  _nonce() {
    return crypto.randomBytes(16).toString("base64");
  }

  /** Build the dashboard HTML with a per-load nonce and asWebviewUri() asset URIs. */
  _dashboardHtml(web) {
    let html = fs.readFileSync(
      path.join(this.context.extensionPath, "webview", "index.html"),
      "utf-8"
    );
    const nonce = this._nonce();
    const base = vscode.Uri.file(path.join(this.context.extensionPath, "webview"));
    const uri = (file) =>
      web.asWebviewUri(vscode.Uri.joinPath(base, file)).toString();
    return html
      .split("__CV_NONCE__").join(nonce)
      .split("__CV_APP_URI__").join(uri("app.js"))
      .split("__CV_BRIDGE_URI__").join(uri("bridge.js"))
      .split("__CV_STYLES_URI__").join(uri("styles.css"))
      .split("__CV_ICON_URI__").join(uri("icon.svg"));
  }

  _setupHtml(opts) {
    opts = opts || {};
    const nonce = this._nonce();
    const actions = (opts.actions || [])
      .map((a) => `<button class="store-btn" data-cv-setup="${a.id}">${a.label}</button>`)
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

module.exports = { DashboardPanel, API_ALLOWLIST, allowed, PANEL_VIEW_TYPE };