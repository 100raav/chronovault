/*!
 * CHRONOVAULT bridge — the only VS Code-specific piece of the dashboard.
 *
 * The dashboard app (app.js) is shared verbatim with the CLI browser dashboard.
 * Inside the VS Code webview it cannot call window.fetch / EventSource (the page
 * CSP forbids connect-src so there are NO network calls from the webview).
 * This script shims those two APIs and proxies them over the VS Code webview
 * postMessage bus to the extension, which relays to the local `chronovault ui`
 * HTTP+SSE server (loopback) and returns the responses.
 *
 * CSP: this file must be served with a per-load nonce. It performs no eval,
 * no fetch, no new Function — messages only.
 */
(function () {
  "use strict";

  var vscode = acquireVsCodeApi();
  window.__CV_EMBEDDED__ = true;

  var pending = Object.create(null);
  var nextId = 0;
  var eventSources = Object.create(null);
  var MAX_SSE_RETRIES = 5;

  function post(type, payload) {
    if (typeof vscode !== "undefined") {
      vscode.postMessage(Object.assign({ type: type }, payload || {}));
    }
  }

  /* ---------- fetch() shim -> extension -> local CLI server ---------- */
  function shimFetch() {
    var original = window.fetch;

    window.fetch = function fetch(path, opts) {
      opts = opts || {};
      var id = ++nextId;
      return new Promise(function (resolve, reject) {
        pending[id] = { resolve: resolve, reject: reject };
        post("api", {
          id: id,
          path: String(path),
          method: opts.method || "GET",
          headers: opts.headers || undefined,
          body: typeof opts.body === "string" ? opts.body : undefined,
        });
      });
    };

    // Extension relays window.fetch/cv window.fetch internally; expose the raw
    // transport for tooling/tests only (never used by app.js).
    return original;
  }
  shimFetch();

  /* ---------- EventSource shim -> extension -> CLI SSE stream ---------- */
  function CVEventSource(path) {
    this.path = path;
    this.id = ++nextId;
    this.onopen = null;
    this.onmessage = null;
    this.onerror = null;
    this.retries = 0;
    this.connected = false;
    this.fatal = false;
    this.timer = null;
    eventSources[this.id] = this;
    this.open();
  }
  CVEventSource.prototype.open = function open() {
    var self = this;
    if (this.fatal) return;
    post("sse", { id: this.id, path: String(this.path) });
  };
  CVEventSource.prototype.scheduleReconnect = function scheduleReconnect() {
    var self = this;
    if (this.fatal) return;
    if (this.retries >= MAX_SSE_RETRIES) {
      this.fatal = true;
      if (this.onerror) this.onerror.call(this, { fatal: true });
      return;
    }
    var delay = Math.min(250 * Math.pow(2, this.retries), 4000);
    this.retries += 1;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(function () {
      this.connected = false;
      // close the previous (possibly half-open) server-side stream first, so a
      // re-open is never duplicated.
      post("sse-close", { id: self.id });
      self.open();
    }, delay);
  };
  CVEventSource.prototype.reconnectNow = function reconnectNow() {
    if (this.fatal) return;
    if (this.timer) clearTimeout(this.timer);
    this.connected = false;
    post("sse-close", { id: this.id });
    this.open();
  };
  CVEventSource.prototype.close = function close() {
    this.fatal = true;
    if (this.timer) clearTimeout(this.timer);
    var id = this.id;
    delete eventSources[id];
    post("sse-close", { id: id });
  };
  window.EventSource = CVEventSource;

  window.cvReconnectSSE = function cvReconnectSSE() {
    Object.keys(eventSources).forEach(function (id) {
      var es = eventSources[id];
      if (es && !es.fatal) es.reconnectNow();
    });
  };

  function dispatchEventSource(type, id, payload) {
    var es = eventSources[id];
    if (!es) return;
    if (type === "sse-open") {
      es.connected = true;
      es.retries = 0;
      if (es.onopen) es.onopen.call(es, payload || {});
    } else if (type === "sse") {
      if (es.onmessage) es.onmessage.call(es, payload || {});
    } else if (type === "sse-error") {
      es.connected = false;
      if (es.onerror) es.onerror.call(es, payload || {});
      es.scheduleReconnect();
    }
  }

  /* ---------- theme + metadata ---------------------------------------- */
  function applyHostTheme(theme) {
    if (!theme) return;
    document.documentElement.dataset.theme = theme;
    // let the dashboard-local theme applyThemeLocal re-apply if reachable
    if (window.cvBridge && window.cvBridge.applyThemeLocal) {
      try { window.cvBridge.applyThemeLocal(theme, false); } catch (e) { /* noop */ }
    }
  }

  /* ---------- messages from the extension ------------------------------ */
  window.addEventListener("message", function (event) {
    var msg = event.data;
    if (!msg || typeof msg.type !== "string") return;

    if (msg.type === "api") {
      var p = pending[msg.id];
      if (!p) return;
      delete pending[msg.id];
      if (msg.error) {
        p.reject(new Error(msg.error));
        return;
      }
      var status = msg.status != null ? msg.status : 200;
      var body = msg.body != null ? msg.body : "";
      var res = {
        ok: status >= 200 && status < 300,
        status: status,
        text: function () { return Promise.resolve(body); },
        json: function () {
          try { return Promise.resolve(JSON.parse(body)); }
          catch (e) { return Promise.reject(new Error("bridge: invalid JSON from API response")); }
        },
      };
      p.resolve(res);
    } else if (msg.type === "sse" || msg.type === "sse-open" || msg.type === "sse-error") {
      dispatchEventSource(msg.type, msg.id, msg);
    } else if (msg.type === "theme") {
      applyHostTheme(msg.theme);
    } else if (msg.type === "refresh") {
      if (window.cvBridge && window.cvBridge.refreshAll) {
        try { window.cvBridge.refreshAll(); } catch (e) { /* noop */ }
      }
    } else if (msg.type === "hello") {
      if (msg.projectName) {
        var name = document.getElementById("projectName");
        var path = document.getElementById("projectPath");
        if (name && msg.projectName) name.textContent = msg.projectName;
        if (path && msg.projectPath) path.textContent = msg.projectPath;
        var ver = document.getElementById("version");
        if (ver && msg.dashboardVersion) ver.textContent = "CHRONOVAULT " + msg.dashboardVersion;
      }
      applyHostTheme(msg.theme);
    }
  });

  post("ready", {
    embedded: true,
    userAgent: navigator.userAgent || "",
  });
})();