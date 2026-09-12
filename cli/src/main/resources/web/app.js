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
  const ID = v => (v && v.value) || v || "—";
  const BYTES = b => b < 1024 ? b + " B" : b < 1048576 ? (b / 1024).toFixed(1) + " KB" : b < 1073741824 ? (b / 1048576).toFixed(1) + " MB" : (b / 1073741824).toFixed(2) + " GB";

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ==================== STATE ==================== */
  let state = { checkpoints: [], meta: {}, storage: {}, lastVerified: null };
  let selectedCp = null;
  let restoreTarget = null;
  let restorePlan = null;
  let selectedPaths = new Set();
  let evtSource = null;
  let viewMode = "timeline";
  const camera = { zoom: 1, panX: 0 };
  let wizardOpId = null;
  let wizardTerminalOp = false;
  const WIZARD_STAGES = ["PLANNED", "PROTECTING", "PROTECTED", "RESTORING", "VERIFYING", "COMMITTING", "COMPLETED"];
  const WIZARD_ERR = ["ROLLING_BACK", "ROLLED_BACK", "FAILED"];

  /* ==================== INIT ==================== */
  async function init() {
    await Promise.all([loadMeta(), loadState(), loadCheckpoints()]);
    bind();
    startSSE();
    startClock();
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

  async function loadMeta() { try { state.meta = await api("/api/meta"); renderMeta(); } catch {} }
  async function loadState() {
    try {
      const s = await api("/api/state");
      state.lastVerified = s.lastVerified;
      state.storage = s.storage;
      state.meta.project = s.project;
      state.meta.projectName = s.projectName;
      renderMeta();
      renderStats();
    } catch {}
  }
  async function loadCheckpoints() {
    try { state.checkpoints = await api("/api/checkpoints"); renderCheckpoints(); renderWorld(); populateDiffPickers(); updateWelcome(); } catch {}
  }

  function renderMeta() {
    const m = state.meta;
    $("#tagline").textContent = m.tagline || "Return to the moment your code still worked.";
    $("#projectName").textContent = m.projectName || "—";
    $("#projectPath").textContent = m.project || "—";
    $("#version").textContent = m.version || "1.0.0";
    $("#footProject").textContent = m.project || "";
  }

  function renderStats() {
    const s = state.storage;
    $("#statCheckpoints").textContent = s.checkpoints ?? 0;
    $("#statSnapshots").textContent = s.snapshots ?? 0;
    $("#statObjects").textContent = s.objects ?? 0;
    $("#statPhys").textContent = BYTES(s.physicalBytes ?? 0);
    $("#statLogical").textContent = BYTES(s.logicalBytes ?? 0);
    const ratio = s.logicalBytes > 0 ? ((1 - s.physicalBytes / s.logicalBytes) * 100).toFixed(0) : 0;
    $("#statDedup").textContent = ratio + "%";
    const lv = state.lastVerified;
    $("#lastVerified").textContent = lv ? TIME(lv.createdAt) + " · " + (lv.label || ID(lv.id).slice(-8)) : "none";
  }

  function updateWelcome() {
    const has = state.checkpoints.length > 0;
    const w = $("#welcome");
    if (w) w.classList.toggle("open", !has);
  }

  function tickHealthRing() {
    const lv = state.lastVerified;
    const pct = lv && lv.status === "VERIFIED" ? 100 : lv ? 50 : 0;
    const circ = 314;
    const offset = circ - (circ * pct / 100);
    const ring = $("#healthRing");
    ring.style.strokeDashoffset = offset;
    ring.style.stroke = pct === 100 ? "var(--green)" : pct > 0 ? "var(--amber)" : "var(--fg2)";
    $("#healthPct").textContent = pct + "%";
    $("#healthLabel").textContent = pct === 100 ? "HEALTHY" : pct > 0 ? "PARTIAL" : "NO DATA";
  }

  /* ==================== TEMPORAL WORLD (timeline + map) ==================== */
  const CX = 96;              // world spacing between checkpoints
  const BASE_H = 80;

  function sortedCps() { return [...state.checkpoints].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt)); }
  function totalWorld() {
    const n = Math.max(1, state.checkpoints.length);
    return 40 + (n - 1) * CX + 40;
  }

  function layout(i, n) {
    if (viewMode === "map") {
      const ang = i * 2.399;             // golden angle spiral
      const r = 18 + i * 4.6;
      return { x: r * Math.cos(ang), y: BASE_H / 2 + r * Math.sin(ang) * 0.55 };
    }
    return { x: 40 + i * CX, y: BASE_H / 2 };
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

  function renderWorld() {
    const svg = $("#timelineSvg");
    const empty = $("#timelineEmpty");
    const world = $("#tlWorld");
    const cps = sortedCps();
    if (!cps.length) {
      if (world) world.innerHTML = "";
      $("#tlCursor").hidden = true;
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

    // visible window in world coords
    const x0 = camera.panX - 120;
    const x1 = camera.panX + vw / camera.zoom + 120;
    const step = lodStep();
    const selIdx = selectedCp ? cps.findIndex(c => c.id.value === selectedCp.id.value) : -1;

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
        if (labelOn) label = new Date(cp.createdAt).toLocaleDateString([]);
      } else if (camera.zoom < 1.6) {
        if (labelOn) { label = (cp.label || ID(cp.id).slice(-6)); sub = TIME(cp.createdAt); }
      } else {
        if (labelOn) { label = (cp.label || ID(cp.id).slice(-6)); sub = new Date(cp.createdAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit", fractionalSecondDigits: i % 2 ? 0 : 1 }); }
      }
      gx.push({ p, cp, label, sub });
    }

    const selNode = selIdx >= 0 ? cps[selIdx] : null;
    const selPos = selNode ? layout(selIdx, cps.length) : null;
    const selScreen = selPos ? screenX(selPos.x) : null;

    // grid: faint vertical ticks every unit
    let grid = "";
    const startI = Math.max(0, Math.floor(x0 / CX));
    const endI = Math.min(cps.length - 1, Math.ceil(x1 / CX));
    for (let i = startI; i <= endI; i++) {
      const p = layout(i, cps.length);
      grid += `<line class="tl-tick" x1="${p.x}" y1="10" x2="${p.x}" y2="${BASE_H - 10}"/>`;
    }

    const lineStart = selIdx >= 0 && viewMode === "timeline"
      ? { x: 40, y: BASE_H / 2 }
      : { x: 40, y: BASE_H / 2 };
    const lineEnd = selIdx >= 0 && viewMode === "timeline"
      ? { x: 40 + (cps.length - 1) * CX, y: BASE_H / 2 }
      : { x: 40 + (cps.length - 1) * CX, y: BASE_H / 2 };
    let body = `<line class="tl-line" x1="${lineStart.x}" y1="${lineStart.y}" x2="${lineEnd.x}" y2="${lineEnd.y}"/>`;
    // recovery trail to selection
    if (selNode) {
      const sp = layout(selIdx, cps.length);
      body += `<path class="tl-guide" d="M ${lineStart.x} ${lineStart.y} L ${screenX(lineStart.x)} 0" style="display:none"/>`;
      if (viewMode === "timeline" && selIdx > 0) {
        body += `<line class="tl-guide" x1="${lineStart.x}" y1="${lineStart.y}" x2="${sp.x}" y2="${sp.y}"/>`;
      }
    }

    for (const { p, cp, label, sub } of gx) {
      const status = cp.status || "UNVERIFIED";
      const isSel = selNode && cp.id.value === selNode.id.value;
      const r = status === "VERIFIED" ? 4.2 : status === "BROKEN" ? 4.6 : 4;
      if (isSel) body += `<circle class="tl-active-ring" cx="${p.x}" cy="${p.y}" r="9"/>`;
      body += `<circle class="tl-dot tl-status-${status}" cx="${p.x}" cy="${p.y}" r="${r}" data-cpid="${cp.id.value}"/>`;
      if (cp.pinned) body += `<circle cx="${p.x}" cy="${p.y}" r="1.8" fill="var(--magenta)"/>`;
      if (label) body += `<text class="tl-label" x="${p.x}" y="${BASE_H + 12}" text-anchor="middle">${esc(label)}</text>`;
      if (sub) body += `<text class="tl-label" x="${p.x}" y="${BASE_H + 22}" text-anchor="middle">${esc(sub)}</text>`;
      if (cp.id.value.startsWith("cp-") && gx.length > 1) {
        // branch-like distinguisher for broken nodes
        if (status === "BROKEN") body += `<circle class="tl-branch-dot" cx="${p.x + 9}" cy="${p.y - 8}" r="1.6"/>`;
      }
    }

    // grid group uses world coords too, transformed together
    $("#tlGrid").setAttribute("transform", `translate(0,0)`);
    world.innerHTML = body;
    world.setAttribute("transform", `translate(${-camera.panX * camera.zoom} 0) scale(${camera.zoom})`);

    if (selScreen != null) {
      const cur = $("#tlCursor");
      cur.setAttribute("x1", selScreen); cur.setAttribute("x2", selScreen);
      cur.hidden = false;
    } else $("#tlCursor").hidden = true;

    $$(".tl-dot").forEach(d => d.addEventListener("click", () => { selectCheckpoint(d.dataset.cpid); showInspector(); }));
  }

  const esc = s => String(s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

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
    const tools = (ev && ev.toolchain && ev.toolchain.tools) ? Object.entries(ev.toolchain.tools).map(([k, v]) =>
      `<div class="insp-row"><span>${esc(k)}</span><span class="insp-chip">${esc(String(v))}</span></div>`).join("") : `<div class="insp-row"><span>none recorded</span><span>—</span></div>`;
    box.innerHTML = `
      <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
        <h4 style="font-size:.8rem;letter-spacing:1px;text-transform:uppercase">Checkpoint ${ID(cp.id).slice(-10)}</h4>
        <span class="badge badge-${esc(cp.status)}">${esc(cp.status)}</span>
        ${cp.pinned ? `<span class="pin-icon" title="Pinned">● pinned</span>` : ""}
        ${cp.label ? `<span class="time-chip">${esc(cp.label)}</span>` : ""}
        <span class="time-val">${TS(cp.createdAt)}</span>
        <span style="flex:1"></span>
        <button class="btn btn-danger" id="inspRestore"><span class="btn-icon">⇦</span> Restore to this</button>
      </div>
      <div class="inspector-grid">
        <div class="insp-box"><h4>Health evidence</h4>
          <div class="insp-row"><span>profile</span><span class="insp-chip">${esc(ev ? ev.healthProfileName || "—" : "—")}</span></div>
          <div class="insp-row"><span>overall</span><span>${(hr && hr.overallPass) ? '<span class="ok">PASS</span>' : '<span class="bad">FAIL</span>'}</span></div>
          <div class="insp-row"><span>checks</span><span>${hr ? passCount + "/" + checkList.length : "—"}</span></div>
          <div class="insp-row"><span>duration</span><span>${hr ? (hr.totalDurationMs > 1000 ? (hr.totalDurationMs / 1000).toFixed(1) + "s" : hr.totalDurationMs + "ms") : "—"}</span></div>
          <div class="health-mini">${checks || ""}</div>
        </div>
        <div class="insp-box"><h4>Toolchain fingerprint</h4>${tools}</div>
      </div>`;
    box.classList.add("open");
    $("#inspRestore").addEventListener("click", () => openRestoreModal(cp.id.value));
  }

  /* ==================== CHECKPOINTS TABLE ==================== */
  function renderCheckpoints() {
    const cps = [...state.checkpoints].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    const body = $("#cpBody");
    if (!cps.length) { body.innerHTML = `<tr><td colspan="6" class="muted">None recorded.</td></tr>`; }
    else {
      body.innerHTML = cps.map(cp => `
        <tr data-rowcp="${cp.id.value}" style="cursor:pointer">
          <td class="cp-id" title="${ID(cp.id)}">${ID(cp.id).slice(-10)}</td>
          <td>${esc(cp.label || "—")}</td>
          <td class="time-val" title="${TS(cp.createdAt)}">${TIME(cp.createdAt)}</td>
          <td><span class="badge badge-${esc(cp.status)}">${esc(cp.status)}</span></td>
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
    if (action === "diff") { openModal("diffModal"); $$("#diffTo")[0].value = cpid; }
  }

  /* ==================== DIFF ==================== */
  function populateDiffPickers() {
    const cps = sortedCps();
    const opts = cps.map(c => `<option value="${c.id.value}">${TIME(c.createdAt)} · ${c.label || ID(c.id).slice(-8)} [${c.status}]</option>`).join("");
    $("#diffFrom").innerHTML = `<option value="">— select —</option>${opts}`;
    $("#diffTo").innerHTML = `<option value="">— select —</option>${opts}`;
  }

  async function runDiff() {
    const from = $("#diffFrom").value;
    const to = $("#diffTo").value;
    if (!from || !to) { toast("Select both checkpoints.", "warn"); return; }
    try {
      const d = await api(`/api/diff?from=${from}&to=${to}`);
      const body = $("#diffBody");
      const total = d.added + d.modified + d.deleted + d.renamed;
      if (total === 0) { body.innerHTML = `<p class="muted">No changes between these checkpoints.</p>`; return; }
      body.innerHTML = `
        <div class="diff-summary">
          <span class="diff-ADDED">+${d.added} added</span>
          <span class="diff-MODIFIED">~${d.modified} modified</span>
          <span class="diff-DELETED">-${d.deleted} deleted</span>
          <span class="diff-RENAMED">↻${d.renamed} renamed</span>
        </div>
        ${(d.changes || []).map(c => `<div class="diff-row"><span class="diff-kind diff-${c.kind}">${c.kind}</span><span>${esc(c.path)}</span></div>`).join("")}`;
    } catch (e) { toast("Diff failed: " + e.message, "err"); }
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
      restoreTarget = plan.targetCheckpointId.value;
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
    } catch (e) { body.innerHTML = `<p style="color:var(--red)">Cannot plan recovery: ${e.message}</p>`; restoreTarget = null; }
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
    $("#wizardSteps").innerHTML = WIZARD_STAGES.map((n, i) =>
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
      try { await Promise.all([loadState(), loadCheckpoints()]); } catch {}
    }, 600);
  }

  /* ==================== ACTIONS ==================== */
  async function runCheckpoint() {
    toast("Creating checkpoint…", "ok");
    try {
      await api("/api/checkpoint", { method: "POST" });
      await new Promise(r => setTimeout(r, 800));
      await loadState(); await loadCheckpoints();
      toast("Checkpoint created.", "ok");
    } catch (e) { toast("Checkpoint failed: " + e.message, "err"); }
  }

  const runHealthAndCheckpoint = async () => { await runHealth(); await runCheckpoint(); };

  async function runHealth() {
    toast("Running health checks…", "ok");
    try {
      const res = await api("/api/health", { method: "POST" });
      toast(res.operationId ? "Health check started." : "Health check complete.", "ok");
    } catch (e) { toast("Health check failed: " + e.message, "err"); }
  }

  async function runDiagnose() {
    try {
      const d = await api("/api/diagnose");
      const panel = $("#diagnosisPanel");
      panel.hidden = false;
      const body = $("#diagnosisBody");
      const cards = d.cards || [];
      body.innerHTML = `
        <div style="padding:8px 16px;font-size:.72rem;color:var(--fg2)">
          ${d.lastHealthyCheckpoint ? `Last verified: <strong>${ID(d.lastHealthyCheckpoint).slice(-8)}</strong> · ${TS(d.lastHealthyAt)} · ${d.changesSinceHealthy} change(s) since` : "No verified checkpoint found."}
        </div>
        <div class="card-list">${cards.map(c => `
          <div class="card-item" data-sev="${esc(c.severity)}">
            <div class="card-kind">${esc(c.kind)} · ${esc(c.severity)}</div>
            <div class="card-title">${esc(c.title)}</div>
            <div class="card-detail">${esc(c.detail || "")}</div>
          </div>`).join("")}</div>`;
      panel.scrollIntoView({ behavior: reducedMotion ? "auto" : "smooth" });
    } catch (e) { toast("Diagnosis failed: " + e.message, "err"); }
  }

  /* ==================== SSE ==================== */
  function startSSE() {
    try {
      evtSource = new EventSource("/api/events");
      evtSource.onmessage = e => {
        try {
          const upd = JSON.parse(e.data);
          if (!upd.message || upd.message === ": keep-alive") return;
          appendOpLog(upd);
          const uid = upd.operationId && upd.operationId.value;
          if (wizardOpId && uid === wizardOpId) driveWizard(upd);
        } catch {}
      };
      evtSource.onerror = () => { setConn(false); };
      evtSource.onopen = () => { setConn(true); };
    } catch {}
  }
  function setConn(ok) {
    const c = $("#conn");
    if (ok) { c.classList.remove("off"); $("span:last-child", c).textContent = "CONNECTED"; }
    else { c.classList.add("off"); $("span:last-child", c).textContent = "RECONNECTING…"; }
  }

  function appendOpLog(upd) {
    const log = $("#opLog");
    if ($(".muted", log)) log.innerHTML = "";
    const li = document.createElement("li");
    li.innerHTML = `<span class="op-stage op-stage-${esc(upd.stage)}">${esc(upd.stage)}</span><span class="op-msg">${esc(upd.message || "…")}</span><span class="op-time">${TIME(upd.timestamp)}</span>`;
    log.prepend(li);
    while (log.children.length > 80) log.lastChild.remove();
  }

  /* ==================== CLOCK ==================== */
  function startClock() {
    const tick = () => { const n = new Date(); $("#clock").textContent = [n.getHours(), n.getMinutes(), n.getSeconds()].map(v => String(v).padStart(2, "0")).join(":"); };
    tick(); setInterval(tick, 1000);
  }

  /* ==================== PARTICLES ==================== */
  function startParticles() {
    if (reducedMotion) return;
    const cv = $("#fx"); const ctxv = cv.getContext("2d");
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
    const N = Math.min(70, Math.floor(innerWidth / 22));
    const parts = Array.from({ length: N }, () => ({
      x: Math.random() * innerWidth, y: Math.random() * innerHeight,
      r: Math.random() * 1.6 + .4, vx: (Math.random() - .5) * .12, vy: -Math.random() * .22 - .04,
      c: Math.random() < .7 ? "77,216,255" : "255,77,157", a: Math.random() * .35 + .15
    }));
    let last = 0;
    const loop = t => {
      requestAnimationFrame(loop);
      if (t - last < 33) return; last = t;
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
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem("cv-theme", next); } catch {}
    toast("Theme: " + next, "ok");
  }
  function loadTheme() { try { const t = localStorage.getItem("cv-theme"); if (t) document.documentElement.dataset.theme = t; } catch {} }

  /* ==================== CAMERA INPUT ==================== */
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
    }, { passive: false });
    const active = new Map();
    let drag = null;
    let pinch = null;
    const endPointers = () => {
      if (active.size < 2) pinch = null;
      if (active.size === 0) { drag = null; vp.classList.remove("dragging"); }
    };
    vp.addEventListener("pointerdown", e => {
      if (e.target.closest(".tl-dot")) return;
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
    if (e.key === "Escape") { $$(".modal-backdrop.open").forEach(m => closeModal(m.id)); closeWizard(); return; }
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
  }

  /* ==================== TOASTS ==================== */
  function toast(msg, type = "ok") {
    const t = document.createElement("div");
    t.className = `toast ${type}`;
    t.textContent = msg;
    $("#toasts").appendChild(t);
    setTimeout(() => { t.style.opacity = "0"; setTimeout(() => t.remove(), 300); }, 4000);
  }

  /* ==================== BIND ==================== */
  function bind() {
    $("#themeToggle").addEventListener("click", cycleTheme);
    $("#cmdPaletteBtn").addEventListener("click", openPalette);
    $("#btnCheckpoint").addEventListener("click", runCheckpoint);
    $("#btnHealth").addEventListener("click", runHealth);
    $("#btnDiagnose").addEventListener("click", runDiagnose);
    $("#btnRestore").addEventListener("click", () => openRestoreModal(""));
    $("#btnConfirmRestore").addEventListener("click", confirmRestore);
    $("#diffRun").addEventListener("click", runDiff);
    $("#diagClose").addEventListener("click", () => $("#diagnosisPanel").hidden = true);
    $("#paletteInput").addEventListener("input", e => renderPalette(e.target.value));
    $("#paletteInput").addEventListener("keydown", e => {
      const sel = $(".sel", $("#paletteList"));
      if (e.key === "ArrowDown" && sel?.nextElementSibling) { sel.classList.remove("sel"); sel.nextElementSibling.classList.add("sel"); }
      if (e.key === "ArrowUp" && sel?.previousElementSibling) { sel.classList.remove("sel"); sel.previousElementSibling.classList.add("sel"); }
      if (e.key === "Enter" && sel) { closePalette(); filteredCmds[+sel.dataset.idx]?.fn(); }
      if (e.key === "Enter" && !sel && filteredCmds.length) { closePalette(); filteredCmds[0]?.fn(); }
    });
    const zIn = () => { camera.zoom = Math.min(camera.zoom * 1.4, 8); clampCam(); renderWorld(); };
    const zOut = () => { camera.zoom = Math.max(camera.zoom / 1.4, 0.15); clampCam(); renderWorld(); };
    $("#zoomIn").addEventListener("click", zIn);
    $("#zoomOut").addEventListener("click", zOut);
    $("#zoomReset").addEventListener("click", fit);
    $("#viewTimeline").addEventListener("click", () => { viewMode = "timeline"; $("#viewTimeline").classList.add("active"); $("#viewMap").classList.remove("active"); renderWorld(); });
    $("#viewMap").addEventListener("click", () => { viewMode = "map"; $("#viewMap").classList.add("active"); $("#viewTimeline").classList.remove("active"); fit(); renderWorld(); });
    $("#wizardClose").addEventListener("click", closeWizard);
    $("#btnWelcomeVerify").addEventListener("click", runHealth);
    $("#btnWelcomeCheckpoint").addEventListener("click", runHealthAndCheckpoint);
    initCamera();
  }

  loadTheme();
  init().catch(console.error);
})();