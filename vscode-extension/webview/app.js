(() => {
  "use strict";
  const $ = (s, p = document) => p.querySelector(s);
  const $$ = (s, p = document) => [...p.querySelectorAll(s)];
  const api = async (path, opts = {}) => {
    const r = await fetch(path, opts);
    if (!r.ok) throw new Error(`${r.status} ${await r.text()}`);
    return r.json();
  };
  const TS = s => s ? new Date(s).toLocaleString() : "—";
  const TIME = s => s ? new Date(s).toLocaleTimeString() : "";
  const DATED = s => s ? new Date(s).toLocaleDateString() : "";
  const ID = v => (v && v.value) || v || "—";
  const BYTES = b => b < 1024 ? b + " B" : b < 1048576 ? (b / 1024).toFixed(1) + " KB" : b < 1073741824 ? (b / 1048576).toFixed(1) + " MB" : (b / 1073741824).toFixed(2) + " GB";

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ==================== STATE ==================== */
  let state = { checkpoints: [], meta: {}, storage: {}, config: {}, lastVerified: null, recoveries: [], diff: null };
  let selectedCp = null;
  let restoreTarget = null;
  let restorePlan = null;
  let selectedPaths = new Set();
  let evtSource = null;
  let viewMode = "timeline";
  const camera = { zoom: 1, panX: 0 };
  let wizardOpId = null;
  let wizardTerminalOp = false;
  let revealOnNextRender = true;
  let connOk = false;
  let tooltipTimer = null;
  let sseAlive = 0;
  let diagLog = [];
  let diagDone = false;
  let diffData = null;
  let diffPos = 0;
  const MAX_DIFF_ROWS = 250;
  const WIZARD_STAGES = ["PLANNED", "PROTECTING", "PROTECTED", "RESTORING", "VERIFYING", "COMMITTING", "COMPLETED"];
  const WIZARD_ERR = ["ROLLING_BACK", "ROLLED_BACK", "FAILED"];
  const OPS_VERIFY = ["RUN_HEALTH", "health", "verifying", "VERIFYING"];

  /* Directed state machine shown in map mode; edges are the machine's real,
     directional transitions, never inferred causality. Nodes light up only
     when their state is actually observed in the vault data. */
  const MAP_STATES = [
    { key: "VERIFIED", label: "VERIFIED", desc: "last healthy state" },
    { key: "CODE_CHANGED", label: "CODE CHANGED", desc: "moved away from verified" },
    { key: "HEALTH_FAILED", label: "HEALTH FAILED", desc: "verification failed" },
    { key: "DIAGNOSED", label: "DIAGNOSED", desc: "causes triangulated" },
    { key: "PROTECTED", label: "PROTECTED", desc: "protective snapshot taken" },
    { key: "RESTORED", label: "RESTORED", desc: "target state recovered" }
  ];
  const MAP_EDGES = [
    [0, 1], [1, 2], [2, 3], [3, 4], [4, 5], [5, 0]
  ];

  const STATUS_CLASS = s => String(s || "UNVERIFIED").toUpperCase();

  /* ==================== HOST THEME HOOK (IDE webviews) ==================== */
  function applyTheme(t) {
    if (!t) return;
    const themes = ["dark", "light", "high-contrast"];
    if (!themes.includes(t)) return;
    document.documentElement.dataset.theme = t;
    window.dispatchEvent(new CustomEvent("cv:theme", { detail: t }));
  }
  function loadTheme() {
    try {
      const t = localStorage.getItem("cv-theme");
      if (t) { applyTheme(t); return; }
    } catch {}
    const host = window.__CV_HOST_THEME__;
    if (host) applyTheme(host);
  }

  /* ==================== INIT ==================== */
  async function init() {
    await Promise.all([loadMeta(), loadState(), loadConfig(), loadCheckpoints(), loadHistory()]);
    bind();
    startSSE();
    startClock();
    startWatchdog();
    renderWorld();
    renderCheckpoints();
    populateDiffPickers();
    tickHealthRing();
    buildWizardSteps();
    startParticles();
    $$(".modal-backdrop").forEach(m => m.addEventListener("click", e => { if (e.target === m) closeModal(m.id); }));
    $$("[data-close-modal]").forEach(b => b.addEventListener("click", () => closeModal(b.closest(".modal-backdrop").id)));
    document.addEventListener("keydown", onKey);
  }

  async function loadMeta() { try { state.meta = await api("/api/meta"); renderMeta(); } catch (e) { diag("loadMeta: " + e.message); } }
  async function loadConfig() {
    try {
      state.config = await api("/api/config");
      renderConfig();
    } catch (e) { diag("loadConfig: " + e.message); }
  }
  function renderConfig() {
    const c = state.config || {};
    const name = c.adapter || "generic";
    const build = c.buildCommand || (c.testCommand ? c.testCommand : "");
    $("#adapterLine").textContent = name + (build ? " · " + build : "");
    const r = $("#storeRetention");
    if (r) r.textContent = "retention: " + (c.retention || "—") + (c.lastGc ? " · gc: " + c.lastGc : "");
    const w = $("#welcome");
    if (w) {
      const det = w.querySelector(".welcome-detect");
      if (det) det.textContent = "Detected: " + name + (build ? " — build: " + build : "");
    }
  }
  async function loadState() {
    try {
      const s = await api("/api/state");
      state.lastVerified = s.lastVerified;
      state.storage = s.storage;
      state.meta.project = s.project;
      state.meta.projectName = s.projectName;
      if (s.recoveryCount != null) state.meta.recoveryCount = s.recoveryCount;
      renderMeta();
      renderStats();
      renderStorage();
    } catch (e) { diag("loadState: " + e.message); }
  }
  async function loadCheckpoints(animate) {
    try {
      const before = state.checkpoints.length;
      const prev = new Map(state.checkpoints.map(c => [c.id.value, c]));
      state.checkpoints = await api("/api/checkpoints");
      revealOnNextRender = animate || state.checkpoints.length !== before;
      markNewState(prev);
      renderCheckpoints();
      renderWorld();
      populateDiffPickers();
      updateWelcome();
      tickHealthRing();
    } catch (e) { diag("loadCheckpoints: " + e.message); }
  }
  async function loadHistory() {
    try { state.recoveries = await api("/api/history"); } catch (e) { state.recoveries = state.recoveries || []; diag("loadHistory: " + e.message); }
  }
  function markNewState(prev) {
    state.newIds = [];
    if (!prev.size) return;
    for (const c of state.checkpoints) {
      if (!prev.has(c.id.value)) state.newIds.push(c.id.value);
    }
  }

  /* ==================== DIAGNOSTICS / ERROR SCREEN ==================== */
  function diag(msg) {
    diagLog.push({ at: new Date().toISOString(), msg });
    if (diagLog.length > 40) diagLog.shift();
  }

  function showErrorScreen(info) {
    const ov = $("#cvError");
    if (!ov) return;
    const set = (id, v) => { const el = $("#" + id); if (el) el.textContent = v || "—"; };
    set("evComponent", info.component);
    set("evCause", info.cause);
    set("evRuntime", info.runtime);
    set("evSuggest", info.suggested);
    ov.hidden = false;
  }
  function hideErrorScreen() { const ov = $("#cvError"); if (ov) ov.hidden = true; }

  function runtimeInfo() {
    const c = state.config || {};
    const r = [window.location.protocol === "file:" ? "file://" : window.location.host];
    if (c.adapter) r.push("adapter: " + c.adapter);
    if (c.status) r.push("runtime: " + c.status);
    if (c.cli && c.cli.version) r.push("cli: " + c.cli.version);
    if (c.cli && c.cli.path) r.push(c.cli.path);
    return r.join(" · ");
  }

  function openDiagnostics() {
    const grid = $("#diagGrid");
    const logs = $("#diagLogs");
    if (!grid || !logs) return;
    const rows = [
      ["Dashboard version", document.querySelector("#version") ? $("#version").textContent : "?"],
      ["Host", window.location.protocol === "file:" ? "file:// (local/IDE resource)" : window.location.host],
      ["Runtime", runtimeInfo()],
      ["Theme", document.documentElement.dataset.theme || "dark"],
      ["Reduced motion", reducedMotion ? "yes" : "no"],
      ["Checkpoints loaded", state.checkpoints.length],
      ["Last verified", state.lastVerified ? new Date(state.lastVerified.createdAt).toISOString() : "none"],
      ["SSE stream", evtSource ? (evtSource.readyState === evtSource.OPEN ? "open" : "reconnecting") : "off"],
      ["Connections", connOk ? "connected" : "reconnecting…"]
    ];
    grid.innerHTML = rows.map(([k, v]) => `<div class="diag-grid-row"><dt>${esc(k)}</dt><dd>${esc(String(v))}</dd></div>`).join("");
    logs.innerHTML = (diagLog.length ? diagLog.map(l => `<div class="diag-line"><span>${esc(l.at)}</span>${esc(l.msg)}</div>`).join("") : `<p class="muted">No diagnostics recorded.</p>`);
    openModal("diagModal");
  }

  function renderMeta() {
    const m = state.meta;
    $("#tagline").textContent = m.tagline || "Return to the moment your code still worked.";
    $("#projectName").textContent = m.projectName || "—";
    $("#projectPath").textContent = m.project || "—";
    $("#version").textContent = m.version || "1.0.3";
    $("#footProject").textContent = m.project || "";
  }

  function renderStats() {
    const s = state.storage;
    $("#statCheckpoints").textContent = s.checkpoints ?? 0;
    $("#statSnapshots").textContent = s.snapshots ?? 0;
    $("#statObjects").textContent = s.objects ?? 0;
    $("#statPhys").textContent = BYTES(s.physicalBytes ?? 0);
    $("#statProtected").textContent = s.protectedStates ?? 0;
    const rec = state.meta.recoveryCount != null ? state.meta.recoveryCount : (s.recoveryHistory ?? 0);
    $("#statRecovery").textContent = rec;
    const logical = s.logicalBytes ?? 0;
    const physical = s.physicalBytes ?? 0;
    const ratio = logical > 0 ? ((1 - physical / logical) * 100).toFixed(0) : 0;
    $("#statDedup").textContent = ratio + "%";
    const lv = state.lastVerified;
    $("#lastVerified").textContent = lv ? TIME(lv.createdAt) + " · " + (lv.label || ID(lv.id).slice(-8)) : "none";
  }

  function renderStorage() {
    const s = state.storage || {};
    const logical = s.logicalBytes || 0;
    const physical = s.physicalBytes || 0;
    const physPct = logical > 0 ? Math.min(100, Math.round(physical / logical * 100)) : 0;
    const saved = Math.max(0, logical - physical);
    const savedPct = logical > 0 ? Math.min(100, Math.max(0, Math.round(saved / logical * 100))) : 0;
    const fill = n => `min(${n}%, 100%)`;
    const phys = $("#storePhysFill");
    const sav = $("#storeSavedFill");
    if (!phys) return;
    phys.style.width = fill(physPct);
    sav.style.width = fill(physPct + savedPct);
    const p = $("#storePhysicalSize");
    const l = $("#storeLogicalSize");
    if (p) p.textContent = BYTES(physical);
    if (l) l.textContent = BYTES(logical);
  }

  function updateWelcome() {
    const has = state.checkpoints.length > 0;
    const w = $("#welcome");
    if (w) w.classList.toggle("open", !has);
  }

  function setHealthState(mode, label) {
    const el = $("#healthState");
    if (!el) return;
    const next = ["healthy", "broken", "verifying", "idle"].includes(mode) ? mode : "idle";
    el.dataset.state = next;
    if ($("#healthStateText")) $("#healthStateText").textContent = (label || next.toUpperCase());
    const ring = $("#healthRing");
    if (ring) ring.dataset.state = next === "verifying" ? "verifying" : next === "healthy" ? "healthy" : next === "broken" ? "broken" : "";
  }

  function tickHealthRing() {
    const lv = state.lastVerified;
    const pct = lv && lv.status === "VERIFIED" ? 100 : lv ? 50 : 0;
    const circ = 314;
    const offset = circ - (circ * pct / 100);
    const ring = $("#healthRing");
    if (!ring) return;
    ring.style.strokeDashoffset = offset;
    ring.style.stroke = "";
    $("#healthPct").textContent = pct + "%";
    const label = pct === 100 ? "HEALTHY" : pct > 0 ? "PARTIAL" : "NO DATA";
    $("#healthLabel").textContent = label;
    setHealthState(pct === 100 ? "healthy" : pct > 0 ? "idle" : "idle", label);
  }

  /* ==================== TEMPORAL WORLD (timeline + map) ==================== */
  const CX = 96;
  const BASE_H = 92;

  function sortedCps() { return [...state.checkpoints].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt)); }
  function currentCp() {
    const cps = state.checkpoints;
    if (!cps.length) return null;
    return cps.reduce((m, c) => new Date(c.createdAt) >= new Date(m.createdAt) ? c : m);
  }
  function totalWorld() {
    const n = Math.max(1, state.checkpoints.length);
    return 40 + (n - 1) * CX + 40;
  }

  function layout(i, n) {
    return { x: 40 + i * CX, y: BASE_H / 2 };
  }

  /* ==================== MAP MODE (directed state machine) ==================== */
  function mapRealized(cps) {
    const sorted = sortedCps();
    const lv = state.lastVerified;
    const latest = sorted.length ? sorted[sorted.length - 1] : null;
    const healthHistory = (state.recoveries || []);
    const protectedSeen = healthHistory.some(r =>
      (r.protectiveSnapshot && r.protectiveSnapshot.id) || r.progress && r.progress >= 1) ||
      healthHistory.some(r => !!(r.targetCheckpoint));
    const restored = healthHistory.some(r => r.stage === "COMPLETED");
    const brokenSeen = cps.some(c => STATUS_CLASS(c.status) === "BROKEN");
    const healthySeen = !!lv || cps.some(c => STATUS_CLASS(c.status) === "VERIFIED");
    const moved = cps.length > 0 && (!lv || !(latest && lv.id && latest.id.value === lv.id.value && STATUS_CLASS(latest.status) === "VERIFIED"));
    const notHealthy = cps.length > 0 && (!healthySeen || brokenSeen);
    const flags = [healthySeen, moved, notHealthy, diagDone, protectedSeen, restored];
    const onIdx = [];
    for (let i = 0; i < flags.length; i++) if (flags[i]) onIdx.push(i);
    const firstOn = onIdx.length ? onIdx[onIdx.length - 1] : -1;
    return { onIdx, activeIdx: firstOn >= 0 && firstOn < MAP_STATES.length - 1 ? firstOn + 1 : -1 };
  }

  function renderMapWorld() {
    const svg = $("#timelineSvg");
    const world = $("#tlWorld");
    if (!svg || !world) return;
    const cps = sortedCps();
    const vw = Math.max(viewportWidth(), 320);
    const W = Math.max(vw / camera.zoom, 520);
    const H = BASE_H + 26;
    svg.setAttribute("height", H);
    svg.setAttribute("viewBox", `0 0 ${W} ${H}`);
    const cx = W / 2;
    const cy = H / 2 + 4;
    const rx = Math.min(W / 2 - 96, 300);
    const ry = 42;
    const n = MAP_STATES.length;
    const realized = mapRealized(cps);
    const anim = !reducedMotion;

    let defs = `<marker id="mapArrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" class="map-arrow"/></marker>`;
    let edges = "";
    for (const [a, b] of MAP_EDGES) {
      const pa = { x: cx + rx * Math.cos(Math.PI / 2 + Math.PI * 2 * a / n), y: cy + ry * Math.sin(Math.PI / 2 + Math.PI * 2 * a / n) };
      const pb = { x: cx + rx * Math.cos(Math.PI / 2 + Math.PI * 2 * b / n), y: cy + ry * Math.sin(Math.PI / 2 + Math.PI * 2 * b / n) };
      const mx = (pa.x + pb.x) / 2, my = (pa.y + pb.y) / 2;
      const ox = my - cy, oy = cx - mx;
      const len = Math.hypot(ox, oy) || 1;
      const c = { x: mx + (ox / len) * 22, y: my + (oy / len) * 22 };
      const active = realized.activeIdx === b && anim;
      edges += `<path class="map-edge ${active ? "map-edge-active" : ""}" d="M${pa.x} ${pa.y} Q${c.x} ${c.y} ${pb.x} ${pb.y}" marker-end="url(#mapArrow)"/>`;
    }

    let nodes = "";
    for (let i = 0; i < n; i++) {
      const s = MAP_STATES[i];
      const p = { x: cx + rx * Math.cos(Math.PI / 2 + Math.PI * 2 * i / n), y: cy + ry * Math.sin(Math.PI / 2 + Math.PI * 2 * i / n) };
      const on = realized.onIdx.includes(i);
      const activeNode = realized.activeIdx === i;
      nodes += `<g class="map-node ${on ? "on" : "off"} ${activeNode ? "active" : ""}" data-status="${s.key}" transform="translate(${p.x} ${p.y})">`;
      if (activeNode && anim) nodes += `<circle class="map-node-pulse" r="12"/>`;
      nodes += `<circle class="map-node-ring" r="12"/>`;
      nodes += `<circle class="map-node-core" r="4.5"/>`;
      nodes += `</g>
        <text class="map-label ${on ? "on" : "off"} ${activeNode ? "active" : ""}" x="${p.x}" y="${p.y + 30}" text-anchor="middle">${esc(s.label)}</text>
        <text class="map-sub" x="${p.x}" y="${p.y + 42}" text-anchor="middle">${esc(s.desc)}</text>`;
    }

    world.innerHTML = defs + edges + nodes;
    $("#tlGrid").innerHTML = "";
    $("#tlCursor").hidden = true;
    $("#timelineEmpty").hidden = true;
  }

  function fit() {
    const vw = viewportWidth();
    const w = totalWorld();
    camera.zoom = Math.min(1, vw / Math.max(w, 1));
    camera.panX = 0;
    renderWorld();
  }

  function clampCam() {
    const vw = viewportWidth();
    const w = totalWorld();
    const maxPan = Math.max(0, w - vw / camera.zoom);
    camera.panX = Math.min(Math.max(camera.panX, 0), maxPan);
    camera.zoom = Math.min(Math.max(camera.zoom, 0.15), 8);
  }

  function viewportWidth() { return Math.max($("#timelineViewport").clientWidth || 600, 200); }
  function screenX(wx) { return (wx - camera.panX) * camera.zoom; }

  function lodStep() {
    const sp = CX * camera.zoom;
    return Math.max(1, Math.round(58 / sp));
  }

  function statusIcon(status) {
    switch (STATUS_CLASS(status)) {
      case "VERIFIED": return "✓";
      case "BROKEN": return "✗";
      case "ACTIVE": return "◉";
      default: return "•";
    }
  }

  function renderWorld() {
    const svg = $("#timelineSvg");
    const empty = $("#timelineEmpty");
    const world = $("#tlWorld");
    if (!svg || !world) return;
    if (viewMode === "map") {
      updateWelcome();
      renderMapWorld();
      return;
    }
    const cps = sortedCps();
    if (!cps.length) {
      world.innerHTML = "";
      $("#tlCursor").hidden = true;
      hideTooltip();
      if (empty) { empty.hidden = false; }
      updateWelcome();
      return;
    }
    empty.hidden = true;
    updateWelcome();
    svg.setAttribute("height", BASE_H + 26);
    clampCam();
    const vw = viewportWidth();
    svg.setAttribute("viewBox", `0 0 ${vw} ${BASE_H + 26}`);

    const x0 = camera.panX - 120;
    const x1 = camera.panX + vw / camera.zoom + 120;
    const step = lodStep();
    const selIdx = selectedCp ? cps.findIndex(c => c.id.value === selectedCp.id.value) : -1;
    const cur = currentCp();
    const recoveredIds = new Set((state.recoveries || [])
      .filter(r => r.stage === "COMPLETED" && r.targetCheckpoint)
      .map(r => (r.targetCheckpoint && r.targetCheckpoint.value) || r.targetCheckpoint));

    let gx = [];
    for (let i = 0; i < cps.length; i++) {
      const p = layout(i, cps.length);
      if (p.x < x0 || p.x > x1) continue;
      const cp = cps[i];
      const labelOn = i % step === 0 && (i === 0 || i === cps.length - 1 || step === 1);
      let label = "";
      let sub = "";
      if (viewMode === "map") {
        sub = labelOn ? TIME(cp.createdAt) : "";
      } else if (camera.zoom < 0.55) {
        if (labelOn) label = DATED(cp.createdAt);
      } else if (camera.zoom < 1.6) {
        if (labelOn) { label = (cp.label || ID(cp.id).slice(-6)); sub = TIME(cp.createdAt); }
      } else {
        if (labelOn) { label = (cp.label || ID(cp.id).slice(-6)); sub = new Date(cp.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: i % 2 ? 0 : 1 }); }
      }
      gx.push({ p, cp, label, sub });
    }

    let grid = "";
    const startI = Math.max(0, Math.floor(x0 / CX));
    const endI = Math.min(cps.length - 1, Math.ceil(x1 / CX));
    for (let i = startI; i <= endI; i++) {
      const p = layout(i, cps.length);
      grid += `<line class="tl-tick" x1="${p.x}" y1="10" x2="${p.x}" y2="${BASE_H - 12}"/>`;
    }
    $("#tlGrid").innerHTML = grid;

    const lineStart = { x: 40, y: BASE_H / 2 };
    const lineEnd = { x: 40 + (cps.length - 1) * CX, y: BASE_H / 2 };
    let body = `<line class="tl-line" x1="${lineStart.x}" y1="${lineStart.y}" x2="${lineEnd.x}" y2="${lineEnd.y}"/>`;

    if (selNodeOf(selIdx, cps) && viewMode === "timeline" && selIdx > 0) {
      const sp = layout(selIdx, cps.length);
      body += `<line class="tl-guide" x1="${lineStart.x}" y1="${lineStart.y}" x2="${sp.x}" y2="${sp.y}"/>`;
    }

    for (let i = 0; i < gx.length; i++) {
      const { p, cp, label, sub } = gx[i];
      const status = STATUS_CLASS(cp.status || "UNVERIFIED");
      const isSel = selectedCp && cp.id.value === selectedCp.id.value;
      const isCur = cur && cp.id.value === cur.id.value;
      const isRes = recoveredIds.has(cp.id.value);
      const isNew = (state.newIds || []).includes(cp.id.value);
      const r = status === "VERIFIED" ? 4.2 : status === "BROKEN" ? 4.6 : 4;
      const haloS = isCur ? "ACTIVE" : status;
      if (isCur) body += `<circle class="tl-active-pulse" cx="${p.x}" cy="${p.y}" r="9"/>`;
      if (isNew) body += `<circle class="tl-new-pulse" cx="${p.x}" cy="${p.y}" r="12"/>`;
      if (status === "BROKEN") body += `<circle class="tl-disrupt" cx="${p.x}" cy="${p.y}" r="7.5"/>`;
      body += `<g class="tl-node ${isCur ? "tl-current " : ""}${isRes ? "tl-recovered " : ""}${isNew ? "tl-new " : ""}tl-status-${status}" data-cpid="${cp.id.value}" transform="translate(${p.x} ${p.y})">`;
      if (isSel) body += `<circle class="tl-ring" cx="0" cy="0" r="11" fill="none" stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="3 3" opacity="1"/>`;
      body += `<circle class="tl-halo tl-halo-${haloS}" cx="0" cy="0" r="9"/>`;
      body += `<circle class="tl-dot tl-status-${status}" cx="0" cy="0" r="${r}"/>`;
      if (isSel) body += `<circle cx="0" cy="0" r="13" fill="none" stroke="var(--accent)" stroke-width="1.2" opacity=".5" stroke-dasharray="4 3"/>`;
      body += `<text class="tl-node-icon" x="0" y="1.5">${esc(statusIcon(status))}</text>`;
      if (isRes) body += `<text class="tl-rec-glyph" x="9" y="-8" font-size="8" text-anchor="middle" font-weight="900">↺</text>`;
      if (cp.pinned) body += `<circle cx="9" cy="-8" r="1.8" fill="var(--magenta)"/>`;
      body += `</g>`;
      if (label || sub) {
        body += `<text class="tl-label" x="${p.x}" y="${BASE_H + 14}" text-anchor="middle">${esc(label)}</text>`;
        if (sub) body += `<text class="tl-label" x="${p.x}" y="${BASE_H + 24}" text-anchor="middle">${esc(sub)}</text>`;
      }
    }

    world.innerHTML = body;
    world.classList.toggle("tl-reveal", revealOnNextRender);
    world.setAttribute("transform", `translate(${-camera.panX * camera.zoom} 0) scale(${camera.zoom})`);
    revealOnNextRender = false;

    if (selIdx >= 0) {
      const sp = layout(selIdx, cps.length);
      const curLine = $("#tlCursor");
      curLine.setAttribute("x1", screenX(sp.x)); curLine.setAttribute("x2", screenX(sp.x));
      curLine.hidden = false;
    } else $("#tlCursor").hidden = true;

    $$(".tl-node", world).forEach(nEl => {
      nEl.addEventListener("click", () => {
        const id = nEl.dataset.cpid;
        selectCheckpoint(id);
        showInspector();
      });
      nEl.addEventListener("mouseenter", e => {
        e.stopPropagation();
        showTooltip(idOf(nEl.dataset.cpid), nEl);
      });
      nEl.addEventListener("mouseleave", hideTooltipLater);
      nEl.addEventListener("focus", () => { selectCheckpoint(nEl.dataset.cpid); showTooltip(idOf(nEl.dataset.cpid), nEl); });
      nEl.addEventListener("keydown", ev => {
        if (ev.key === "Enter") { selectCheckpoint(nEl.dataset.cpid); showInspector(); }
      });
      nEl.setAttribute("tabindex", "0");
      nEl.setAttribute("role", "button");
      nEl.setAttribute("aria-label", checkpointAria(idOf(nEl.dataset.cpid)));
    });
  }

  function selNodeOf(idx, cps) {
    if (idx < 0 || !cps.length) return null;
    return cps[idx];
  }

  function idOf(cp) { return cp ? (cp.value || cp) : ""; }
  function cpById(id) { return state.checkpoints.find(c => c.id.value === id) || null; }
  function checkpointAria(cp) {
    const c = state.checkpoints.find(x => x.id.value === idOf(cp));
    if (!c) return "";
    return `Checkpoint ${ID(c.id)} — ${STATUS_CLASS(c.status)} — ${TS(c.createdAt)}. Press Enter for details.`;
  }

  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  /* ==================== HOVER TOOLTIP ==================== */
  function showTooltip(cpId, nodeEl) {
    hideTooltip();
    const cp = cpById(cpId);
    if (!cp) return;
    const tip = $("#tlTip");
    if (!tip) return;
    const hr = cp.evidence && cp.evidence.healthResult;
    const pass = hr ? (hr.checks || []).filter(c => c.status === "PASS").length : null;
    const total = hr ? (hr.checks || []).length : null;
    const status = STATUS_CLASS(cp.status || "UNVERIFIED");
    tip.innerHTML =
      `<div class="tt-row"><b>${esc(cp.label || "checkpoint")}</b><span class="tt-status tt-${esc(status)}">${esc(status)}</span></div>` +
      `<div class="tt-id">${esc(ID(cp.id))}</div>` +
      `<div class="tt-row"><span>${esc(TS(cp.createdAt))}</span></div>` +
      (pass !== null ? `<div class="tt-row"><span>verification</span><b>${pass}/${total} checks</b></div>` : "") +
      (cp.pinned ? `<div class="tt-row"><span>protection</span><b>pinned</b></div>` : "");
    tip.hidden = false;
    requestAnimationFrame(() => tip.classList.add("on"));
    const vp = $("#timelineViewport");
    const rect = vp.getBoundingClientRect();
    const nodeRect = nodeEl.getBoundingClientRect();
    const tipW = tip.offsetWidth || 160;
    let left = nodeRect.left - rect.left - tipW / 2 + nodeRect.width / 2;
    left = Math.max(6, Math.min(left, rect.width - tipW - 6));
    const top = nodeRect.top - rect.top - tip.offsetHeight - 8;
    tip.style.left = left + "px";
    tip.style.top = Math.max(4, top) + "px";
  }
  function hideTooltipLater() {
    if (tooltipTimer) clearTimeout(tooltipTimer);
    tooltipTimer = setTimeout(hideTooltip, 140);
  }
  function hideTooltip() {
    if (tooltipTimer) clearTimeout(tooltipTimer);
    const tip = $("#tlTip");
    if (!tip) return;
    tip.classList.remove("on");
    tip.hidden = true;
  }

  /* ==================== CHECKPOINT INSPECTOR ==================== */
  function showInspector() {
    const box = $("#inspector");
    if (!selectedCp) { box.classList.remove("open"); return; }
    const cp = selectedCp;
    const ev = cp.evidence;
    const hr = ev && ev.healthResult;
    const checkList = (hr && hr.checks) || [];
    const passCount = checkList.filter(c => c.status === "PASS").length;
    const checks = checkList.map(c => `<span class="${c.status === "PASS" ? "ok" : "bad"}">${esc(c.name || "check")}</span>`).join("");
    const checkDetails = checkList.map(c => `
      <div class="insp-check" data-status="${esc(c.status)}">
        <span class="insp-check-name">${esc(c.name || "check")} <span class="insp-check-status ${c.status === "PASS" ? "ok" : "bad"}">${esc(c.status)}</span></span>
        ${c.exitCode != null ? `<span class="insp-check-meta">exit ${esc(c.exitCode)}</span>` : ""}
        ${c.durationMs != null ? `<span class="insp-check-meta">${c.durationMs > 1000 ? (c.durationMs / 1000).toFixed(1) + "s" : c.durationMs + "ms"}</span>` : ""}
        ${c.required ? `<span class="insp-check-meta">required</span>` : ""}
        ${c.outputTail ? `<details class="insp-check-tail"><summary>output</summary><pre>${esc(c.outputTail)}</pre></details>` : ""}
        ${c.errorTail ? `<details class="insp-check-tail"><summary>errors</summary><pre class="insp-check-err">${esc(c.errorTail)}</pre></details>` : ""}
      </div>`).join("");
    const tools = (ev && ev.toolchain && ev.toolchain.tools) ? Object.entries(ev.toolchain.tools).map(([k, v]) =>
      `<div class="insp-row"><span>${esc(k)}</span><span class="insp-chip">${esc(String(v))}</span></div>`).join("") : `<div class="insp-row"><span>none recorded</span><span>—</span></div>`;
    const isRes = (state.recoveries || []).some(r => r.stage === "COMPLETED" && ((r.targetCheckpoint && r.targetCheckpoint.value) || r.targetCheckpoint) === cp.id.value);
    box.innerHTML = `
      <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <h4 style="font-size:.8rem;letter-spacing:1px;text-transform:uppercase">Checkpoint ${ID(cp.id).slice(-10)}</h4>
        <span class="badge badge-${esc(STATUS_CLASS(cp.status))}">${esc(STATUS_CLASS(cp.status))}</span>
        ${isRes ? `<span class="badge" style="background:rgba(255,77,157,.12);color:var(--magenta);border:1px solid rgba(255,77,157,.3)">↺ recovered</span>` : ""}
        ${cp.pinned ? `<span class="pin-icon" title="Pinned">● pinned</span>` : ""}
        ${cp.label ? `<span class="time-chip">${esc(cp.label)}</span>` : ""}
        <span class="time-val">${TS(cp.createdAt)}</span>
        <span style="flex:1"></span>
        <button class="btn" id="inspDiff"><span class="btn-icon">⇔</span> Compare</button>
        <button class="btn btn-danger" id="inspRestore"><span class="btn-icon">⇦</span> Restore to this</button>
      </div>
      <div class="inspector-grid">
        <div class="insp-box"><h4>Health evidence</h4>
          <div class="insp-row"><span>profile</span><span class="insp-chip">${esc(ev ? ev.healthProfileName || "—" : "—")}</span></div>
          <div class="insp-row"><span>overall</span><span>${(hr && hr.overallPass) ? '<span class="ok">PASS</span>' : '<span class="bad">FAIL</span>'}</span></div>
          <div class="insp-row"><span>checks</span><span>${hr ? passCount + "/" + checkList.length : "—"}</span></div>
          <div class="insp-row"><span>duration</span><span>${hr ? (hr.totalDurationMs > 1000 ? (hr.totalDurationMs / 1000).toFixed(1) + "s" : hr.totalDurationMs + "ms") : "—"}</span></div>
          <div class="health-mini">${checks || ""}</div>
          ${checkDetails ? `<details class="insp-checks"><summary>per-check detail</summary><div class="insp-check-list">${checkDetails}</div></details>` : ""}
        </div>
        <div class="insp-box"><h4>Toolchain fingerprint</h4>${tools}</div>
      </div>`;
    box.classList.add("open");
    $("#inspRestore").addEventListener("click", () => openRestoreModal(cp.id.value));
    $("#inspDiff").addEventListener("click", () => { openModal("diffModal"); $("#diffTo").value = cp.id.value; });
  }

  /* ==================== CHECKPOINTS TABLE ==================== */
  function renderCheckpoints() {
    const cps = [...state.checkpoints].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    const body = $("#cpBody");
    if (!body) return;
    if (!cps.length) { body.innerHTML = `<tr><td colspan="6" class="muted">None recorded.</td></tr>`; }
    else {
      body.innerHTML = cps.map(cp => `
        <tr data-rowcp="${cp.id.value}" style="cursor:pointer">
          <td class="cp-id" title="${ID(cp.id)}">${ID(cp.id).slice(-10)}</td>
          <td>${esc(cp.label || "—")}</td>
          <td class="time-val" title="${TS(cp.createdAt)}">${TIME(cp.createdAt)}</td>
          <td><span class="badge badge-${esc(STATUS_CLASS(cp.status))}">${esc(STATUS_CLASS(cp.status))}</span></td>
          <td>${cp.gitBranch ? `<span class="git-branch">${esc(cp.gitBranch)}</span>` : "—"}</td>
          <td>
            <span class="cp-actions">
              ${cp.pinned ? '<span class="pin-icon" title="Pinned">●</span>' : ""}
              <button data-action="diff" data-cpid="${cp.id.value}" title="Compare…">⇔</button>
              <button data-action="restore" data-cpid="${cp.id.value}" title="Restore to this checkpoint">⇦</button>
            </span>
          </td>
        </tr>`).join("");
    }
    $("#cpCount").textContent = cps.length;
    $$(".cp-actions button", body).forEach(b => b.addEventListener("click", () => onCpAction(b.dataset.action, b.dataset.cpid)));
    $$("[data-rowcp]", body).forEach(tr => tr.addEventListener("click", e => {
      if (e.target.closest("button") || e.target.closest(".cp-actions")) return;
      selectCheckpoint(tr.dataset.rowcp); showInspector();
    }));
  }

  function selectCheckpoint(id) {
    selectedCp = state.checkpoints.find(c => c.id.value === id) || null;
    renderWorld();
  }

  function onCpAction(action, cpid) {
    if (action === "restore") openRestoreModal(cpid);
    if (action === "diff") { openModal("diffModal"); $("#diffTo").value = cpid; }
  }

  /* ==================== DIFF ==================== */
  function populateDiffPickers() {
    const cps = sortedCps();
    const opts = cps.map(c => `<option value="${c.id.value}">${TIME(c.createdAt)} · ${c.label || ID(c.id).slice(-8)} [${STATUS_CLASS(c.status)}]</option>`).join("");
    const df = $("#diffFrom"), dt = $("#diffTo");
    if (df) df.innerHTML = `<option value="">— select —</option>${opts}`;
    if (dt) dt.innerHTML = `<option value="">— select —</option>${opts}`;
  }

  async function runDiff() {
    const from = $("#diffFrom").value;
    const to = $("#diffTo").value;
    if (!from || !to) { toast("Select both checkpoints.", "warn"); return; }
    try {
      const d = await api(`/api/diff?from=${from}&to=${to}`);
      diffData = d;
      diffPos = 0;
      renderDiff();
    } catch (e) { toast("Diff failed: " + e.message, "err"); diag("runDiff: " + e.message); }
  }

  function renderDiff() {
    const d = diffData;
    const body = $("#diffBody");
    if (!d || !body) return;
    const changes = d.changes || [];
    const total = d.added + d.modified + d.deleted + d.renamed;
    if (total === 0) { body.innerHTML = `<p class="muted">No changes between these checkpoints.</p>`; return; }
    const slice = changes.slice(diffPos, diffPos + MAX_DIFF_ROWS);
    const more = diffPos + MAX_DIFF_ROWS < changes.length;
    body.innerHTML = `
      <div class="diff-summary">
        <span class="diff-ADDED">+${d.added} added</span>
        <span class="diff-MODIFIED">~${d.modified} modified</span>
        <span class="diff-DELETED">-${d.deleted} deleted</span>
        <span class="diff-RENAMED">↻${d.renamed} renamed</span>
      </div>
      <div class="diff-rows">
        ${slice.map((c, i) => `<div class="diff-row" style="animation-delay:${Math.min(i * 12, 320)}ms"><span class="diff-kind diff-${c.kind}">${c.kind}</span><span>${esc(c.path)}</span></div>`).join("")}
      </div>
      ${more ? `<button class="btn diff-more" id="diffMore"><span class="btn-icon">+</span> Show ${Math.min(MAX_DIFF_ROWS, changes.length - diffPos - MAX_DIFF_ROWS)} more (${changes.length - diffPos - slice.length} left)</button>` : ""}
      <p class="muted diff-foot">${changes.length} total change(s)${more ? " · " + (slice.length + diffPos) + " shown" : ""}</p>`;
    const moreBtn = $("#diffMore");
    if (moreBtn) moreBtn.addEventListener("click", () => { diffPos += MAX_DIFF_ROWS; renderDiff(); });
  }

  /* ==================== RESTORE (selective) ==================== */
  function groupOf(path) {
    const i = path.indexOf("/");
    return i > 0 ? path.slice(0, i) : path;
  }

  async function openRestoreModal(to) {
    openModal("restoreModal");
    const body = $("#restoreBody");
    restoreTarget = null; restorePlan = null; selectedPaths.clear();
    body.innerHTML = `<p class="muted">Computing restore plan…</p>`;
    try {
      const plan = await api(`/api/plan?to=${to || ""}`);
      if (!plan || !plan.targetCheckpointId) throw new Error("no plan returned");
      restoreTarget = (plan.targetCheckpointId.value || plan.targetCheckpointId);
      restorePlan = plan;
      (plan.actions || []).forEach(a => selectedPaths.add(a.path));
      const summaryQty = Object.fromEntries(["PUT", "SYMLINK", "REMOVE"].map(k => [k, (plan.actions || []).filter(a => a.type === k).length]));
      body.innerHTML = `
        <div class="diff-summary" style="margin-bottom:12px">
          <span class="diff-MODIFIED">~${summaryQty.PUT || 0} restored</span>
          <span class="diff-DELETED">-${summaryQty.REMOVE || 0} removed</span>
          <span class="diff-RENAMED">↻${summaryQty.SYMLINK || 0} symlinks</span>
        </div>
        <p style="margin-bottom:8px">Total: ${BYTES(plan.totalBytes)} · ${plan.actions.length} actions</p>
        <p style="font-size:.65rem;color:var(--fg2);margin-bottom:10px">⚠ A protective snapshot of the current state protects everything first. After restore, the whole project is re-verified — if anything fails, automatic rollback returns every file exactly.</p>
        <div class="sel-tools">
          <button id="selAll">Select all</button>
          <button id="selNone">Select none</button>
          <span style="margin-left:auto;font-size:.62rem;color:var(--fg2)" id="selCount"></span>
        </div>
        <div class="sel-groups" id="selGroups"></div>
        <details><summary style="cursor:pointer;color:var(--fg2);font-size:.65rem">View all actions</summary>
          <div style="max-height:180px;overflow-y:auto;margin-top:6px">${plan.actions.slice(0, 120).map(a =>
            `<div class="diff-row"><span class="diff-kind diff-${a.type === 'REMOVE' ? 'DELETED' : 'ADDED'}">${a.type}</span><span>${esc(a.path || "")}</span></div>`
          ).join("")}${plan.actions.length > 120 ? `<div class="muted" style="padding:4px">…and ${plan.actions.length - 120} more</div>` : ""}</div>
        </details>`;
      renderSelGroups();
      $("#selAll").addEventListener("click", () => { (plan.actions || []).forEach(a => selectedPaths.add(a.path)); renderSelGroups(); });
      $("#selNone").addEventListener("click", () => { selectedPaths.clear(); renderSelGroups(); });
    } catch (e) { body.innerHTML = `<p style="color:var(--red)">Cannot plan recovery: ${escapeHtml(e.message)}</p>`; restoreTarget = null; }
  }

  function renderSelGroups() {
    const plan = restorePlan;
    const groups = new Map();
    (plan.actions || []).forEach(a => {
      const g = groupOf(a.path);
      if (!groups.has(g)) groups.set(g, []);
      groups.get(g).push(a);
    });
    const total = (plan.actions || []).length;
    const selCount = selectedPaths.size;
    $("#selCount").textContent = `${selCount}/${total} selected`;
    const html = [...groups.entries()].map(([g, acts]) => {
      const allChecked = acts.every(a => selectedPaths.has(a.path));
      return `<div class="sel-group">
        <div class="sel-group-head"><input type="checkbox" data-g="${esc(g)}" ${allChecked ? "checked" : ""}> <span>${esc(g)}</span> <span class="pill">${acts.length}</span></div>
        ${acts.map(a => `<label class="path-row ${selectedPaths.has(a.path) ? "checked" : ""}"><input type="checkbox" data-p="${esc(a.path)}" ${selectedPaths.has(a.path) ? "checked" : ""}> ${esc(a.path)}</label>`).join("")}
      </div>`;
    }).join("");
    $("#selGroups").innerHTML = html || `<p class="muted">No file actions.</p>`;
    $$("#selGroups input[data-g]").forEach(i => i.addEventListener("change", () => {
      const g = i.dataset.g;
      (restorePlan.actions || []).filter(a => groupOf(a.path) === g)
        .forEach(a => i.checked ? selectedPaths.add(a.path) : selectedPaths.delete(a.path));
      renderSelGroups();
    }));
    $$("#selGroups input[data-p]").forEach(i => i.addEventListener("change", () => {
      const p = i.dataset.p;
      i.checked ? selectedPaths.add(p) : selectedPaths.delete(p);
      renderSelGroups();
    }));
  }

  async function confirmRestore() {
    if (!restoreTarget) return;
    const plan = restorePlan;
    const all = (plan.actions || []).map(a => a.path).filter(Boolean);
    const chosen = all.filter(p => selectedPaths.has(p));
    if (chosen.length === 0) {
      toast("No files selected — recovery cancelled.", "warn");
      return;
    }
    closeModal("restoreModal");
    const pathsQ = chosen.length < all.length ? "&paths=" + encodeURIComponent(chosen.join(",")) : "";
    openWizard(plan.targetCheckpointId.value);
    try {
      const res = await api(`/api/recover?to=${restoreTarget}&verify=true${pathsQ}`, { method: "POST" });
      if (res && res.operationId) wizardOpId = res.operationId;
      else driveWizard({ stage: "FAILED", message: "No operation id returned" });
    } catch (e) {
      driveWizard({ stage: "FAILED", message: e.message });
    }
  }

  /* ==================== RECOVERY WIZARD ==================== */
  function buildWizardSteps() {
    const el = $("#wizardSteps");
    if (el) el.innerHTML = WIZARD_STAGES.map((n, i) =>
      `<div class="wizard-step" data-ws="${n}"><span class="step-node">${i + 1}</span><span>${n}</span></div>`).join("");
  }
  function openWizard(targetLabel) {
    wizardOpId = null;
    wizardTerminalOp = false;
    const shell = $("#wizardShell");
    shell.classList.add("open");
    shell.setAttribute("aria-hidden", "false");
    $("#wizardTarget").textContent = targetLabel || "—";
    $("#wizardResult").hidden = true; $("#wizardResult").className = "wizard-result";
    $("#wizardMsg").textContent = "Protecting current state…";
    $("#wizardBar").style.width = "0%";
    $("#wizardClose").hidden = true;
    $$("#wizardSteps .wizard-step").forEach(s => { s.classList.remove("done", "active", "err"); });
  }
  function closeWizard() {
    $("#wizardShell").classList.remove("open");
    $("#wizardShell").setAttribute("aria-hidden", "true");
    wizardOpId = null;
  }

  function driveWizard(upd) {
    const stage = (upd.stage || "UNKNOWN").toUpperCase();
    const msg = esc(upd.message || "");
    if (wizardTerminalOp) return;
    if (stage !== "UNKNOWN" && msg) $("#wizardMsg").textContent = msg;
    const idx = WIZARD_STAGES.indexOf(stage);
    const steps = $$("#wizardSteps .wizard-step");
    const bar = $("#wizardBar");
    if (idx >= 0) {
      steps.forEach((s, i) => {
        s.classList.toggle("done", i < idx);
        s.classList.toggle("active", i === idx && stage !== "COMPLETED");
        s.classList.remove("err");
      });
      bar.style.width = Math.round(((idx) / (WIZARD_STAGES.length - 1)) * 100) + "%";
    }
    if (stage === "VERIFYING") setHealthState("verifying", "VERIFYING");
    if (stage === "COMPLETED") {
      steps.forEach(s => { s.classList.remove("active"); s.classList.add("done"); });
      bar.style.width = "100%";
      const res = $("#wizardResult");
      res.textContent = "✓ STATE RESTORED — verification passed";
      res.hidden = false; res.className = "wizard-result ok";
      $("#wizardClose").hidden = false;
      wizardTerminalOp = true;
      refreshAfterOp();
    } else if (WIZARD_ERR.includes(stage) || stage === "FAILED") {
      const cur = steps.find(s => s.classList.contains("active")) || (steps[steps.length - 2]);
      if (cur) cur.classList.add("err");
      const res = $("#wizardResult");
      if (stage === "ROLLING_BACK") {
        res.textContent = "↻ ROLLING BACK…";
        res.hidden = true;
      } else if (stage === "ROLLED_BACK") {
        res.textContent = "✓ ROLLBACK COMPLETE — pre-recovery state restored";
        res.hidden = false; res.className = "wizard-result fail";
        $("#wizardClose").hidden = false;
        wizardTerminalOp = true;
        refreshAfterOp();
      } else if (stage === "CANCELLED") {
        res.textContent = "✗ RECOVERY CANCELLED — nothing was restored";
        res.hidden = false; res.className = "wizard-result fail";
        $("#wizardClose").hidden = false;
        wizardTerminalOp = true;
        refreshAfterOp();
      } else {
        res.textContent = "✗ RECOVERY " + stage;
        res.hidden = false; res.className = "wizard-result fail";
        $("#wizardClose").hidden = false;
        wizardTerminalOp = true;
        refreshAfterOp();
      }
    }
  }

  async function refreshAfterOp() {
    setTimeout(async () => {
      try { await Promise.all([loadState(), loadCheckpoints(true), loadHistory()]); } catch {}
    }, reducedMotion ? 0 : 600);
  }

  /* ==================== ACTIONS ==================== */
  async function runCheckpoint() {
    toast("Creating checkpoint…", "ok");
    setHealthState("verifying", "VERIFYING");
    try {
      await api("/api/checkpoint", { method: "POST" });
      await new Promise(r => setTimeout(r, reducedMotion ? 0 : 800));
      await loadState(); await loadCheckpoints(true);
      toast("Checkpoint created.", "ok");
    } catch (e) { toast("Checkpoint failed: " + e.message, "err"); setHealthState("broken", "CHECKPOINT FAILED"); }
  }

  const runHealthAndCheckpoint = async () => { await runHealth(); await runCheckpoint(); };

  async function runHealth() {
    toast("Running health checks…", "ok");
    setHealthState("verifying", "VERIFYING");
    try {
      const res = await api("/api/health", { method: "POST" });
      toast(res.operationId ? "Health check started." : "Health check complete.", "ok");
    } catch (e) { toast("Health check failed: " + e.message, "err"); setHealthState("broken", "VERIFICATION ERROR"); }
  }

  async function runDiagnose() {
    try {
      const d = await api("/api/diagnose");
      diagDone = true;
      const panel = $("#diagnosisPanel");
      panel.hidden = false;
      const body = $("#diagnosisBody");
      const cards = d.cards || [];
      const kindLabel = { FACT: "FACT", OBSERVATION: "OBSERVATION", HYPOTHESIS: "HYPOTHESIS" };
      body.innerHTML = `
        <div class="diag-headline">
          <span class="badge badge-${esc(d.healthy ? "VERIFIED" : "BROKEN")}">${d.healthy ? "HEALTHY" : "NOT HEALTHY"}</span>
          <span class="diag-summary">${d.lastHealthyCheckpoint ? `Last verified <strong>${esc((d.lastHealthyCheckpoint.value || d.lastHealthyCheckpoint)).slice(-8)}</strong> · ${TS(d.lastHealthyAt)} · <strong>${d.changesSinceHealthy}</strong> change(s) since` : "No verified checkpoint found."}</span>
        </div>
        ${cards.length ? `<div class="card-list">
          ${cards.map(c => `
          <div class="card-item" data-sev="${esc(c.severity)}" data-kind="${esc(c.kind)}">
            <div class="card-kind"><span class="ev-kind ev-${esc(c.kind)}">${esc(kindLabel[c.kind] || c.kind)}</span><span class="ev-sev" data-sev="${esc(c.severity)}">${esc(c.severity)}</span></div>
            <div class="card-title">${esc(c.title)}</div>
            <div class="card-detail">${esc(c.detail || "")}</div>
          </div>`).join("")}
        </div>` : `<p class="muted">No evidence recorded — run a health check first.</p>`}`;
      panel.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth" });
    } catch (e) { toast("Diagnosis failed: " + e.message, "err"); diag("runDiagnose: " + e.message); }
  }

  /* ==================== SSE ==================== */
  function startSSE() {
    try {
      evtSource = new EventSource("/api/events");
      evtSource.onmessage = e => {
        try {
          sseAlive = Date.now();
          setConn(true);
          const upd = JSON.parse(e.data);
          if (!upd.message || upd.message === ": keep-alive") return;
          appendOpLog(upd);
          const uid = upd.operationId && upd.operationId.value;
          const msg = (upd.message || "").toLowerCase();
          if (/verif|health|checking|running/i.test(msg) || /VERIFYING/.test(upd.stage || "")) {
            setHealthState("verifying", "VERIFYING");
          }
          if (upd.stage === "COMPLETED" && /RUN_HEALTH|CREATE_CHECKPOINT|checkpoint/.test(msg || "")) {
            setTimeout(tickHealthRing, 700);
          }
          if (wizardOpId && uid === wizardOpId) driveWizard(upd);
        } catch {}
      };
      evtSource.onerror = () => { setConn(false); };
      evtSource.onopen = () => { sseAlive = Date.now(); setConn(true); };
    } catch (e) { diag("startSSE: " + e.message); }
  }
  function tryRestartSSE() {
    try { if (evtSource) evtSource.close(); } catch {}
    evtSource = null;
    if (window.cvBridge && window.cvBridge.reconnectSSE) { window.cvBridge.reconnectSSE(); return; }
    startSSE();
  }
  function startWatchdog() {
    setInterval(() => {
      const stale = sseAlive > 0 && (Date.now() - sseAlive > 30000);
      if (stale) {
        diag("sse stale — refreshing");
        setConn(false);
        Promise.all([loadState(), loadCheckpoints(), loadHistory()]).catch(() => {});
        tryRestartSSE();
      }
    }, 20000);
  }
  function setConn(ok) {
    connOk = ok;
    const c = $("#conn");
    if (!c) return;
    if (ok) { c.classList.remove("off"); const s = $("span:last-child", c); if (s) s.textContent = "CONNECTED"; }
    else { c.classList.add("off"); const s = $("span:last-child", c); if (s) s.textContent = "RECONNECTING…"; }
  }

  function appendOpLog(upd) {
    const log = $("#opLog");
    if (!log) return;
    if ($(".muted", log)) log.innerHTML = "";
    const li = document.createElement("li");
    li.innerHTML = `<span class="op-stage op-stage-${esc(upd.stage)}">${esc(upd.stage)}</span><span class="op-msg">${esc(upd.message || "…")}</span><span class="op-time">${TIME(upd.timestamp)}</span>`;
    log.prepend(li);
    while (log.children.length > 80) log.lastChild.remove();
  }

  /* ==================== CLOCK ==================== */
  function startClock() {
    const tick = () => { const n = new Date(); const el = $("#clock"); if (el) el.textContent = [n.getHours(), n.getMinutes(), n.getSeconds()].map(v => String(v).padStart(2, "0")).join(":"); };
    tick(); setInterval(tick, 1000);
  }

  /* ==================== PARTICLES ==================== */
  function startParticles() {
    if (reducedMotion) return;
    const cv = $("#fx"); if (!cv) return;
    const ctxv = cv.getContext("2d");
    if (!ctxv) return;
    let W, H;
    const resize = () => {
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      cv.width = Math.floor(innerWidth * dpr);
      cv.height = Math.floor(innerHeight * dpr);
      ctxv.setTransform(dpr, 0, 0, dpr, 0, 0);
      W = innerWidth; H = innerHeight;
    };
    resize(); addEventListener("resize", resize);
    const N = Math.min(60, Math.floor(innerWidth / 24));
    const parts = Array.from({ length: N }, () => ({
      x: Math.random() * innerWidth, y: Math.random() * innerHeight,
      r: Math.random() * 1.6 + .4, vx: (Math.random() - .5) * .12, vy: -Math.random() * .22 - .04,
      c: Math.random() < .7 ? "77,216,255" : "255,77,157", a: Math.random() * .35 + .15
    }));
    let last = 0;
    const loop = t => {
      requestAnimationFrame(loop);
      if (t - last < 40) return; last = t;
      ctxv.clearRect(0, 0, W, H);
      for (const p of parts) {
        p.x += p.vx; p.y += p.vy;
        if (p.y < -10) { p.y = H + 10; p.x = Math.random() * W; }
        if (p.x < -10) p.x = W + 10; if (p.x > W + 10) p.x = -10;
        ctxv.beginPath();
        ctxv.arc(p.x, p.y, p.r, 0, 7);
        ctxv.fillStyle = `rgba(${p.c},${p.a})`;
        ctxv.fill();
      }
    };
    requestAnimationFrame(loop);
  }

  /* ==================== MODALS / COMMAND PALETTE ==================== */
  function openModal(id) { const m = $("#" + id); m.classList.add("open"); m.setAttribute("aria-hidden", "false"); }
  function closeModal(id) { const m = $("#" + id); m.classList.remove("open"); m.setAttribute("aria-hidden", "true"); }

  const commands = [
    { name: "Create Checkpoint", key: "C", fn: runCheckpoint },
    { name: "Run Health Checks", key: "V", fn: runHealth },
    { name: "Diagnose Project", key: "D", fn: runDiagnose },
    { name: "Restore Last Verified", key: "R", fn: () => openRestoreModal("") },
    { name: "Compare Checkpoints (Diff)", fn: () => openModal("diffModal") },
    { name: "Toggle Theme", key: "T", fn: cycleTheme },
  ];

  let filteredCmds = [];

  function openPalette() {
    openModal("cmdPalette");
    const input = $("#paletteInput");
    input.value = "";
    input.focus();
    renderPalette("");
  }
  function closePalette() { closeModal("cmdPalette"); }
  function renderPalette(q) {
    const list = $("#paletteList");
    filteredCmds = commands.filter(c => !q || c.name.toLowerCase().includes(q.toLowerCase()));
    list.innerHTML = filteredCmds.map((c, i) =>
      `<li data-idx="${i}" class="${i === 0 ? 'sel' : ''}">${esc(c.name)}${c.key ? `<span class="cmd-key">${c.key}</span>` : ""}</li>`
    ).join("");
    $$("li", list).forEach(li => li.addEventListener("click", () => { closePalette(); filteredCmds[+li.dataset.idx]?.fn(); }));
  }

  /* ==================== THEME ==================== */
  function cycleTheme() {
    const themes = ["dark", "light", "high-contrast"];
    const cur = document.documentElement.dataset.theme || "dark";
    const next = themes[(themes.indexOf(cur) + 1) % themes.length];
    applyThemeLocal(next);
    toast("Theme: " + next, "ok");
  }
  function applyThemeLocal(t) {
    applyTheme(t);
    try { localStorage.setItem("cv-theme", t); } catch {}
  }

  /* ==================== CAMERA INPUT (no cursor auto-zoom) ==================== */
  function initCamera() {
    const vp = $("#timelineViewport");
    const rect = () => vp.getBoundingClientRect();
    vp.addEventListener("wheel", e => {
      e.preventDefault();
      const mx = e.clientX - rect().left;
      const before = camera.zoom;
      const after = Math.min(Math.max(before * (e.deltaY < 0 ? 1.16 : 1 / 1.16), 0.15), 8);
      const wx = camera.panX + mx / before;
      camera.zoom = after;
      camera.panX = wx - mx / after;
      clampCam();
      renderWorld();
      hideTooltip();
    }, { passive: false });
    const active = new Map();
    let drag = null;
    let pinch = null;
    const endPointers = () => {
      if (active.size < 2) pinch = null;
      if (active.size === 0) { drag = null; vp.classList.remove("dragging"); }
    };
    vp.addEventListener("pointerdown", e => {
      if (e.target.closest(".tl-node")) return;
      active.set(e.pointerId, { x: e.clientX, y: e.clientY });
      try { vp.setPointerCapture(e.pointerId); } catch {}
      if (active.size === 2) drag = null;
      else if (active.size === 1) drag = { x: e.clientX, pan: camera.panX, moved: false };
      vp.classList.add("dragging");
    });
    vp.addEventListener("pointermove", e => {
      if (active.has(e.pointerId)) active.set(e.pointerId, { x: e.clientX, y: e.clientY });
      if (active.size >= 2) {
        const pts = [...active.values()];
        const dist = Math.hypot(pts[0].x - pts[1].x, pts[0].y - pts[1].y);
        if (pinch && pinch.dist > 0) {
          const before = camera.zoom;
          const after = Math.min(Math.max(before * (dist / pinch.dist), 0.15), 8);
          const mx = (pts[0].x + pts[1].x) / 2 - rect().left;
          const wx = camera.panX + mx / before;
          camera.zoom = after;
          camera.panX = wx - mx / after;
          clampCam();
          renderWorld();
          hideTooltip();
        }
        pinch = { dist };
        return;
      }
      if (!drag) return;
      const dx = e.clientX - drag.x;
      camera.panX = drag.pan - dx / camera.zoom;
      drag.moved = drag.moved || Math.abs(dx) > 3;
      clampCam(); renderWorld();
    });
    vp.addEventListener("pointerup", e => { active.delete(e.pointerId); endPointers(); });
    vp.addEventListener("pointercancel", e => { active.delete(e.pointerId); endPointers(); });
  }

  /* ==================== KEYBOARD ==================== */
  function onKey(e) {
    if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") { e.preventDefault(); openPalette(); return; }
    if (e.key === "Escape") { $$(".modal-backdrop.open").forEach(m => closeModal(m.id)); closeWizard(); hideTooltip(); return; }
    const tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea" || tag === "select" || e.target.isContentEditable) return;
    const k = e.key.toLowerCase();
    if (k === "+" || k === "=") { camera.zoom = Math.min(camera.zoom * 1.25, 8); clampCam(); renderWorld(); }
    else if (k === "-") { camera.zoom = Math.max(camera.zoom / 1.25, 0.15); clampCam(); renderWorld(); }
    else if (k === "0") { fit(); }
    else if (k === "f") { fit(); }
    else if (k === "arrowleft" || k === "arrowright") {
      const cps = sortedCps();
      if (!cps.length) return;
      const idx = selectedCp ? cps.findIndex(c => c.id.value === selectedCp.id.value) : -1;
      const ni = k === "arrowright" ? Math.min(idx + 1, cps.length - 1) : Math.max(idx === -1 ? 0 : idx - 1, 0);
      selectCheckpoint(cps[ni].id.value); showInspector();
    }
    else if (k === "r") { openRestoreModal(selectedCp ? selectedCp.id.value : ""); }
    else if (k === "c") { runCheckpoint(); }
    else if (k === "v") { runHealth(); }
    else if (k === "t") { cycleTheme(); }
  }

  /* ==================== TOASTS ==================== */
  function toast(msg, type = "ok") {
    const box = $("#toasts");
    if (!box) return;
    const t = document.createElement("div");
    t.className = `toast ${type}`;
    t.textContent = msg;
    box.appendChild(t);
    setTimeout(() => { t.style.opacity = "0"; setTimeout(() => t.remove(), 300); }, 4000);
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  /* ==================== BIND ==================== */
  function bind() {
    const on = (id, fn) => { const el = $(id); if (el) el.addEventListener("click", fn); };
    on("#themeToggle", cycleTheme);
    on("#cmdPaletteBtn", openPalette);
    on("#btnCheckpoint", runCheckpoint);
    on("#btnHealth", runHealth);
    on("#btnDiagnose", runDiagnose);
    on("#btnRestore", () => openRestoreModal(""));
    on("#btnConfirmRestore", confirmRestore);
    on("#diffRun", runDiff);
    on("#diagClose", () => { const p = $("#diagnosisPanel"); if (p) p.hidden = true; });
    on("#zoomIn", () => { camera.zoom = Math.min(camera.zoom * 1.4, 8); clampCam(); renderWorld(); });
    on("#zoomOut", () => { camera.zoom = Math.max(camera.zoom / 1.4, 0.15); clampCam(); renderWorld(); });
    on("#zoomReset", fit);
    on("#viewTimeline", () => { viewMode = "timeline"; $("#viewTimeline").classList.add("active"); $("#viewMap").classList.remove("active"); renderWorld(); });
    on("#viewMap", () => { viewMode = "map"; $("#viewMap").classList.add("active"); $("#viewTimeline").classList.remove("active"); fit(); renderWorld(); });
    on("#wizardClose", closeWizard);
    on("#btnWelcomeVerify", runHealth);
    on("#btnWelcomeCheckpoint", runHealthAndCheckpoint);
    on("#evRetry", () => { hideErrorScreen(); location.reload(); });
    on("#evReload", () => location.reload());
    on("#evDiagnostics", () => { hideErrorScreen(); openDiagnostics(); });
    const pi = $("#paletteInput");
    if (pi) {
      pi.addEventListener("input", e => renderPalette(e.target.value));
      pi.addEventListener("keydown", e => {
        const sel = $(".sel", $("#paletteList"));
        if (e.key === "ArrowDown" && sel?.nextElementSibling) { sel.classList.remove("sel"); sel.nextElementSibling.classList.add("sel"); }
        if (e.key === "ArrowUp" && sel?.previousElementSibling) { sel.classList.remove("sel"); sel.previousElementSibling.classList.add("sel"); }
        if (e.key === "Enter" && sel) { closePalette(); filteredCmds[+sel.dataset.idx]?.fn(); }
        if (e.key === "Enter" && !sel && filteredCmds.length) { closePalette(); filteredCmds[0]?.fn(); }
      });
    }
    initCamera();
  }

  /* ==================== HOST BRIDGE HOOKS (VS Code webview) ==================== */
  window.cvBridge = {
    api,
    applyThemeLocal,
    setTheme: applyTheme,
    refreshAll: () => Promise.all([loadState(), loadCheckpoints(true), loadHistory()]),
    setHealthState,
    reconnectSSE: () => { if (window.cvReconnectSSE) window.cvReconnectSSE(); }
  };
  window.addEventListener("error", e => {
    diag("uncaught: " + (e && e.message));
    showErrorScreen({
      component: "dashboard",
      cause: (e && e.message) || "unknown error",
      runtime: runtimeInfo(),
      suggested: "Use Reload to rebuild the dashboard, or open Diagnostics for details."
    });
  });
  window.addEventListener("unhandledrejection", e => {
    diag("unhandled rejection: " + (e && e.reason && e.reason.message));
  });
  if (window.__CV_READY__) window.__CV_READY__();

  loadTheme();
  init().catch(e => {
    diag("init: " + e.message);
    showErrorScreen({
      component: "init",
      cause: (e && e.message) || "initialisation failed",
      runtime: runtimeInfo(),
      suggested: "Ensure the ChronoVault CLI is installed and this project is initialized, then Retry."
    });
    throw e;
  });
})();