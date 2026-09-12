"use strict";

const { test } = require("node:test");
const assert = require("node:assert/strict");
const http = require("node:http");
const { EventEmitter } = require("node:events");
const { DashboardServer, findFreePort } = require("./dashboardServer");

function startFakeServer(handler) {
  return new Promise((resolve) => {
    const srv = http.createServer(handler);
    srv.listen(0, "127.0.0.1", () => {
      resolve({ srv, port: srv.address().port });
    });
  });
}

test("findFreePort returns a numeric loopback port", async () => {
  const port = await findFreePort();
  assert.equal(typeof port, "number");
  assert.ok(port > 0);
});

test("request proxies status/body from a local server", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ port }));
  });
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    server.port = port;
    const res = await server.request("/api/state");
    assert.equal(res.status, 200);
    assert.deepEqual(JSON.parse(res.body), { port });
  } finally {
    srv.close();
  }
});

test("request rejects when no server is running", async () => {
  const server = new DashboardServer();
  await assert.rejects(() => server.request("/api/state"), /not running/);
});

test("request supports POST bodies", async () => {
  let received = null;
  const { srv, port } = await startFakeServer((req, res) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      received = { method: req.method, body: Buffer.concat(chunks).toString("utf-8") };
      res.writeHead(200);
      res.end("ok");
    });
  });
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    server.port = port;
    await server.request("/api/x", { method: "POST", body: "hello" });
    assert.equal(received.method, "POST");
    assert.equal(received.body, "hello");
  } finally {
    srv.close();
  }
});

test("openEventStream parses SSE data frames and closes cleanly", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    assert.equal(req.headers.accept, "text/event-stream");
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.write("data: first\n\ndata: second\n\n");
    setTimeout(() => {
      res.write("data: third\n\n");
      res.end();
    }, 30);
  });
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    server.port = port;
    const events = [];
    await new Promise((resolve, reject) => {
      const ctl = server.openEventStream("/api/events", {
        onEvent: (ev) => {
          events.push(ev.data);
          if (events.length === 3) {
            ctl.close();
            resolve();
          }
        },
        onError: reject,
      });
      setTimeout(() => reject(new Error("timeout waiting for events")), 5000);
    }).catch((err) => {
      if (events.length >= 3) return;
      throw err;
    });
    assert.deepEqual(events, ["first", "second", "third"]);
  } finally {
    srv.close();
  }
});

test("openEventStream errors when server not running", async () => {
  const server = new DashboardServer();
  const err = await new Promise((resolve) => {
    server.openEventStream("/api/events", { onError: resolve });
  });
  assert.match(err.message, /not running/);
});

test("start spawns `ui --port N` and waits until /api/state responds", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end("{}");
  });
  const spawned = [];
  const fakeChild = new EventEmitter();
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    const chosen = await server.start({
      cli: "/fake/chronovault",
      projectRoot: "/tmp/proj",
      port,
      spawn: (cli, args) => {
        spawned.push({ cli, args });
        return fakeChild;
      },
    });
    assert.equal(chosen, port);
    assert.equal(spawned[0].cli, "/fake/chronovault");
    assert.deepEqual(spawned[0].args, ["ui", "--port", String(port)]);
    assert.ok(server.running);
    await server.stop();
    assert.ok(!server.running);
  } finally {
    srv.close();
  }
});

test("start rejects when the API never becomes ready", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    res.writeHead(503);
    res.end("not ready");
  });
  const fakeChild = new EventEmitter();
  try {
    const server = new DashboardServer({ timeoutMs: 300 });
    await assert.rejects(
      () =>
        server.start({
          cli: "/fake/chronovault",
          projectRoot: "/tmp/proj",
          port,
          spawn: () => fakeChild,
        }),
      /did not become ready/
    );
    assert.ok(!server.running);
  } finally {
    srv.close();
  }
});

test("start rejects when the child exits before ready and reports the code", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    res.writeHead(503);
    res.end("not ready");
  });
  const fakeChild = new EventEmitter();
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    const startPromise = server.start({
      cli: "/fake/chronovault",
      projectRoot: "/tmp/proj",
      port,
      spawn: () => fakeChild,
    });
    setTimeout(() => fakeChild.emit("exit", 1, null), 20);
    await assert.rejects(startPromise, /child exited code=1/);
  } finally {
    srv.close();
  }
});

test("start rejects with a clear message when cli is missing", async () => {
  const server = new DashboardServer({ timeoutMs: 1000 });
  await assert.rejects(
    () => server.start({ cli: null, projectRoot: "/tmp/proj" }),
    /CLI runtime not configured/
  );
});

test("exit event fires onExit callback but only when not stopping intentionally", async () => {
  const { srv, port } = await startFakeServer((req, res) => {
    res.writeHead(200);
    res.end("{}");
  });
  let exitValue = null;
  const fakeChild = new EventEmitter();
  try {
    const server = new DashboardServer({ timeoutMs: 2000 });
    server.onExit = (code) => (exitValue = code);
    await server.start({
      cli: "/fake/chronovault",
      projectRoot: "/tmp/proj",
      port,
      spawn: () => fakeChild,
    });
    fakeChild.emit("exit", 7, null);
    assert.equal(exitValue, 7);
    assert.ok(!server.running);
  } finally {
    srv.close();
  }
});