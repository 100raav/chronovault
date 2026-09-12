"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const Module = require("node:module");

function makeVscode(extra) {
  const vscode = {
    ColorThemeKind: { Light: 1, Dark: 2, HighContrast: 3 },
    Uri: { file(p) { return { $uriFile: p }; } },
    window: {
      activeColorTheme: { kind: 2 },
      onDidChangeActiveColorTheme(cb) {
        vscode.window._themeListener = cb;
        return { dispose() {} };
      },
      showInformationMessage() { return Promise.resolve(); },
    },
    commands: {
      executeCommand(id) {
        return Promise.resolve();
      },
    },
  };
  return Object.assign(vscode, extra || {});
}

function loadProvider(vscodeStub) {
  const origLoad = Module._load;
  Module._load = function (request, parent, isMain) {
    if (request === "vscode") return vscodeStub;
    return origLoad.call(this, request, parent, isMain);
  };
  try {
    delete require.cache[require.resolve("./dashboardView")];
    return require("./dashboardView");
  } finally {
    Module._load = origLoad;
  }
}

function makeWebview() {
  return {
    options: null,
    html: "",
    isDisposed: false,
    posts: [],
    postMessage(m) {
      this.posts.push(m);
      return Promise.resolve();
    },
    onDidReceiveMessage(cb) {
      this._msgHandler = cb;
    },
  };
}

function makeView() {
  const webview = makeWebview();
  const view = {
    webview,
    _disposeHandlers: [],
    onDidDispose(cb) {
      this._disposeHandlers.push(cb);
    },
    fireDispose() {
      for (const cb of this._disposeHandlers) cb();
    },
  };
  return view;
}

function FakeServer() {
  this.startCount = 0;
  this.started = [];
  this.stopped = 0;
  this.requests = [];
  this.streams = [];
}
FakeServer.prototype.start = async function ({ cli, projectRoot, port }) {
  this.startCount++;
  this.started.push({ cli, projectRoot, port });
  return port || 9876;
};
FakeServer.prototype.stop = async function () { this.stopped++; };
FakeServer.prototype.request = async function (p, opts) {
  this.requests.push({ p, opts });
  return { status: 200, body: `{"path":"${p}"}` };
};
FakeServer.prototype.openEventStream = function (p, handlers) {
  const ctl = { closed: false, close() { this.closed = true; } };
  this.streams.push({ p, handlers, ctl });
  return ctl;
};

async function makeProvider(overrides, vscodeStub) {
  const opts = Object.assign(
    {
      cliResolver: { resolve: async () => "/fake/chronovault" },
      getProjectRoot: () => "/tmp/proj",
    },
    overrides || {}
  );
  const { DashboardViewProvider } = loadProvider(vscodeStub || makeVscode());
  const server = new FakeServer();
  const provider = new DashboardViewProvider(
    { extensionPath: __dirname },
    Object.assign({ server }, opts)
  );
  return { provider, server };
}

test("_theme maps VS Code color theme kinds to dashboard themes", async () => {
  const { provider } = await makeProvider();
  assert.equal(provider._theme(), "dark");

  const stub = makeVscode();
  stub.window.activeColorTheme.kind = 1;
  const { provider: light } = await makeProvider(undefined, stub);
  assert.equal(light._theme(), "light");

  const stubHc = makeVscode();
  stubHc.window.activeColorTheme.kind = 3;
  const { provider: hc } = await makeProvider(undefined, stubHc);
  assert.equal(hc._theme(), "dark");
});

test("getProjectRoot is asked for the active project", async () => {
  let asked = 0;
  const { provider } = await makeProvider({
    getProjectRoot: () => { asked++; return "/tmp/proj"; },
  });
  provider._projectRoot();
  assert.equal(asked, 1);
});

test("no-project resolves to a read-only setup page", async () => {
  const { provider } = await makeProvider({ getProjectRoot: () => undefined });
  const view = makeView();
  await provider.resolveWebviewView(view);
  assert.match(view.webview.html, /Open a project/);
  assert.match(view.webview.html, /Content-Security-Policy/);
  assert.match(view.webview.html, /default-src 'none'/);
  assert.match(view.webview.html, /setup\.js/);
});

test("runtime missing resolves to a setup page with Configure/Locate/Retry", async () => {
  const { provider } = await makeProvider({ cliResolver: { resolve: async () => null } });
  const view = makeView();
  await provider.resolveWebviewView(view);
  assert.match(view.webview.html, /runtime not found/i);
  assert.ok(view.webview.html.includes('data-cv-setup="configure-cli"'));
  assert.ok(view.webview.html.includes('data-cv-setup="locate-runtime"'));
  assert.ok(view.webview.html.includes('data-cv-setup="retry"'));
});

test("dashboard render starts the server and serves the locked-down bundle", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  assert.equal(server.startCount, 1);
  assert.deepEqual(server.started[0], { cli: "/fake/chronovault", projectRoot: "/tmp/proj", port: undefined });
  assert.match(view.webview.html, /bridge\.js/);
  assert.match(view.webview.html, /default-src 'none'/);
  assert.match(view.webview.html, /connect-src 'none'/);
  assert.ok(!view.webview.html.includes("__CV_NONCE__"), "nonce placeholder must be replaced per load");
  assert.match(view.webview.html, /nonce=/);
});

test("server start failure surfaces an error page with Retry + Browser fallback", async () => {
  const { provider } = await makeProvider();
  provider.server.start = async () => { throw new Error("boom: server refused"); };
  const view = makeView();
  await provider.resolveWebviewView(view);
  assert.match(view.webview.html, /boom: server refused/);
  assert.ok(view.webview.html.includes('data-cv-setup="retry"'));
  assert.ok(view.webview.html.includes('data-cv-setup="open-browser"'));
});

test("api messages proxy to the local server and answer the webview", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "api", id: 42, path: "/api/state", method: "GET" });
  assert.deepEqual(server.requests[0].p, "/api/state");
  const answer = view.webview.posts.find((m) => m.type === "api" && m.id === 42);
  assert.equal(answer.status, 200);
  assert.match(answer.body, /api\/state/);
});

test("api errors reject the pending webview call with the error text", async () => {
  const { provider } = await makeProvider();
  provider.server.request = async () => { throw new Error("zeta failed"); };
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "api", id: 7, path: "/api/state" });
  const answer = view.webview.posts.find((m) => m.type === "api" && m.id === 7);
  assert.equal(answer.error, "zeta failed");
});

test("sse messages open + close a stream and relay events", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "sse", id: 3, path: "/api/events" });
  assert.equal(server.streams[0].p, "/api/events");
  server.streams[0].handlers.onOpen();
  server.streams[0].handlers.onEvent({ data: "{\"t\":1}" });
  assert.ok(view.webview.posts.find((m) => m.type === "sse-open" && m.id === 3));
  assert.ok(view.webview.posts.find((m) => m.type === "sse" && m.id === 3 && m.data === "{\"t\":1}"));
  await view.webview._msgHandler({ type: "sse-close", id: 3 });
  assert.ok(server.streams[0].ctl.closed);
});

test("concurrent sse streams stay independent", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "sse", id: 1, path: "/api/events" });
  await view.webview._msgHandler({ type: "sse", id: 2, path: "/api/events" });
  server.streams[0].handlers.onEvent({ data: "one" });
  server.streams[1].handlers.onEvent({ data: "two" });
  assert.ok(view.webview.posts.find((m) => m.id === 1 && m.data === "one"));
  assert.ok(view.webview.posts.find((m) => m.id === 2 && m.data === "two"));
});

test("ready message answers with theme + dashboard version", async () => {
  const { provider } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "ready" });
  const hello = view.webview.posts.find((m) => m.type === "hello");
  assert.equal(hello.theme, "dark");
  assert.equal(hello.dashboardVersion, "1.0.2");
});

test("theme changes are pushed to the webview", async () => {
  const vscodeStub = makeVscode();
  const { provider } = await makeProvider(undefined, vscodeStub);
  const view = makeView();
  await provider.resolveWebviewView(view);
  vscodeStub.window._themeListener();
  assert.ok(view.webview.posts.find((m) => m.type === "theme" && m.theme === "dark"));
});

test("refresh posts a refresh message to the webview", async () => {
  const { provider } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  view.webview.posts = [];
  await provider.refresh();
  assert.ok(view.webview.posts.find((m) => m.type === "refresh"));
});

test("disposing the view stops the server and closes streams", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "sse", id: 9, path: "/api/events" });
  view.fireDispose();
  await new Promise((r) => setImmediate(r));
  assert.equal(server.stopped, 1);
  assert.ok(server.streams[0].ctl.closed);
});

test("reload restarts the server and re-renders", async () => {
  const { provider, server } = await makeProvider();
  const view = makeView();
  await provider.resolveWebviewView(view);
  await provider.reload();
  assert.equal(server.stopped, 1);
  assert.equal(server.startCount, 2);
  assert.match(view.webview.html, /bridge\.js/);
});

test("setup actions route to extension commands", async () => {
  const executed = [];
  const vscodeStub = makeVscode({
    commands: {
      executeCommand(id) {
        executed.push(id);
        return Promise.resolve();
      },
    },
  });
  const { provider } = await makeProvider(undefined, vscodeStub);
  const view = makeView();
  await provider.resolveWebviewView(view);
  await view.webview._msgHandler({ type: "setup", action: "configure-cli" });
  await view.webview._msgHandler({ type: "setup", action: "locate-runtime" });
  await view.webview._msgHandler({ type: "setup", action: "open-browser" });
  await view.webview._msgHandler({ type: "setup", action: "retry" });
  assert.deepEqual(executed, [
    "chronovault.configureCli",
    "chronovault.locateRuntime",
    "chronovault.dashboardBrowser",
  ]);
});