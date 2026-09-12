"use strict";

/**
 * Manages the local CHRONOVAULT dashboard server (`chronovault ui --port N`)
 * that backs the embedded VS Code webview: free-port selection, lifecycle,
 * and a small HTTP/SSE client used to proxy webview API calls.
 *
 * Pure Node (no VS Code dependency) so it is unit-testable with node:test.
 */

const http = require("node:http");
const net = require("node:net");
const cp = require("node:child_process");

function findFreePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.unref();
    srv.on("error", reject);
    srv.listen(0, "127.0.0.1", () => {
      const port = srv.address().port;
      srv.close(() => resolve(port));
    });
  });
}

const DEFAULT_TIMEOUT_MS = 15000;
const READY_POLL_MS = 200;

class DashboardServer {
  constructor(options) {
    options = options || {};
    this.timeoutMs = options.timeoutMs || DEFAULT_TIMEOUT_MS;
    this._child = null;
    this._stopping = false;
    this._exit = null;
    this._streams = new Set();
    this.onExit = null;
  }

  get running() {
    return !!this.port && !!this._child;
  }

  /**
   * Start `cli ui --port <p>` bound to loopback for the given project and wait
   * until the API is responsive. Resolves with the chosen port.
   */
  async start({ cli, projectRoot, port, env, spawn }) {
    if (!cli) {
      throw new Error("CHRONOVAULT CLI runtime not configured");
    }
    const p = port || (await findFreePort());
    const child =
      typeof spawn === "function"
        ? spawn(cli, ["ui", "--port", String(p)])
        : cp.spawn(cli, ["ui", "--port", String(p)], {
            env: Object.assign({}, process.env, env || {}, {
              CHRONOVAULT_PROJECT: projectRoot || "",
            }),
            stdio: "ignore",
            windowsHide: true,
          });

    this._child = child;
    this.port = p;
    this._exit = null;

    if (child && typeof child.on === "function") {
      child.on("exit", (code, signal) => {
        if (!this._stopping) {
          this._exit = { code, signal };
          const stream = this;
          this.port = null;
          this._child = null;
          for (const c of Array.from(this._streams)) c.close();
          this._streams.clear();
          if (this.onExit) this.onExit(code, signal);
          void stream;
        }
      });
    }

    try {
      await this._waitReady();
    } catch (err) {
      const exitDetail = this._exit ? ` (child exited code=${this._exit.code} signal=${this._exit.signal})` : "";
      await this.stop().catch(() => {});
      throw new Error(
        `CHRONOVAULT dashboard server did not become ready within ${this.timeoutMs}ms` +
          `: ${err.message}${exitDetail}`
      );
    }
    return p;
  }

  async stop() {
    this._stopping = true;
    try {
      if (this._child && typeof this._child.pid === "number") {
        try {
          process.kill(this._child.pid);
        } catch (err) {
          if (err.code !== "ESRCH") throw err;
        }
      }
    } finally {
      for (const c of Array.from(this._streams)) c.close();
      this._streams.clear();
      this._child = null;
      this.port = null;
      this._stopping = false;
    }
  }

  /** GET/POST a path on the local server. Resolves {status, headers, body}. */
  async request(path, opts) {
    if (!this.port) {
      throw new Error("CHRONOVAULT dashboard server is not running");
    }
    opts = opts || {};
    const url = new URL(path, `http://127.0.0.1:${this.port}`);
    return new Promise((resolve, reject) => {
      const req = http.request(
        url,
        {
          method: opts.method || "GET",
          headers: Object.assign(
            { accept: "application/json" },
            opts.headers || {}
          ),
        },
        (res) => {
          const chunks = [];
          res.on("data", (c) => chunks.push(c));
          res.on("error", reject);
          res.on("end", () => {
            resolve({
              status: res.statusCode,
              headers: res.headers,
              body: Buffer.concat(chunks).toString("utf-8"),
            });
          });
        }
      );
      req.on("error", reject);
      req.setTimeout(this.timeoutMs, () =>
        req.destroy(new Error("CHRONOVAULT: request timed out"))
      );
      if (opts.body) req.write(opts.body);
      req.end();
    });
  }

  /**
   * Open an SSE stream. Callbacks: onOpen(), onEvent({data}), onError(Error).
   * Returns a controller with close().
   */
  openEventStream(path, handlers) {
    handlers = handlers || {};
    const ctl = {
      closed: false,
      req: null,
      close() {
        if (this.closed) return;
        this.closed = true;
        if (this.req) {
          try {
            this.req.destroy();
          } catch (err) {
            /* noop */
          }
        }
      },
    };
    if (!this.port) {
      process.nextTick(() =>
        handlers.onError && handlers.onError(new Error("CHRONOVAULT dashboard server is not running"))
      );
      return ctl;
    }
    const url = new URL(path, `http://127.0.0.1:${this.port}`);
    const req = http.request(url, { headers: { accept: "text/event-stream" } }, (res) => {
      if (res.statusCode !== 200) {
        ctl.closed = true;
        if (handlers.onError) {
          handlers.onError(new Error("SSE stream returned status " + res.statusCode));
        }
        return;
      }
      if (handlers.onOpen) handlers.onOpen();
      this._streams.add(ctl);
      let buf = "";
      res.on("data", (chunk) => {
        buf += chunk.toString("utf-8");
        let idx;
        while ((idx = buf.indexOf("\n\n")) !== -1) {
          const raw = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const line = raw.split("\n").find((l) => l.indexOf("data: ") === 0);
          if (line && handlers.onEvent) handlers.onEvent({ data: line.slice(6) });
        }
      });
      res.on("end", () => {
        ctl.closed = true;
        this._streams.delete(ctl);
        if (handlers.onError) handlers.onError(new Error("SSE stream ended"));
      });
      res.on("error", (err) => {
        ctl.closed = true;
        this._streams.delete(ctl);
        if (handlers.onError) handlers.onError(err);
      });
    });
    req.on("error", (err) => {
      ctl.closed = true;
      if (handlers.onError) handlers.onError(err);
    });
    ctl.req = req;
    req.end();
    return ctl;
  }

  async _waitReady() {
    const deadline = Date.now() + this.timeoutMs;
    let lastErr = null;
    while (Date.now() < deadline) {
      if (!this._child) break;
      try {
        const res = await this.request("/api/state");
        if (res.status >= 200 && res.status < 300) return;
        lastErr = new Error("status " + res.status);
      } catch (err) {
        lastErr = err;
      }
      await new Promise((r) => setTimeout(r, READY_POLL_MS));
    }
    throw lastErr || new Error("server not running");
  }
}

module.exports = { DashboardServer, findFreePort };