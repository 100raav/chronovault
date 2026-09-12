"use strict";

/*
 * Minimal `vscode` API stub used ONLY when the extension module is required
 * outside the VS Code host (unit tests). In a real VS Code session the real
 * `vscode` module is resolved first by _vscode.js; this file never ships logic
 * used at runtime.
 */

const EventEmitter = require("node:events");

class FakeDisposable {
  constructor(fn) { this._fn = fn; }
  dispose() { if (this._fn) { this._fn(); this._fn = null; } }
}

class FakeWebviewPanel {
  constructor(spec) {
    this.viewType = spec.viewType;
    this.title = spec.title;
    this.webview = new FakeWebview();
    this.viewColumn = spec.viewColumn;
    this.options = spec.options;
    this.disposed = false;
    this.onDidDisposeHandlers = [];
    this.webview.onDidReceiveMessage(this._fireDisposeIfDisposed.bind(this));
  }
  _fireDisposeIfDisposed(handler) {} // place-holder for message wiring
  onDidDispose(handler) {
    this.onDidDisposeHandlers.push(handler);
    return new FakeDisposable();
  }
  reveal() {}
  dispose() {
    if (this.disposed) return;
    this.disposed = true;
    const hs = this.onDidDisposeHandlers;
    this.onDidDisposeHandlers = [];
    for (const h of hs) h();
  }
}

class FakeWebview {
  constructor() {
    this.isDisposed = false;
    this.html = "";
    this.messageHandlers = [];
  }
  onDidReceiveMessage(handler) {
    this.messageHandlers.push(handler);
    return new FakeDisposable();
  }
  postMessage() { return Promise.resolve(); }
}

const fakePanel = new FakeWebviewPanel({
  viewType: "chronovault.dashboardPanel",
  title: "test",
  viewColumn: "Active",
  options: { enableScripts: true }
});

module.exports = {
  ViewColumn: { Active: "Active" },
  ColorThemeKind: { Light: 1, Dark: 2, HighContrast: 3 },
  Uri: {
    file() { return { fileName: "" }; }
  },
  window: {
    activeColorTheme: { kind: 2 },
    createWebviewPanel(viewType, title, viewColumn, options) {
      return new FakeWebviewPanel({ viewType, title, viewColumn, options });
    },
    onDidChangeActiveColorTheme() { return new FakeDisposable(); },
  },
  commands: {
    executeCommand() { return Promise.resolve(); },
  },
  _fakePanel: fakePanel,
  _FakeWebviewPanel: FakeWebviewPanel,
  _FakeWebview: FakeWebview,
  _FakeDisposable: FakeDisposable,
};