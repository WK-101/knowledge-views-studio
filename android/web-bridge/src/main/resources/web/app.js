"use strict";
/*
 * Hexis Web — desktop companion served from the phone.
 *
 * Transport/crypto are unchanged from v0.2: every /api body and every /live WebSocket frame is
 * AES-256-GCM over HKDF-SHA256 (WebCrypto-native), keyed by the per-device pairing key carried in the URL
 * fragment. This file is the UI layer: a nav-rail + context-sidebar + main + right-inspector shell.
 */
(function () {
  const AEAD_INFO = "hexis-web-aead-v1";
  const IV_LEN = 12;

  // ============================================================== base64url
  function b64urlEncode(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
    return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  }
  function b64urlDecode(str) {
    const s = str.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(s + "===".slice((s.length + 3) % 4));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  // ============================================================== crypto
  let aeadKey = null;
  async function deriveKey(pairingKeyBytes) {
    const base = await crypto.subtle.importKey("raw", pairingKeyBytes, "HKDF", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits(
      { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new TextEncoder().encode(AEAD_INFO) },
      base,
      256
    );
    return crypto.subtle.importKey("raw", bits, "AES-GCM", false, ["encrypt", "decrypt"]);
  }
  async function sealText(text) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LEN));
    const ct = new Uint8Array(
      await crypto.subtle.encrypt({ name: "AES-GCM", iv, tagLength: 128 }, aeadKey, new TextEncoder().encode(text))
    );
    const blob = new Uint8Array(IV_LEN + ct.length);
    blob.set(iv, 0);
    blob.set(ct, IV_LEN);
    return b64urlEncode(blob);
  }
  async function openText(blobB64) {
    const blob = b64urlDecode(blobB64);
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: blob.slice(0, IV_LEN), tagLength: 128 }, aeadKey, blob.slice(IV_LEN)
    );
    return new TextDecoder().decode(pt);
  }
  function nonce() { return b64urlEncode(crypto.getRandomValues(new Uint8Array(16))); }

  // ============================================================== api
  async function call(req, signal) {
    const body = await sealText(JSON.stringify(Object.assign({ ts: Date.now(), nonce: nonce() }, req)));
    const res = await fetch("api", { method: "POST", headers: { "Content-Type": "text/plain" }, body, signal });
    if (!res.ok) {
      let detail = "";
      try { detail = (await res.text()).slice(0, 120); } catch (_e) { /* ignore */ }
      throw new Error("HTTP " + res.status + (detail ? " — " + detail : ""));
    }
    return JSON.parse(await openText(await res.text()));
  }
  async function query(domain, op, extra) {
    const api = await call(Object.assign({ kind: "query", domain, op: op || "list", limit: 1000 }, extra || {}));
    if (!api.ok) throw new Error(api.error || "request failed");
    const page = api.dataJson ? JSON.parse(api.dataJson) : { payloadJson: "[]" };
    return JSON.parse(page.payloadJson || "[]");
  }
  async function mutate(domain, op, payload) {
    const api = await call({ kind: "mutate", domain, op, payloadJson: JSON.stringify(payload || {}) });
    if (!api.ok) throw new Error(api.error || "save failed");
    return api.dataJson ? JSON.parse(api.dataJson) : null;
  }
  async function hello() {
    const api = await call({ kind: "hello" });
    return api.ok && api.dataJson ? JSON.parse(api.dataJson) : { readOnly: false };
  }

  // ============================================================== state
  const DOMAINS = ["tasks", "notes", "calendar", "time", "habits"];
  const state = { tasks: [], lists: [], notes: [], calendar: [], time: [], habits: [] };
  let seen = {};
  let readOnly = false;

  const ui = {
    domain: "tasks",
    taskView: "list", // list | board | matrix
    taskFilter: { kind: "smart", value: "all" }, // smart:(all|today|upcoming|starred|completed) | list:<id>
    sel: null, // { domain, id } for the inspector
    sidebar: true,
  };
  function loadUi() {
    try {
      const s = JSON.parse(localStorage.getItem("hexis_ui") || "{}");
      if (DOMAINS.includes(s.domain)) ui.domain = s.domain;
      if (["list", "board", "matrix"].includes(s.taskView)) ui.taskView = s.taskView;
      if (s.taskFilter && s.taskFilter.kind) ui.taskFilter = s.taskFilter;
      if (typeof s.sidebar === "boolean") ui.sidebar = s.sidebar;
    } catch (_e) { /* defaults */ }
  }
  function saveUi() {
    try {
      localStorage.setItem("hexis_ui", JSON.stringify({
        domain: ui.domain, taskView: ui.taskView, taskFilter: ui.taskFilter, sidebar: ui.sidebar,
      }));
    } catch (_e) { /* private mode */ }
  }

  // ============================================================== helpers
  const el = (id) => document.getElementById(id);
  const app = () => el("app");
  function esc(s) {
    return (s == null ? "" : String(s)).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }
  const DAY = 86400000;
  function startOfToday() { const d = new Date(); d.setHours(0, 0, 0, 0); return d.getTime(); }
  function fmtDate(ms) {
    if (!ms) return "";
    try { return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" }); } catch (_e) { return ""; }
  }
  function fmtDateTime(ms) {
    if (!ms) return "";
    try { return new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }); } catch (_e) { return ""; }
  }
  function fmtTime(ms) {
    if (!ms) return "";
    try { return new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" }); } catch (_e) { return ""; }
  }
  function fmtDuration(min) {
    const m = Math.max(0, Math.round(min || 0));
    if (m < 60) return m + "m";
    const h = Math.floor(m / 60), r = m % 60;
    return r ? h + "h " + r + "m" : h + "h";
  }
  function toDateInput(ms) {
    if (!ms) return "";
    const d = new Date(ms), p = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
  }
  function fromDateInput(v) {
    if (!v) return null;
    const [y, m, d] = v.split("-").map(Number);
    return new Date(y, m - 1, d, 12, 0, 0, 0).getTime();
  }
  function argb(color) {
    if (color == null) return null;
    const n = (Number(color) >>> 0) & 0xffffff;
    return "#" + n.toString(16).padStart(6, "0");
  }
  function dueClass(t) {
    if (!t.dueDate || t.completed) return "";
    if (t.dueDate < startOfToday()) return "overdue";
    if (t.dueDate < startOfToday() + DAY) return "today";
    return "";
  }

  let toastTimer = null;
  function toast(msg) {
    const t = el("toast");
    t.textContent = msg;
    t.hidden = false;
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 3200);
  }
  function setStatus(msg, kind) {
    const s = el("status");
    s.textContent = msg || "";
    s.hidden = !msg;
    s.className = "status" + (kind ? " " + kind : "");
  }
  function setConn(kind) { el("connDot").className = "dot" + (kind ? " " + kind : ""); }

  // ============================================================== data load
  const DOMAIN_RENDER = {}; // domain -> () => void (set after render fns defined)
  async function reloadDomain(d) {
    if (d === "tasks") {
      const [tasks, lists] = await Promise.all([query("tasks", "list"), query("tasks", "lists").catch(() => [])]);
      state.tasks = tasks; state.lists = lists;
    } else {
      state[d] = await query(d, "list");
    }
  }
  async function reload(domain) {
    const domains = domain ? [domain] : DOMAINS;
    await Promise.all(domains.map(reloadDomain));
  }
  async function loadAll() {
    setStatus("Loading…", "");
    try { await reload(); setStatus("", "ok"); } catch (e) {
      setStatus("Could not load. Make sure Hexis is connected on your phone. (" + e.message + ")", "err");
      throw e;
    }
  }

  // ============================================================== render shell
  function render() {
    app().dataset.domain = ui.domain;
    app().dataset.sidebar = ui.sidebar ? "open" : "closed";
    document.querySelectorAll(".rail-btn[data-domain]").forEach((b) => {
      b.classList.toggle("active", b.dataset.domain === ui.domain);
    });
    renderSidebar();
    renderMain();
    renderInspector();
  }

  function selectDomain(d) {
    ui.domain = d;
    if (ui.sel && ui.sel.domain !== d) closeInspector();
    saveUi();
    render();
  }

  // ============================================================== sidebar
  function renderSidebar() {
    const sb = el("sidebar");
    if (ui.domain === "tasks") { sb.innerHTML = tasksSidebar(); return; }
    if (ui.domain === "notes") { sb.innerHTML = simpleSidebar("Notes", [["all", "All notes"]], "all"); return; }
    if (ui.domain === "calendar") { sb.innerHTML = simpleSidebar("Calendar", [["upcoming", "Upcoming"]], "upcoming"); return; }
    if (ui.domain === "time") { sb.innerHTML = simpleSidebar("Time", [["recent", "Recent"]], "recent"); return; }
    if (ui.domain === "habits") { sb.innerHTML = simpleSidebar("Habits", [["all", "Today"]], "all"); return; }
  }
  function simpleSidebar(title, items, active) {
    return '<h2 class="sb-title">' + esc(title) + "</h2><ul class=\"sb-list\">" +
      items.map((i) => '<li class="sb-item active">' + esc(i[1]) + "</li>").join("") + "</ul>";
  }
  function tasksSidebar() {
    const f = ui.taskFilter;
    const smart = [
      ["all", "All", "&#x2630;"], ["today", "Today", "&#x2600;"], ["upcoming", "Upcoming", "&#x25B8;"],
      ["starred", "Starred", "&#x2605;"], ["completed", "Completed", "&#x2713;"],
    ];
    const smartHtml = smart.map((s) => {
      const on = f.kind === "smart" && f.value === s[0];
      const count = s[0] === "completed" ? "" : countSmart(s[0]);
      return '<li class="sb-item' + (on ? " active" : "") + '" data-smart="' + s[0] + '">' +
        '<span class="sb-ico">' + s[2] + "</span><span class=\"sb-label\">" + s[1] + "</span>" +
        (count ? '<span class="sb-count">' + count + "</span>" : "") + "</li>";
    }).join("");
    const listsHtml = state.lists.map((l) => {
      const on = f.kind === "list" && f.value === l.id;
      const dot = argb(l.colorArgb);
      return '<li class="sb-item' + (on ? " active" : "") + '" data-list="' + esc(l.id) + '">' +
        '<span class="sb-ico">' + (l.emoji ? esc(l.emoji) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : "&#x2219;")) + "</span>" +
        '<span class="sb-label">' + esc(l.name) + "</span>" +
        (l.count ? '<span class="sb-count">' + l.count + "</span>" : "") + "</li>";
    }).join("");
    return '<h2 class="sb-title">Tasks</h2>' +
      '<ul class="sb-list">' + smartHtml + "</ul>" +
      (listsHtml ? '<h3 class="sb-sub">Lists</h3><ul class="sb-list">' + listsHtml + "</ul>" : "");
  }
  function countSmart(kind) {
    const open = state.tasks.filter((t) => !t.completed);
    if (kind === "all") return open.length;
    if (kind === "today") return open.filter((t) => t.dueDate && t.dueDate < startOfToday() + DAY).length;
    if (kind === "upcoming") return open.filter((t) => t.dueDate && t.dueDate >= startOfToday() + DAY).length;
    if (kind === "starred") return open.filter((t) => t.star).length;
    return 0;
  }

  // ============================================================== main (dispatch)
  function renderMain() {
    const head = el("mainHead"), body = el("mainBody");
    body.onclick = null;
    if (ui.domain === "tasks") { head.innerHTML = tasksHead(); body.innerHTML = tasksMain(); wireTasksMain(); }
    else if (ui.domain === "notes") { head.innerHTML = headTitle("Notes", readOnly ? "" : '<button class="btn primary" data-new="note">New note</button>'); body.innerHTML = notesMain(); body.onclick = onNotesClick; }
    else if (ui.domain === "calendar") { head.innerHTML = headTitle("Calendar", ""); body.innerHTML = calendarMain(); }
    else if (ui.domain === "time") { head.innerHTML = headTitle("Time", ""); body.innerHTML = timeMain(); }
    else if (ui.domain === "habits") { head.innerHTML = headTitle("Habits", ""); body.innerHTML = habitsMain(); }
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = toggleSidebar;
    const nb = head.querySelector('[data-new="note"]'); if (nb) nb.onclick = () => openInspector("notes", "__new__");
  }
  function onNotesClick(e) {
    const card = e.target.closest(".ncard");
    if (card) openInspector("notes", card.dataset.id);
  }
  function headTitle(title, right) {
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar" aria-label="Toggle sidebar">&#9776;</button>' +
      '<h1 class="main-title">' + esc(title) + "</h1><span class=\"head-spacer\"></span>" + (right || "");
  }

  // ---- Tasks -------------------------------------------------------------
  function filteredTasks() {
    const f = ui.taskFilter;
    let ts = state.tasks.slice();
    if (f.kind === "list") return ts.filter((t) => t.listId === f.value);
    if (f.value === "completed") return ts.filter((t) => t.completed);
    const open = ts.filter((t) => !t.completed);
    if (f.value === "today") return open.filter((t) => t.dueDate && t.dueDate < startOfToday() + DAY);
    if (f.value === "upcoming") return open.filter((t) => t.dueDate && t.dueDate >= startOfToday() + DAY);
    if (f.value === "starred") return open.filter((t) => t.star);
    return open; // all
  }
  function tasksHead() {
    const views = [["list", "List"], ["board", "Board"], ["matrix", "Matrix"]];
    const seg = views.map((v) => '<button class="seg' + (ui.taskView === v[0] ? " on" : "") + '" data-view="' + v[0] + '">' + v[1] + "</button>").join("");
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar">&#9776;</button>' +
      '<h1 class="main-title">' + esc(filterLabel()) + "</h1>" +
      '<div class="segmented">' + seg + "</div><span class=\"head-spacer\"></span>";
  }
  function filterLabel() {
    const f = ui.taskFilter;
    if (f.kind === "list") { const l = state.lists.find((x) => x.id === f.value); return l ? l.name : "List"; }
    return { all: "All tasks", today: "Today", upcoming: "Upcoming", starred: "Starred", completed: "Completed" }[f.value] || "Tasks";
  }
  function tasksMain() {
    const quick = readOnly ? "" :
      '<form id="quickAdd" class="quickadd"><input id="quickAddInput" type="text" autocomplete="off" ' +
      'placeholder="Add a task…  (use ! for a due date, * to star)" /><button type="submit">Add</button></form>';
    let view;
    if (ui.taskView === "matrix") view = tasksMatrix();
    else if (ui.taskView === "board") view = tasksBoard();
    else view = tasksList();
    return quick + view;
  }
  function tasksList() {
    const ts = filteredTasks();
    if (!ts.length) return emptyState("No tasks here", "Add one above or pick another list.");
    // order: incomplete by sortOrder, then completed
    const open = ts.filter((t) => !t.completed).sort((a, b) => a.sortOrder - b.sortOrder);
    const done = ts.filter((t) => t.completed).sort((a, b) => b.updatedAt - a.updatedAt);
    const rows = open.map(taskRow).join("");
    const doneRows = done.length ? '<h3 class="group-h">Completed</h3><ul class="tlist">' + done.map(taskRow).join("") + "</ul>" : "";
    return '<ul class="tlist">' + rows + "</ul>" + doneRows;
  }
  function taskRow(t) {
    const sel = ui.sel && ui.sel.domain === "tasks" && ui.sel.id === t.id;
    const indent = t.parentId ? ' style="padding-left:34px"' : "";
    return '<li class="trow' + (t.completed ? " done" : "") + (sel ? " sel" : "") + '" data-id="' + esc(t.id) + '"' + indent + ">" +
      '<button class="check" data-act="toggle" aria-label="Complete">' + (t.completed ? "&#x2713;" : "") + "</button>" +
      '<span class="trow-main" data-act="open">' +
      '<span class="trow-title">' + (t.star ? '<span class="star">&#x2605;</span> ' : "") + esc(t.title) + "</span>" +
      (t.note ? '<span class="trow-note">' + esc(t.note.slice(0, 80)) + "</span>" : "") + "</span>" +
      (t.dueDate ? '<span class="due ' + dueClass(t) + '">' + esc(fmtDate(t.dueDate)) + "</span>" : "") +
      (readOnly ? "" : '<button class="star-btn" data-act="star" aria-label="Star">' + (t.star ? "&#x2605;" : "&#x2606;") + "</button>") +
      "</li>";
  }
  function tasksBoard() {
    const ts = filteredTasks().filter((t) => !t.completed);
    const buckets = [
      ["overdue", "Overdue"], ["today", "Today"], ["soon", "Next 7 days"], ["later", "Later"], ["none", "No date"],
    ];
    const t0 = startOfToday();
    const place = (t) => !t.dueDate ? "none" : t.dueDate < t0 ? "overdue" : t.dueDate < t0 + DAY ? "today" : t.dueDate < t0 + 7 * DAY ? "soon" : "later";
    const cols = buckets.map((b) => {
      const items = ts.filter((t) => place(t) === b[0]).sort((a, b2) => (a.dueDate || 0) - (b2.dueDate || 0));
      const cards = items.map(taskCard).join("") || '<p class="col-empty">—</p>';
      return '<div class="bcol" data-bucket="' + b[0] + '"><h3 class="bcol-h">' + b[1] + ' <span class="bcol-n">' + items.length + "</span></h3>" +
        '<div class="bcol-body">' + cards + "</div></div>";
    }).join("");
    return '<div class="board">' + cols + "</div>";
  }
  function taskCard(t) {
    return '<div class="tcard" draggable="' + (!readOnly) + '" data-id="' + esc(t.id) + '" data-act="open">' +
      '<span class="tcard-title">' + (t.star ? '<span class="star">&#x2605;</span> ' : "") + esc(t.title) + "</span>" +
      (t.dueDate ? '<span class="due ' + dueClass(t) + '">' + esc(fmtDate(t.dueDate)) + "</span>" : "") + "</div>";
  }
  const QUADS = [
    { q: 0, name: "Urgent & Important", hint: "Do first" },
    { q: 1, name: "Important, Not Urgent", hint: "Schedule" },
    { q: 2, name: "Urgent, Not Important", hint: "Delegate" },
    { q: 3, name: "Neither", hint: "Later" },
  ];
  function quadrantOf(t) { // mirrors PriorityEngine.quadrant (threshold 4)
    const important = (t.importance || 0) >= 4, urgent = (t.urgency || 0) >= 4;
    return urgent && important ? 0 : !urgent && important ? 1 : urgent && !important ? 2 : 3;
  }
  function tasksMatrix() {
    const ts = filteredTasks().filter((t) => !t.completed);
    const cells = QUADS.map((qd) => {
      const items = ts.filter((t) => quadrantOf(t) === qd.q).sort((a, b) => (a.dueDate || 1e15) - (b.dueDate || 1e15));
      const cards = items.map(taskCard).join("") || '<p class="col-empty">—</p>';
      return '<div class="quad q' + qd.q + '" data-quad="' + qd.q + '">' +
        '<div class="quad-h"><span>' + esc(qd.name) + '</span><span class="quad-hint">' + esc(qd.hint) + "</span></div>" +
        '<div class="quad-body">' + cards + "</div></div>";
    }).join("");
    return '<div class="matrix">' + cells + "</div>";
  }

  // ---- Notes (card grid; editor in inspector) ----------------------------
  function notesMain() {
    const notes = state.notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.updatedAt - a.updatedAt));
    if (!notes.length) return emptyState("No notes", readOnly ? "" : "Create one with “New note”.");
    return '<div class="ncards">' + notes.map((n) => {
      const sel = ui.sel && ui.sel.domain === "notes" && ui.sel.id === n.id;
      return '<article class="ncard' + (sel ? " sel" : "") + '" data-id="' + esc(n.id) + '" data-act="open">' +
        (n.pinned ? '<span class="pin">&#x1F4CC;</span>' : "") +
        "<h4>" + esc(n.title || "Untitled") + "</h4>" +
        '<p>' + esc((n.body || "").slice(0, 240)) + "</p></article>";
    }).join("") + "</div>";
  }

  // ---- Calendar / Time / Habits (read lists for R3a) ---------------------
  function calendarMain() {
    const now = Date.now();
    const up = state.calendar.filter((e) => (e.endMillis || e.startMillis) >= now - 12 * 3600000).slice(0, 300);
    if (!up.length) return emptyState("No upcoming events", "");
    return '<ul class="elist">' + up.map((e) => {
      const when = e.allDay ? fmtDate(e.startMillis) + " · all day" : fmtDateTime(e.startMillis) + "–" + fmtTime(e.endMillis);
      const dot = argb(e.colorArgb) || "var(--accent)";
      return '<li class="erow"><span class="cdot" style="background:' + dot + '"></span>' +
        '<span class="einfo"><span class="etitle">' + esc(e.title || "Untitled") + "</span>" +
        '<span class="emeta">' + esc(when) + (e.location ? " · " + esc(e.location) : "") + (e.calendarName ? " · " + esc(e.calendarName) : "") + "</span></span>" +
        (e.recurring ? '<span class="chip">&#x21BB;</span>' : "") + "</li>";
    }).join("") + "</ul>";
  }
  function timeMain() {
    const entries = state.time.slice(0, 300);
    const t0 = startOfToday();
    const todayMin = entries.filter((e) => e.startMillis >= t0).reduce((s, e) => s + (e.minutes || 0), 0);
    if (!entries.length) return emptyState("No time entries", "");
    const rows = entries.map((e) =>
      '<li class="erow"><span class="einfo"><span class="etitle">' + esc(e.activityName || "Activity") +
      (e.running ? ' <span class="chip live">● live</span>' : "") + "</span>" +
      '<span class="emeta">' + esc(fmtDateTime(e.startMillis)) + (e.note ? " · " + esc(e.note) : "") + "</span></span>" +
      '<span class="dur">' + esc(fmtDuration(e.minutes)) + "</span></li>").join("");
    return '<div class="summary">Today · <strong>' + esc(fmtDuration(todayMin)) + "</strong> tracked</div><ul class=\"elist\">" + rows + "</ul>";
  }
  function habitsMain() {
    const habits = state.habits;
    if (!habits.length) return emptyState("No habits", "");
    const row = (h) => '<li class="trow' + (h.doneToday ? " done" : "") + '">' +
      '<span class="check static">' + (h.doneToday ? "&#x2713;" : "") + "</span>" +
      '<span class="trow-main"><span class="trow-title">' + (h.emoji ? esc(h.emoji) + " " : "") + esc(h.name) +
      (h.paused ? ' <span class="chip">paused</span>' : "") + "</span></span>" +
      (h.targetPerDay > 1 ? '<span class="due">' + (h.todayCount || 0) + "/" + h.targetPerDay + "</span>" : "") + "</li>";
    const active = habits.filter((h) => !h.paused), paused = habits.filter((h) => h.paused);
    return '<ul class="tlist">' + active.map(row).join("") + "</ul>" +
      (paused.length ? '<h3 class="group-h">Paused</h3><ul class="tlist">' + paused.map(row).join("") + "</ul>" : "");
  }

  function emptyState(title, sub) {
    return '<div class="empty-state"><div class="empty-ic">&#9671;</div><h3>' + esc(title) + "</h3>" +
      (sub ? "<p>" + esc(sub) + "</p>" : "") + "</div>";
  }

  // ============================================================== inspector
  function openInspector(domain, id) { ui.sel = { domain, id }; app().dataset.inspector = "open"; el("inspector").hidden = false; renderInspector(); if (window.innerWidth < 1100) el("scrim").hidden = false; }
  function closeInspector() { ui.sel = null; app().dataset.inspector = "closed"; el("inspector").hidden = true; el("scrim").hidden = true; document.querySelectorAll(".trow.sel,.ncard.sel").forEach((n) => n.classList.remove("sel")); }
  function renderInspector() {
    if (!ui.sel) return;
    const ins = el("inspector");
    if (ui.sel.domain === "tasks") {
      const t = state.tasks.find((x) => x.id === ui.sel.id);
      if (!t) { closeInspector(); return; }
      ins.innerHTML = taskInspector(t);
      wireTaskInspector(t);
    } else if (ui.sel.domain === "notes") {
      const n = ui.sel.id === "__new__" ? { id: "", title: "", body: "", pinned: false, archived: false } : state.notes.find((x) => x.id === ui.sel.id);
      if (!n) { closeInspector(); return; }
      ins.innerHTML = noteInspector(n);
      wireNoteInspector(n);
    }
  }
  function inspectorHead(title) {
    return '<div class="ins-head"><h2>' + esc(title) + '</h2><button class="icon-btn" id="insClose" title="Close (Esc)">&#x2715;</button></div>';
  }
  function taskInspector(t) {
    const ro = readOnly ? " disabled" : "";
    return inspectorHead("Task") +
      '<div class="ins-body">' +
      '<label class="fld"><span>Title</span><input id="iTitle" type="text" value="' + esc(t.title) + '"' + ro + "></label>" +
      '<label class="fld"><span>Note</span><textarea id="iNote" rows="4"' + ro + ">" + esc(t.note || "") + "</textarea></label>" +
      '<div class="fld-row">' +
      '<label class="fld"><span>Due</span><input id="iDue" type="date" value="' + esc(toDateInput(t.dueDate)) + '"' + ro + "></label>" +
      '<label class="fld chk"><input id="iStar" type="checkbox"' + (t.star ? " checked" : "") + ro + "> Star</label>" +
      "</div>" +
      '<div class="fld-row">' +
      '<label class="fld chk"><input id="iImp" type="checkbox"' + ((t.importance || 0) >= 4 ? " checked" : "") + ro + "> Important</label>" +
      '<label class="fld chk"><input id="iUrg" type="checkbox"' + ((t.urgency || 0) >= 4 ? " checked" : "") + ro + "> Urgent</label>" +
      "</div>" +
      (readOnly ? "" :
        '<div class="ins-actions"><button class="btn danger" id="iDelete">Delete</button><span class="spacer"></span>' +
        '<button class="btn primary" id="iSave">Save</button></div>') +
      "</div>";
  }
  function noteInspector(n) {
    const ro = readOnly ? " disabled" : "";
    const isNew = !n.id;
    return inspectorHead(isNew ? "New note" : "Note") +
      '<div class="ins-body">' +
      '<label class="fld"><span>Title</span><input id="iTitle" type="text" value="' + esc(n.title) + '"' + ro + "></label>" +
      '<label class="fld grow"><span>Body</span><textarea id="iBody" rows="12"' + ro + ">" + esc(n.body) + "</textarea></label>" +
      '<label class="fld chk"><input id="iPin" type="checkbox"' + (n.pinned ? " checked" : "") + ro + "> Pin</label>" +
      '<div class="attachments" id="iAttach"></div>' +
      (readOnly ? "" :
        '<div class="ins-actions">' + (isNew ? "" : '<button class="btn danger" id="iDelete">Delete</button>') +
        '<span class="spacer"></span><button class="btn primary" id="iSave">Save</button></div>') +
      "</div>";
  }

  // ============================================================== mutations (tasks/notes)
  async function saveTask(t, patch) {
    try {
      await mutate("tasks", "upsert", Object.assign({
        id: t.id, title: t.title, note: t.note, dueDate: t.dueDate, startDate: t.startDate,
        importance: t.importance, urgency: t.urgency, star: t.star,
      }, patch));
      await reloadDomain("tasks"); render();
    } catch (e) { toast(e.message); }
  }
  async function wireTaskInspector(t) {
    el("insClose").onclick = closeInspector;
    if (readOnly) return;
    el("iSave").onclick = async () => {
      const title = el("iTitle").value.trim();
      if (!title) { toast("Title can't be empty"); return; }
      const imp = el("iImp").checked ? 5 : 3, urg = el("iUrg").checked ? 5 : 3;
      try {
        await mutate("tasks", "upsert", {
          id: t.id, title, note: el("iNote").value, dueDate: fromDateInput(el("iDue").value),
          startDate: t.startDate, importance: imp, urgency: urg, star: el("iStar").checked,
        });
        await reloadDomain("tasks"); render(); toast("Saved");
      } catch (e) { toast(e.message); }
    };
    el("iDelete").onclick = async () => {
      if (!confirm("Move this task to Trash?")) return;
      try { await mutate("tasks", "delete", { id: t.id }); closeInspector(); await reloadDomain("tasks"); render(); } catch (e) { toast(e.message); }
    };
  }
  async function wireNoteInspector(n) {
    el("insClose").onclick = closeInspector;
    if (!n.id) loadAttachments(null); else loadAttachments(n.id);
    if (readOnly) return;
    el("iSave").onclick = async () => {
      try {
        const res = await mutate("notes", "upsert", { id: n.id, title: el("iTitle").value, body: el("iBody").value, pinned: el("iPin").checked });
        await reloadDomain("notes");
        // keep the inspector open on the (possibly new) note
        const newId = res && res.id ? res.id : n.id;
        ui.sel = { domain: "notes", id: newId };
        render(); toast("Saved");
      } catch (e) { toast(e.message); }
    };
    const del = el("iDelete");
    if (del) del.onclick = async () => {
      if (!confirm("Move this note to Trash?")) return;
      try { await mutate("notes", "delete", { id: n.id }); closeInspector(); await reloadDomain("notes"); render(); } catch (e) { toast(e.message); }
    };
  }
  async function loadAttachments(noteId) {
    const box = el("iAttach");
    if (!box || !noteId) return;
    let list;
    try { list = await query("notes", "attachments", { paramsJson: JSON.stringify({ id: noteId }) }); } catch (_e) { return; }
    if (!list || !list.length) return;
    box.innerHTML = '<h4 class="att-h">Attachments</h4><div class="att-grid"></div>';
    const grid = box.querySelector(".att-grid");
    for (const a of list) {
      const cell = document.createElement("div");
      cell.className = "att";
      cell.innerHTML = '<span class="att-name">' + esc(a.fileName || "file") + "</span>";
      grid.appendChild(cell);
      if (a.isImage) {
        query("notes", "attachmentData", { paramsJson: JSON.stringify({ id: a.id, noteId }) }).then((d) => {
          if (d && d.dataB64) {
            const img = document.createElement("img");
            img.loading = "lazy"; img.alt = a.fileName || "";
            img.src = "data:" + (d.mime || "image/*") + ";base64," + d.dataB64;
            cell.insertBefore(img, cell.firstChild);
          } else cell.insertAdjacentHTML("afterbegin", '<span class="att-ph">open on phone</span>');
        }).catch(() => {});
      } else cell.insertAdjacentHTML("afterbegin", '<span class="att-ph">open on phone</span>');
    }
  }

  // ============================================================== events
  function wireTasksMain() {
    const head = el("mainHead");
    head.querySelectorAll(".seg").forEach((b) => b.onclick = () => { ui.taskView = b.dataset.view; saveUi(); renderMain(); });
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = toggleSidebar;
    if (!readOnly) {
      const form = el("quickAdd");
      if (form) form.onsubmit = async (e) => {
        e.preventDefault();
        const input = el("quickAddInput");
        const parsed = parseQuickAdd(input.value);
        if (!parsed.title) return;
        input.value = "";
        try {
          const payload = { id: "", title: parsed.title, star: parsed.star };
          if (parsed.due != null) payload.dueDate = parsed.due;
          if (ui.taskFilter.kind === "list") payload.listId = ui.taskFilter.value;
          await mutate("tasks", "upsert", payload);
          await reloadDomain("tasks"); render();
        } catch (err) { toast(err.message); }
      };
    }
    const body = el("mainBody");
    body.onclick = onTasksClick;
    // drag for board + matrix
    if (!readOnly) wireTaskDrag(body);
  }
  function parseQuickAdd(raw) {
    let title = (raw || "").trim();
    let star = false, due = null;
    if (/(^|\s)\*(\s|$)/.test(title)) { star = true; title = title.replace(/(^|\s)\*(\s|$)/, " ").trim(); }
    const m = title.match(/!(today|tomorrow|mon|tue|wed|thu|fri|sat|sun)\b/i);
    if (m) {
      due = dueFromWord(m[1].toLowerCase());
      title = title.replace(m[0], "").trim();
    }
    return { title, star, due };
  }
  function dueFromWord(w) {
    const d = new Date(); d.setHours(12, 0, 0, 0);
    if (w === "today") return d.getTime();
    if (w === "tomorrow") return d.getTime() + DAY;
    const map = { sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 };
    if (w in map) { let delta = (map[w] - d.getDay() + 7) % 7; if (delta === 0) delta = 7; return d.getTime() + delta * DAY; }
    return d.getTime();
  }
  async function onTasksClick(e) {
    const act = e.target.dataset.act;
    const rowEl = e.target.closest("[data-id]");
    if (!rowEl) return;
    const t = state.tasks.find((x) => x.id === rowEl.dataset.id);
    if (!t) return;
    if (act === "toggle" && !readOnly) {
      try { await mutate("tasks", "complete", { id: t.id, completed: !t.completed }); await reloadDomain("tasks"); render(); } catch (err) { toast(err.message); }
    } else if (act === "star" && !readOnly) {
      saveTask(t, { star: !t.star });
    } else {
      openInspector("tasks", t.id);
      rowEl.classList.add("sel");
    }
  }
  function wireTaskDrag(body) {
    let dragId = null;
    body.addEventListener("dragstart", (e) => { const c = e.target.closest("[data-id]"); if (c) { dragId = c.dataset.id; e.dataTransfer.effectAllowed = "move"; } });
    body.addEventListener("dragover", (e) => { if (e.target.closest(".quad,.bcol")) e.preventDefault(); });
    body.addEventListener("drop", async (e) => {
      const quad = e.target.closest(".quad"), col = e.target.closest(".bcol");
      if (!dragId || (!quad && !col)) return;
      e.preventDefault();
      const t = state.tasks.find((x) => x.id === dragId); dragId = null;
      if (!t) return;
      if (quad) {
        const q = Number(quad.dataset.quad);
        await saveTask(t, { importance: (q === 0 || q === 1) ? 5 : 3, urgency: (q === 0 || q === 2) ? 5 : 3 });
      } else if (col) {
        const b = col.dataset.bucket, t0 = startOfToday();
        const due = b === "today" ? t0 + 12 * 3600000 : b === "soon" ? t0 + 3 * DAY + 12 * 3600000 : b === "later" ? t0 + 14 * DAY + 12 * 3600000 : b === "none" ? null : t0 - DAY + 12 * 3600000;
        await saveTask(t, { dueDate: due });
      }
    });
  }

  function wireSidebar() {
    el("sidebar").addEventListener("click", (e) => {
      const item = e.target.closest(".sb-item");
      if (!item) return;
      if (item.dataset.smart) { ui.taskFilter = { kind: "smart", value: item.dataset.smart }; }
      else if (item.dataset.list) { ui.taskFilter = { kind: "list", value: item.dataset.list }; }
      else return;
      saveUi(); closeInspector(); render();
      if (window.innerWidth < 960) { ui.sidebar = false; render(); }
    });
  }
  function wireGlobal() {
    document.querySelectorAll(".rail-btn[data-domain]").forEach((b) => b.onclick = () => selectDomain(b.dataset.domain));
    el("themeBtn").onclick = toggleTheme;
    el("scrim").onclick = () => { closeInspector(); if (window.innerWidth < 920) { ui.sidebar = false; render(); } };
    document.addEventListener("keydown", onKey);
  }
  function toggleSidebar() { ui.sidebar = !ui.sidebar; saveUi(); render(); }
  function onKey(e) {
    if (e.key === "Escape" && ui.sel) { closeInspector(); return; }
    const tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea") return;
    if (e.key === "m" || e.key === "M") toggleSidebar();
  }

  let theme = null;
  function toggleTheme() {
    theme = theme === "dark" ? "light" : theme === "light" ? null : "dark";
    if (theme) document.documentElement.setAttribute("data-theme", theme);
    else document.documentElement.removeAttribute("data-theme");
    try { if (theme) localStorage.setItem("hexis_theme", theme); else localStorage.removeItem("hexis_theme"); } catch (_e) { /* */ }
  }

  // ============================================================== live (WebSocket)
  const live = { ws: null, backoff: 0, reconnectTimer: null, hbTimer: null, pongTimer: null, bannerTimer: null };
  function wsUrl() { return (location.protocol === "https:" ? "wss" : "ws") + "://" + location.host + "/live"; }
  async function sealFrame(obj) { return sealText(JSON.stringify(obj)); }
  function startLive() {
    stopLive();
    let sock;
    try { sock = new WebSocket(wsUrl()); } catch (_e) { scheduleReconnect(); return; }
    live.ws = sock;
    sock.onopen = async () => {
      live.backoff = 0; clearBannerTimer(); setConn("ok");
      try { sock.send(await sealFrame({ t: "hello", nonce: nonce(), since: seen })); } catch (_e) { /* */ }
      startHeartbeat();
    };
    sock.onmessage = async (ev) => {
      if (typeof ev.data !== "string") return;
      let msg; try { msg = JSON.parse(await openText(ev.data)); } catch (_e) { return; }
      if (msg.t === "pong") { clearPong(); return; }
      if (msg.t === "tick" && msg.versions) {
        const now = msg.versions;
        const changed = Object.keys(now).filter((d) => (now[d] || 0) > (seen[d] || 0) && DOMAINS.includes(d));
        seen = Object.assign({}, seen, now);
        for (const d of changed) { try { await reloadDomain(d); } catch (_e) { /* */ } }
        if (changed.length) render();
      }
    };
    sock.onclose = () => { if (live.ws === sock) { stopHeartbeat(); live.ws = null; setConn("err"); scheduleReconnect(); } };
    sock.onerror = () => { try { sock.close(); } catch (_e) { /* */ } };
  }
  function stopLive() { stopHeartbeat(); if (live.reconnectTimer) { clearTimeout(live.reconnectTimer); live.reconnectTimer = null; } if (live.ws) { const s = live.ws; live.ws = null; try { s.onclose = null; s.close(); } catch (_e) { /* */ } } }
  function scheduleReconnect() {
    if (live.reconnectTimer) return;
    showBannerSoon();
    const delay = Math.floor(Math.random() * Math.min(10000, 1000 * Math.pow(2, live.backoff)));
    live.backoff = Math.min(live.backoff + 1, 6);
    live.reconnectTimer = setTimeout(() => { live.reconnectTimer = null; startLive(); }, delay);
  }
  function startHeartbeat() {
    stopHeartbeat();
    live.hbTimer = setInterval(async () => {
      if (!live.ws || live.ws.readyState !== WebSocket.OPEN) return;
      try { live.ws.send(await sealFrame({ t: "ping", nonce: nonce() })); } catch (_e) { return; }
      if (!live.pongTimer) live.pongTimer = setTimeout(() => { live.pongTimer = null; try { live.ws && live.ws.close(); } catch (_e) { /* */ } }, 10000);
    }, 25000);
  }
  function stopHeartbeat() { if (live.hbTimer) { clearInterval(live.hbTimer); live.hbTimer = null; } clearPong(); }
  function clearPong() { if (live.pongTimer) { clearTimeout(live.pongTimer); live.pongTimer = null; } }
  function showBannerSoon() { if (live.bannerTimer) return; live.bannerTimer = setTimeout(() => { live.bannerTimer = null; setConn("err"); }, 2000); }
  function clearBannerTimer() { if (live.bannerTimer) { clearTimeout(live.bannerTimer); live.bannerTimer = null; } }
  function reconnectNow() { live.backoff = 0; if (live.reconnectTimer) { clearTimeout(live.reconnectTimer); live.reconnectTimer = null; } if (!live.ws || live.ws.readyState !== WebSocket.OPEN) startLive(); }
  function wireLiveEvents() {
    window.addEventListener("online", reconnectNow);
    window.addEventListener("offline", () => { setConn("err"); stopLive(); });
    document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") reconnectNow(); });
  }

  // ============================================================== boot
  async function boot() {
    loadUi();
    try { const t = localStorage.getItem("hexis_theme"); if (t) { theme = t; document.documentElement.setAttribute("data-theme", t); } } catch (_e) { /* */ }
    wireGlobal();
    wireSidebar();
    wireLiveEvents();

    const m = /[#&]k=([A-Za-z0-9_-]+)/.exec(location.hash || "");
    let keyB64 = m && m[1];
    if (keyB64) { try { sessionStorage.setItem("hexis_k", keyB64); } catch (_e) { /* */ } }
    else { try { keyB64 = sessionStorage.getItem("hexis_k"); } catch (_e) { keyB64 = null; } }
    if (!keyB64) { setStatus("No pairing key. Open the exact link (or scan the QR) shown in the Hexis Web app.", "err"); return; }
    try { aeadKey = await deriveKey(b64urlDecode(keyB64)); }
    catch (_e) { setStatus("Pairing key looks malformed. Re-scan the QR from the Hexis Web app.", "err"); return; }
    if (m) { try { history.replaceState(null, "", location.pathname + location.search); } catch (_e) { /* */ } }

    try { readOnly = (await hello()).readOnly === true; } catch (_e) { readOnly = false; }
    render(); // paint the shell immediately
    try { await loadAll(); render(); } catch (_e) { /* status shown */ }
    startLive();
  }
  window.addEventListener("DOMContentLoaded", boot);
})();
