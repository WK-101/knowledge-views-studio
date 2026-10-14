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
  const state = { tasks: [], lists: [], notes: [], notebooks: [], calendar: [], calendars: [], time: [], activities: [], habits: [], habitHistory: [] };
  let seen = {};
  let readOnly = false;

  const ui = {
    domain: "tasks",
    taskView: "list", // list | board | matrix
    taskFilter: { kind: "smart", value: "all" }, // smart:(all|today|upcoming|starred|completed) | list:<id>
    noteFilter: { kind: "smart", value: "all" }, // smart:(all|pinned|archived) | notebook:<id>
    noteEdit: true, // notes inspector: true = edit, false = markdown preview
    calView: "month", // month | week | agenda
    calAnchor: Date.now(), // the moment the calendar is centred on (in-memory)
    draft: null, // a seed for a new item (e.g. a clicked calendar slot)
    sel: null, // { domain, id } for the inspector
    sidebar: true,
  };
  function loadUi() {
    try {
      const s = JSON.parse(localStorage.getItem("hexis_ui") || "{}");
      if (DOMAINS.includes(s.domain)) ui.domain = s.domain;
      if (["list", "board", "matrix"].includes(s.taskView)) ui.taskView = s.taskView;
      if (["month", "week", "agenda"].includes(s.calView)) ui.calView = s.calView;
      if (s.taskFilter && s.taskFilter.kind) ui.taskFilter = s.taskFilter;
      if (s.noteFilter && s.noteFilter.kind) ui.noteFilter = s.noteFilter;
      if (typeof s.sidebar === "boolean") ui.sidebar = s.sidebar;
    } catch (_e) { /* defaults */ }
  }
  function saveUi() {
    try {
      localStorage.setItem("hexis_ui", JSON.stringify({
        domain: ui.domain, taskView: ui.taskView, calView: ui.calView,
        taskFilter: ui.taskFilter, noteFilter: ui.noteFilter, sidebar: ui.sidebar,
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
  function toTimeInput(ms) {
    if (!ms) return "";
    const d = new Date(ms), p = (n) => String(n).padStart(2, "0");
    return p(d.getHours()) + ":" + p(d.getMinutes());
  }
  function fromDateTimeInput(dateV, timeV) {
    if (!dateV) return null;
    const [y, m, d] = dateV.split("-").map(Number);
    const [hh, mm] = (timeV || "00:00").split(":").map(Number);
    return new Date(y, m - 1, d, hh || 0, mm || 0, 0, 0).getTime();
  }

  // ---- calendar date math (week starts Sunday) ---------------------------
  const WEEK_START = 0;
  function startOfDay(ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); }
  function addDays(ms, n) { const d = new Date(ms); d.setDate(d.getDate() + n); return d.getTime(); }
  function sameDay(a, b) { return startOfDay(a) === startOfDay(b); }
  function weekStart(ms) {
    const d = new Date(startOfDay(ms));
    const back = (d.getDay() - WEEK_START + 7) % 7;
    return addDays(d.getTime(), -back);
  }
  function monthGrid(anchorMs) {
    const a = new Date(anchorMs);
    const monthStart = new Date(a.getFullYear(), a.getMonth(), 1).getTime();
    const gridStart = weekStart(monthStart);
    const gridEnd = addDays(gridStart, 42); // 6 weeks
    return { gridStart, gridEnd, month: a.getMonth(), year: a.getFullYear() };
  }
  function calWindow() {
    if (ui.calView === "month") { const g = monthGrid(ui.calAnchor); return { from: g.gridStart, to: g.gridEnd }; }
    if (ui.calView === "week") { const s = weekStart(ui.calAnchor); return { from: s, to: addDays(s, 7) }; }
    const from = startOfDay(ui.calAnchor); return { from, to: addDays(from, 60) }; // agenda: 60-day horizon
  }
  function calColor(e) {
    if (e && e.colorArgb != null) return argb(e.colorArgb);
    const c = state.calendars.find((x) => x.id === (e && e.calendarId));
    return (c && argb(c.colorArgb)) || "var(--accent)";
  }

  // ---- markdown (tiny, safe: escape first, then format) ------------------
  function mdInline(s) {
    return s
      .replace(/`([^`]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, "$1<em>$2</em>")
      .replace(/~~([^~]+)~~/g, "<del>$1</del>")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  }
  function mdToHtml(src) {
    const lines = String(src || "").replace(/\r\n/g, "\n").split("\n");
    let html = "", i = 0, inUl = false, inOl = false;
    const closeLists = () => { if (inUl) { html += "</ul>"; inUl = false; } if (inOl) { html += "</ol>"; inOl = false; } };
    while (i < lines.length) {
      const raw = lines[i];
      if (/^\s*```/.test(raw)) {
        closeLists(); i++;
        let code = "";
        while (i < lines.length && !/^\s*```/.test(lines[i])) { code += esc(lines[i]) + "\n"; i++; }
        i++; html += "<pre><code>" + code + "</code></pre>"; continue;
      }
      const line = esc(raw);
      let m;
      if ((m = line.match(/^(#{1,6})\s+(.*)$/))) { closeLists(); const lv = m[1].length; html += "<h" + lv + ">" + mdInline(m[2]) + "</h" + lv + ">"; }
      else if (/^\s*[-*]\s+\[[ xX]\]\s+/.test(line)) {
        if (inOl) { html += "</ol>"; inOl = false; }
        if (!inUl) { html += '<ul class="md-tasks">'; inUl = true; }
        const done = /\[[xX]\]/.test(line.match(/\[[ xX]\]/)[0]);
        html += '<li class="md-task">' + (done ? "☑" : "☐") + " " + mdInline(line.replace(/^\s*[-*]\s+\[[ xX]\]\s+/, "")) + "</li>";
      }
      else if (/^\s*[-*]\s+/.test(line)) {
        if (inOl) { html += "</ol>"; inOl = false; }
        if (!inUl) { html += "<ul>"; inUl = true; }
        html += "<li>" + mdInline(line.replace(/^\s*[-*]\s+/, "")) + "</li>";
      }
      else if (/^\s*\d+\.\s+/.test(line)) {
        if (inUl) { html += "</ul>"; inUl = false; }
        if (!inOl) { html += "<ol>"; inOl = true; }
        html += "<li>" + mdInline(line.replace(/^\s*\d+\.\s+/, "")) + "</li>";
      }
      else if (/^\s*&gt;\s?/.test(line)) { closeLists(); html += "<blockquote>" + mdInline(line.replace(/^\s*&gt;\s?/, "")) + "</blockquote>"; }
      else if (/^\s*([-*_])\1\1+\s*$/.test(raw)) { closeLists(); html += "<hr>"; }
      else if (raw.trim() === "") { closeLists(); }
      else { closeLists(); html += "<p>" + mdInline(line) + "</p>"; }
      i++;
    }
    closeLists();
    return html;
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
    } else if (d === "notes") {
      const [notes, notebooks] = await Promise.all([query("notes", "list"), query("notes", "notebooks").catch(() => [])]);
      state.notes = notes; state.notebooks = notebooks;
    } else if (d === "calendar") {
      const w = calWindow();
      const [events, cals] = await Promise.all([
        query("calendar", "occurrences", { paramsJson: JSON.stringify({ from: w.from, to: w.to }) }),
        query("calendar", "calendars").catch(() => []),
      ]);
      state.calendar = events; state.calendars = cals;
    } else if (d === "time") {
      const [entries, acts] = await Promise.all([query("time", "list"), query("time", "activities").catch(() => [])]);
      state.time = entries; state.activities = acts;
    } else if (d === "habits") {
      const [habits, history] = await Promise.all([
        query("habits", "list"),
        query("habits", "history", { paramsJson: JSON.stringify({ days: 84 }) }).catch(() => []),
      ]);
      state.habits = habits; state.habitHistory = history;
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
    if (ui.domain === "notes") { sb.innerHTML = notesSidebar(); return; }
    if (ui.domain === "calendar") { sb.innerHTML = calendarSidebar(); return; }
    if (ui.domain === "time") { sb.innerHTML = timeSidebar(); return; }
    if (ui.domain === "habits") { sb.innerHTML = habitsSidebar(); return; }
  }
  function simpleSidebar(title, items, active) {
    return '<h2 class="sb-title">' + esc(title) + "</h2><ul class=\"sb-list\">" +
      items.map((i) => '<li class="sb-item active">' + esc(i[1]) + "</li>").join("") + "</ul>";
  }
  function notesSidebar() {
    const f = ui.noteFilter;
    const nonArch = state.notes.filter((n) => !n.archived);
    const smart = [
      ["all", "All notes", "&#x1F5C2;", nonArch.length],
      ["pinned", "Pinned", "&#x1F4CC;", nonArch.filter((n) => n.pinned).length],
      ["archived", "Archived", "&#x1F4E6;", state.notes.filter((n) => n.archived).length],
    ];
    const smartHtml = smart.map((s) => {
      const on = f.kind === "smart" && f.value === s[0];
      return '<li class="sb-item' + (on ? " active" : "") + '" data-nsmart="' + s[0] + '">' +
        '<span class="sb-ico">' + s[2] + '</span><span class="sb-label">' + s[1] + "</span>" +
        (s[3] ? '<span class="sb-count">' + s[3] + "</span>" : "") + "</li>";
    }).join("");
    const books = state.notebooks.map((b) => {
      const on = f.kind === "notebook" && f.value === b.id;
      const dot = argb(b.colorArgb);
      return '<li class="sb-item' + (on ? " active" : "") + '" data-notebook="' + esc(b.id) + '">' +
        '<span class="sb-ico">' + (b.emoji ? esc(b.emoji) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : "&#x2219;")) + "</span>" +
        '<span class="sb-label">' + esc(b.name) + "</span>" + (b.count ? '<span class="sb-count">' + b.count + "</span>" : "") + "</li>";
    }).join("");
    return '<h2 class="sb-title">Notes</h2><ul class="sb-list">' + smartHtml + "</ul>" +
      (books ? '<h3 class="sb-sub">Notebooks</h3><ul class="sb-list">' + books + "</ul>" : "");
  }
  function timeSidebar() {
    const run = runningEntry();
    const todayByAct = minutesByActivity(startOfToday(), Date.now());
    const acts = state.activities.slice().sort((a, b) => (todayByAct[b.id] || 0) - (todayByAct[a.id] || 0));
    const list = acts.length ? acts.map((a) => {
      const col = argb(a.colorArgb) || "var(--accent)";
      const running = run && run.activityId === a.id;
      const mins = todayByAct[a.id] || 0;
      return '<li class="sb-item act' + (running ? " running" : "") + '"' + (readOnly ? "" : ' data-start="' + esc(a.id) + '"') + ">" +
        '<span class="sb-ico"><span class="sb-dot" style="background:' + col + '"></span></span>' +
        '<span class="sb-label">' + (a.emoji ? esc(a.emoji) + " " : "") + esc(a.name) + "</span>" +
        '<span class="sb-count">' + (running ? "●" : (mins ? fmtDuration(mins) : (readOnly ? "" : "▶"))) + "</span></li>";
    }).join("") : '<li class="sb-item static"><span class="sb-label" style="color:var(--muted)">No activities yet</span></li>';
    return '<h2 class="sb-title">Activities</h2>' +
      (readOnly ? "" : '<p class="sb-hint">Tap one to start a timer.</p>') +
      '<ul class="sb-list">' + list + "</ul>";
  }
  function habitsSidebar() {
    const active = state.habits.filter((h) => !h.paused);
    const done = active.filter((h) => h.doneToday).length;
    return '<h2 class="sb-title">Habits</h2>' +
      '<div class="cal-today"><span class="cal-today-dow">Today</span><span class="cal-today-day">' + done + "/" + active.length + "</span>" +
      '<span class="cal-today-mon">habits done</span></div>';
  }
  function calendarSidebar() {
    const d = new Date(ui.calAnchor);
    const big = '<div class="cal-today" data-cal="today">' +
      '<span class="cal-today-dow">' + d.toLocaleDateString(undefined, { weekday: "long" }) + "</span>" +
      '<span class="cal-today-day">' + d.getDate() + "</span>" +
      '<span class="cal-today-mon">' + d.toLocaleDateString(undefined, { month: "long", year: "numeric" }) + "</span></div>";
    const newBtn = readOnly ? "" : '<button class="btn primary block" data-cal="new">New event</button>';
    const legend = state.calendars.length
      ? '<h3 class="sb-sub">Calendars</h3><ul class="sb-list">' + state.calendars.map((c) =>
        '<li class="sb-item static"><span class="sb-ico"><span class="sb-dot" style="background:' + (argb(c.colorArgb) || "var(--accent)") + '"></span></span>' +
        '<span class="sb-label">' + esc(c.name) + "</span>" + (c.isDefault ? '<span class="sb-count">default</span>' : "") + "</li>").join("") + "</ul>"
      : "";
    return '<h2 class="sb-title">Calendar</h2>' + big + newBtn + legend;
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
    stopClock(); // only the Time view with a running timer re-arms it
    if (ui.domain === "tasks") { head.innerHTML = tasksHead(); body.innerHTML = tasksMain(); wireTasksMain(); }
    else if (ui.domain === "notes") { head.innerHTML = headTitle(noteFilterLabel(), readOnly ? "" : '<button class="btn primary" data-new="note">New note</button>'); body.innerHTML = notesMain(); body.onclick = onNotesClick; }
    else if (ui.domain === "calendar") { head.innerHTML = calendarHead(); body.innerHTML = calendarMain(); wireCalendar(); }
    else if (ui.domain === "time") { head.innerHTML = headTitle("Time", ""); body.innerHTML = timeMain(); wireTime(); }
    else if (ui.domain === "habits") { head.innerHTML = headTitle("Habits", ""); body.innerHTML = habitsMain(); wireHabits(); }
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

  // ---- Notes (Keep-style masonry; markdown editor in inspector) -----------
  const NOTE_COLORS = [null, "#e5484d", "#f5a623", "#f6c744", "#1e9e64", "#30a0c0", "#5b57d9", "#9b59b6", "#8a93a3"];
  function filteredNotes() {
    const f = ui.noteFilter;
    if (f.kind === "notebook") return state.notes.filter((n) => n.notebookId === f.value && !n.archived);
    if (f.value === "archived") return state.notes.filter((n) => n.archived);
    const base = state.notes.filter((n) => !n.archived);
    if (f.value === "pinned") return base.filter((n) => n.pinned);
    return base;
  }
  function noteFilterLabel() {
    const f = ui.noteFilter;
    if (f.kind === "notebook") { const b = state.notebooks.find((x) => x.id === f.value); return b ? b.name : "Notebook"; }
    return { all: "Notes", pinned: "Pinned", archived: "Archived" }[f.value] || "Notes";
  }
  function notesMain() {
    const notes = filteredNotes();
    if (!notes.length) return emptyState("No notes here", readOnly ? "" : "Create one with “New note”.");
    const showGroups = ui.noteFilter.kind === "smart" && ui.noteFilter.value === "all";
    if (showGroups) {
      const pinned = notes.filter((n) => n.pinned).sort((a, b) => b.updatedAt - a.updatedAt);
      const others = notes.filter((n) => !n.pinned).sort((a, b) => b.updatedAt - a.updatedAt);
      let html = "";
      if (pinned.length) html += '<h3 class="group-h">Pinned</h3><div class="ncards">' + pinned.map(noteCard).join("") + "</div>";
      if (others.length) html += (pinned.length ? '<h3 class="group-h">Others</h3>' : "") + '<div class="ncards">' + others.map(noteCard).join("") + "</div>";
      return html;
    }
    const sorted = notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.updatedAt - a.updatedAt));
    return '<div class="ncards">' + sorted.map(noteCard).join("") + "</div>";
  }
  function noteCard(n) {
    const sel = ui.sel && ui.sel.domain === "notes" && ui.sel.id === n.id;
    const tint = argb(n.colorArgb);
    const style = tint ? ' style="--nc:' + tint + '"' : "";
    const preview = (n.body || "").slice(0, 500);
    return '<article class="ncard' + (tint ? " tinted" : "") + (sel ? " sel" : "") + '" data-id="' + esc(n.id) + '" data-act="open"' + style + ">" +
      (n.pinned ? '<span class="pin">&#x1F4CC;</span>' : "") +
      (n.coverEmoji ? '<div class="ncard-emoji">' + esc(n.coverEmoji) + "</div>" : "") +
      (n.title ? "<h4>" + esc(n.title) + "</h4>" : "") +
      (preview ? '<div class="ncard-md">' + mdToHtml(preview) + "</div>" : (n.title ? "" : '<p class="ncard-untitled">Untitled</p>')) +
      "</article>";
  }

  // ---- Calendar (month / week / agenda + create/edit/delete) -------------
  function calendarHead() {
    const views = [["month", "Month"], ["week", "Week"], ["agenda", "Agenda"]];
    const seg = views.map((v) => '<button class="seg' + (ui.calView === v[0] ? " on" : "") + '" data-calview="' + v[0] + '">' + v[1] + "</button>").join("");
    const nav = '<div class="cal-nav"><button class="icon-btn" data-cal="prev" title="Previous">&#x2039;</button>' +
      '<button class="btn ghost" data-cal="jump-today">Today</button>' +
      '<button class="icon-btn" data-cal="next" title="Next">&#x203A;</button></div>';
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar">&#9776;</button>' +
      '<h1 class="main-title">' + esc(calTitle()) + "</h1>" + nav +
      '<div class="segmented">' + seg + "</div><span class=\"head-spacer\"></span>" +
      (readOnly ? "" : '<button class="btn primary" data-cal="new">New event</button>');
  }
  function calTitle() {
    const a = new Date(ui.calAnchor);
    if (ui.calView === "month") return a.toLocaleDateString(undefined, { month: "long", year: "numeric" });
    if (ui.calView === "week") {
      const s = new Date(weekStart(ui.calAnchor)), e = new Date(addDays(s.getTime(), 6));
      const sameMonth = s.getMonth() === e.getMonth();
      const sTxt = s.toLocaleDateString(undefined, { month: "short", day: "numeric" });
      const eTxt = e.toLocaleDateString(undefined, sameMonth ? { day: "numeric", year: "numeric" } : { month: "short", day: "numeric", year: "numeric" });
      return sTxt + " – " + eTxt;
    }
    return "Agenda";
  }
  function calendarMain() {
    if (ui.calView === "month") return monthView();
    if (ui.calView === "week") return weekView();
    return agendaView();
  }

  // Events (occurrences) that intersect a given day.
  function eventsOnDay(dayMs) {
    const d0 = startOfDay(dayMs), d1 = addDays(d0, 1);
    return state.calendar.filter((e) => (e.startMillis < d1) && ((e.endMillis || e.startMillis) >= d0 || sameDay(e.startMillis, d0)))
      .sort((a, b) => (a.allDay === b.allDay ? a.startMillis - b.startMillis : (a.allDay ? -1 : 1)));
  }
  function eChip(e, withTime) {
    const t = e.allDay ? "" : (withTime ? fmtTime(e.startMillis) + " " : "");
    return '<button class="echip" data-id="' + esc(e.id) + '" style="--ec:' + calColor(e) + '" title="' + esc(e.title || "") + '">' +
      '<span class="echip-dot"></span><span class="echip-t">' + esc(t) + esc(e.title || "Untitled") + "</span>" +
      (e.recurring ? '<span class="echip-r">&#x21BB;</span>' : "") + "</button>";
  }

  function monthView() {
    const g = monthGrid(ui.calAnchor);
    const dow = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];
    const head = '<div class="mv-dow">' + dow.map((d) => "<span>" + d + "</span>").join("") + "</div>";
    const today0 = startOfToday();
    let cells = "";
    for (let i = 0; i < 42; i++) {
      const day = addDays(g.gridStart, i);
      const dd = new Date(day);
      const inMonth = dd.getMonth() === g.month;
      const isToday = day === today0;
      const evs = eventsOnDay(day);
      const shown = evs.slice(0, 4), extra = evs.length - shown.length;
      cells += '<div class="mv-cell' + (inMonth ? "" : " dim") + (isToday ? " today" : "") + '" data-day="' + day + '">' +
        '<div class="mv-daynum">' + dd.getDate() + "</div>" +
        '<div class="mv-evs">' + shown.map((e) => eChip(e, true)).join("") +
        (extra > 0 ? '<span class="mv-more">+' + extra + " more</span>" : "") + "</div></div>";
    }
    return head + '<div class="mv-grid">' + cells + "</div>";
  }

  function weekView() {
    const ws = weekStart(ui.calAnchor);
    const days = []; for (let i = 0; i < 7; i++) days.push(addDays(ws, i));
    const today0 = startOfToday();
    const HOUR = 44; // px per hour
    // Column headers
    const heads = days.map((day) => {
      const d = new Date(day);
      return '<div class="wv-colh' + (day === today0 ? " today" : "") + '" data-day="' + day + '">' +
        '<span class="wv-dow">' + d.toLocaleDateString(undefined, { weekday: "short" }) + "</span>" +
        '<span class="wv-dnum">' + d.getDate() + "</span></div>";
    }).join("");
    // All-day row
    const allDayCells = days.map((day) => {
      const evs = eventsOnDay(day).filter((e) => e.allDay);
      return '<div class="wv-allday-cell" data-day="' + day + '">' + evs.map((e) => eChip(e, false)).join("") + "</div>";
    }).join("");
    // Hour axis
    let axis = "";
    for (let h = 0; h < 24; h++) axis += '<div class="wv-hr" style="height:' + HOUR + 'px"><span>' + hourLabel(h) + "</span></div>";
    // Day columns with timed events
    const cols = days.map((day) => {
      const d0 = startOfDay(day), d1 = addDays(d0, 1);
      const timed = eventsOnDay(day).filter((e) => !e.allDay);
      const laid = layoutDay(timed, d0, d1);
      const blocks = laid.map((p) => {
        const e = p.e;
        const top = (p.top / 60) * HOUR, h = Math.max(16, (p.dur / 60) * HOUR);
        return '<button class="wv-ev" data-id="' + esc(e.id) + '" style="top:' + top + "px;height:" + h + "px;left:" +
          (p.lane * (100 / p.lanes)).toFixed(2) + "%;width:" + (100 / p.lanes).toFixed(2) + "%;--ec:" + calColor(e) + '">' +
          '<span class="wv-ev-t">' + esc(e.title || "Untitled") + "</span>" +
          '<span class="wv-ev-m">' + esc(fmtTime(e.startMillis)) + "</span></button>";
      }).join("");
      const nowLine = (day === today0) ? nowIndicator(HOUR) : "";
      return '<div class="wv-col" data-day="' + day + '" style="height:' + (24 * HOUR) + 'px">' + hourLines(HOUR) + nowLine + blocks + "</div>";
    }).join("");
    return '<div class="wv">' +
      '<div class="wv-head"><div class="wv-corner"></div>' + heads + "</div>" +
      '<div class="wv-alldays"><div class="wv-allday-label">all-day</div>' + allDayCells + "</div>" +
      '<div class="wv-body" id="wvBody"><div class="wv-axis">' + axis + "</div>" + cols + "</div></div>";
  }
  function hourLabel(h) {
    if (h === 0) return "12 AM"; if (h === 12) return "12 PM";
    return (h < 12 ? h + " AM" : (h - 12) + " PM");
  }
  function hourLines(HOUR) { let s = ""; for (let h = 0; h < 24; h++) s += '<div class="wv-line" style="top:' + (h * HOUR) + 'px"></div>'; return s; }
  function nowIndicator(HOUR) {
    const now = new Date(); const mins = now.getHours() * 60 + now.getMinutes();
    return '<div class="wv-now" style="top:' + ((mins / 60) * HOUR).toFixed(1) + 'px"></div>';
  }
  // Greedy lane packing so overlapping events sit side-by-side.
  function layoutDay(events, d0, d1) {
    const items = events.map((e) => {
      const s = Math.max(e.startMillis, d0), en = Math.min(e.endMillis || (e.startMillis + 3600000), d1);
      return { e, top: (s - d0) / 60000, dur: Math.max(15, (en - s) / 60000) };
    }).sort((a, b) => a.top - b.top);
    // cluster by overlap, assign lanes within each cluster
    let i = 0;
    while (i < items.length) {
      let j = i, clusterEnd = items[i].top + items[i].dur;
      const cluster = [items[i]];
      while (j + 1 < items.length && items[j + 1].top < clusterEnd) {
        j++; cluster.push(items[j]); clusterEnd = Math.max(clusterEnd, items[j].top + items[j].dur);
      }
      const laneEnds = [];
      cluster.forEach((it) => {
        let lane = laneEnds.findIndex((end) => end <= it.top);
        if (lane === -1) { lane = laneEnds.length; laneEnds.push(0); }
        laneEnds[lane] = it.top + it.dur; it.lane = lane;
      });
      const lanes = laneEnds.length;
      cluster.forEach((it) => { it.lanes = lanes; });
      i = j + 1;
    }
    return items;
  }

  function agendaView() {
    const now = Date.now();
    const evs = state.calendar.filter((e) => (e.endMillis || e.startMillis) >= startOfToday()).sort((a, b) => a.startMillis - b.startMillis);
    if (!evs.length) return emptyState("Nothing scheduled", readOnly ? "" : "Create an event to see it here.");
    // group by day
    const groups = [];
    let cur = null;
    evs.forEach((e) => {
      const d0 = startOfDay(e.startMillis);
      if (!cur || cur.day !== d0) { cur = { day: d0, items: [] }; groups.push(cur); }
      cur.items.push(e);
    });
    return '<div class="agenda">' + groups.map((g) => {
      const d = new Date(g.day);
      const isToday = g.day === startOfToday();
      const label = (isToday ? "Today · " : "") + d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
      return '<section class="ag-day"><h3 class="ag-h' + (isToday ? " today" : "") + '">' + esc(label) + "</h3>" +
        '<ul class="ag-list">' + g.items.map((e) => {
          const when = e.allDay ? "All day" : fmtTime(e.startMillis) + " – " + fmtTime(e.endMillis);
          return '<li class="ag-row" data-id="' + esc(e.id) + '"><span class="ag-time">' + esc(when) + "</span>" +
            '<span class="ag-bar" style="background:' + calColor(e) + '"></span>' +
            '<span class="ag-main"><span class="ag-title">' + esc(e.title || "Untitled") + (e.recurring ? ' <span class="chip">&#x21BB;</span>' : "") + "</span>" +
            (e.location || e.calendarName ? '<span class="ag-meta">' + esc([e.location, e.calendarName].filter(Boolean).join(" · ")) + "</span>" : "") +
            "</span></li>";
        }).join("") + "</ul></section>";
    }).join("") + "</div>";
  }
  // ---- Time (dashboard: running timer + tiles + 14-day chart + by-activity + recent) --------
  function runningEntry() { return state.time.find((e) => e.running) || null; }
  function minutesByActivity(from, to) {
    const map = {};
    state.time.forEach((e) => {
      if (e.startMillis >= from && e.startMillis < to) map[e.activityId] = (map[e.activityId] || 0) + (e.minutes || 0);
    });
    return map;
  }
  function dailyMinutes(days) {
    const t0 = startOfToday(), out = [];
    for (let i = days - 1; i >= 0; i--) {
      const d0 = addDays(t0, -i), d1 = addDays(d0, 1);
      const min = state.time.filter((e) => e.startMillis >= d0 && e.startMillis < d1).reduce((s, e) => s + (e.minutes || 0), 0);
      out.push({ day: d0, min: min });
    }
    return out;
  }
  function timeMain() {
    const run = runningEntry();
    const t0 = startOfToday();
    const weekStartMs = weekStart(Date.now());
    const todayMin = state.time.filter((e) => e.startMillis >= t0).reduce((s, e) => s + (e.minutes || 0), 0);
    const weekMin = state.time.filter((e) => e.startMillis >= weekStartMs).reduce((s, e) => s + (e.minutes || 0), 0);
    const todaySessions = state.time.filter((e) => e.startMillis >= t0).length;
    const runBanner = run
      ? '<div class="run-banner"><span class="run-pulse"></span>' +
        '<div class="run-info"><span class="run-act">' + esc(run.activityName || "Tracking") + "</span>" +
        '<span class="run-elapsed" id="runElapsed" data-start="' + run.startMillis + '">' + esc(fmtDuration(run.minutes)) + "</span></div>" +
        (readOnly ? "" : '<button class="btn danger solid" data-stop="' + esc(run.id) + '">Stop</button>') + "</div>"
      : (readOnly || !state.activities.length ? "" :
        '<div class="run-banner idle"><span class="run-info"><span class="run-act">Nothing tracking</span>' +
        '<span class="run-elapsed muted">Start an activity from the sidebar</span></span></div>');
    const tiles = '<div class="tiles">' +
      tile("Today", fmtDuration(todayMin), "tracked") +
      tile("This week", fmtDuration(weekMin), "tracked") +
      tile("Sessions", String(todaySessions), "today") + "</div>";
    // 14-day chart
    const series = dailyMinutes(14);
    const max = Math.max(60, ...series.map((d) => d.min));
    const bars = series.map((d) => {
      const h = Math.round((d.min / max) * 100);
      const dd = new Date(d.day);
      const lbl = dd.getDate();
      const isToday = d.day === t0;
      return '<div class="bar-col" title="' + esc(fmtDate(d.day) + " · " + fmtDuration(d.min)) + '">' +
        '<div class="bar-track"><div class="bar-fill' + (isToday ? " today" : "") + '" style="height:' + h + '%"></div></div>' +
        '<span class="bar-lbl">' + lbl + "</span></div>";
    }).join("");
    const chart = '<section class="panel"><h3 class="panel-h">Last 14 days</h3><div class="barchart">' + bars + "</div></section>";
    // by-activity this week
    const byAct = minutesByActivity(weekStartMs, Date.now());
    const actRows = state.activities.map((a) => ({ a: a, min: byAct[a.id] || 0 })).filter((x) => x.min > 0).sort((x, y) => y.min - x.min);
    const maxAct = Math.max(1, ...actRows.map((x) => x.min));
    const byActHtml = actRows.length ? '<section class="panel"><h3 class="panel-h">This week by activity</h3><div class="actbars">' +
      actRows.map((x) => {
        const col = argb(x.a.colorArgb) || "var(--accent)";
        return '<div class="actbar"><span class="actbar-name">' + (x.a.emoji ? esc(x.a.emoji) + " " : "") + esc(x.a.name) + "</span>" +
          '<div class="actbar-track"><div class="actbar-fill" style="width:' + Math.round((x.min / maxAct) * 100) + "%;background:" + col + '"></div></div>' +
          '<span class="actbar-val">' + esc(fmtDuration(x.min)) + "</span></div>";
      }).join("") + "</div></section>" : "";
    // recent entries
    const recent = state.time.slice(0, 40);
    const recentHtml = recent.length ? '<section class="panel"><h3 class="panel-h">Recent</h3><ul class="elist">' +
      recent.map((e) => {
        const col = argb((state.activities.find((a) => a.id === e.activityId) || {}).colorArgb) || "var(--accent)";
        return '<li class="erow"><span class="cdot" style="background:' + col + '"></span>' +
          '<span class="einfo"><span class="etitle">' + esc(e.activityName || "Activity") +
          (e.running ? ' <span class="chip live">● live</span>' : "") + "</span>" +
          '<span class="emeta">' + esc(fmtDateTime(e.startMillis)) + (e.note ? " · " + esc(e.note) : "") + "</span></span>" +
          '<span class="dur">' + esc(fmtDuration(e.minutes)) + "</span></li>";
      }).join("") + "</ul></section>" : "";
    if (!state.time.length && !state.activities.length) return runBanner + emptyState("No time tracked yet", "Create activities on your phone, then start a timer here.");
    return runBanner + tiles + chart + byActHtml + recentHtml;
  }
  function tile(label, value, sub) {
    return '<div class="tile"><span class="tile-label">' + esc(label) + '</span><span class="tile-value">' + esc(value) + "</span>" +
      '<span class="tile-sub">' + esc(sub) + "</span></div>";
  }

  // ---- Habits (today check-in cards + 12-week heatmap) --------------------
  function todayEpochDay() { const n = new Date(); return Math.floor(Date.UTC(n.getFullYear(), n.getMonth(), n.getDate()) / DAY); }
  function epochDayToDate(day) { const d = new Date(day * DAY); return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()); }
  function habitsMain() {
    const habits = state.habits;
    if (!habits.length) return emptyState("No habits", "Create habits on your phone — they'll appear here to check in and review.");
    const active = habits.filter((h) => !h.paused);
    const paused = habits.filter((h) => h.paused);
    const cards = '<div class="hcards">' + active.map(habitCard).join("") + "</div>";
    // heatmap
    const DAYS = 84;
    const today = todayEpochDay();
    const cell = {};
    state.habitHistory.forEach((c) => { cell[c.habitId + ":" + c.epochDay] = c; });
    const rows = active.map((h) => heatRow(h, today, DAYS, cell)).join("");
    const heat = active.length ? '<section class="panel"><h3 class="panel-h">Last 12 weeks</h3><div class="heatmap">' + rows + "</div></section>" : "";
    const pausedHtml = paused.length ? '<h3 class="group-h">Paused</h3><div class="hcards">' + paused.map(habitCard).join("") + "</div>" : "";
    return cards + heat + pausedHtml;
  }
  function habitCard(h) {
    const done = h.doneToday;
    const multi = h.targetPerDay > 1;
    const pct = multi ? Math.min(100, Math.round((h.todayCount / h.targetPerDay) * 100)) : (done ? 100 : 0);
    const col = argb(h.colorArgb) || "var(--accent)";
    const sub = h.paused ? "Paused" : multi ? (h.todayCount + " / " + h.targetPerDay + (h.unit ? " " + h.unit : "")) : (done ? "Done today" : "Tap to check in");
    const tag = readOnly ? "div" : "button";
    return "<" + tag + ' class="hcard' + (done ? " done" : "") + (h.paused ? " paused" : "") + '"' + (readOnly ? "" : ' data-checkin="' + esc(h.id) + '"') + ' style="--hc:' + col + '">' +
      '<span class="hcard-ring" style="--pct:' + pct + '"><span class="hcard-check">' + (done ? "✓" : (h.emoji ? esc(h.emoji) : "")) + "</span></span>" +
      '<span class="hcard-main"><span class="hcard-name">' + esc(h.name) + "</span>" +
      '<span class="hcard-sub">' + esc(sub) + "</span></span></" + tag + ">";
  }
  function heatRow(h, today, days, cell) {
    const col = argb(h.colorArgb) || "var(--accent)";
    let cells = "";
    for (let i = days - 1; i >= 0; i--) {
      const day = today - i;
      const c = cell[h.id + ":" + day];
      let lvl = 0;
      if (c) {
        if (c.done) { const t = Math.max(1, h.targetPerDay); lvl = h.targetPerDay > 1 ? Math.min(4, 1 + Math.floor((c.count / t) * 3)) : 4; }
        else lvl = 1;
      }
      const title = epochDayToDate(day).toLocaleDateString(undefined, { month: "short", day: "numeric" }) + (c && c.done ? " ✓" : "");
      cells += '<span class="hcell l' + lvl + '" title="' + esc(title) + '"></span>';
    }
    return '<div class="heatrow" style="--hc:' + col + '"><span class="heatrow-name">' + (h.emoji ? esc(h.emoji) + " " : "") + esc(h.name) + "</span>" +
      '<div class="heatcells">' + cells + "</div></div>";
  }

  function emptyState(title, sub) {
    return '<div class="empty-state"><div class="empty-ic">&#9671;</div><h3>' + esc(title) + "</h3>" +
      (sub ? "<p>" + esc(sub) + "</p>" : "") + "</div>";
  }

  // ============================================================== inspector
  function openInspector(domain, id) { ui.sel = { domain, id }; app().dataset.inspector = "open"; el("inspector").hidden = false; renderInspector(); if (window.innerWidth < 1100) el("scrim").hidden = false; }
  function closeInspector() { ui.sel = null; ui.draft = null; app().dataset.inspector = "closed"; el("inspector").hidden = true; el("scrim").hidden = true; document.querySelectorAll(".trow.sel,.ncard.sel").forEach((n) => n.classList.remove("sel")); }
  function renderInspector() {
    if (!ui.sel) return;
    const ins = el("inspector");
    if (ui.sel.domain === "tasks") {
      const t = state.tasks.find((x) => x.id === ui.sel.id);
      if (!t) { closeInspector(); return; }
      ins.innerHTML = taskInspector(t);
      wireTaskInspector(t);
    } else if (ui.sel.domain === "notes") {
      if (ui.sel.id === "__new__") ui.noteEdit = true;
      const n = ui.sel.id === "__new__"
        ? { id: "", title: "", body: "", pinned: false, archived: false, colorArgb: null, coverEmoji: "" }
        : state.notes.find((x) => x.id === ui.sel.id);
      if (!n) { closeInspector(); return; }
      ins.innerHTML = noteInspector(n);
      wireNoteInspector(n);
    } else if (ui.sel.domain === "calendar") {
      const ev = ui.sel.id === "__new__" ? draftEvent() : state.calendar.find((x) => x.id === ui.sel.id);
      if (!ev) { closeInspector(); return; }
      ins.innerHTML = eventInspector(ev);
      wireEventInspector(ev);
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
    const cur = n.colorArgb != null ? argb(n.colorArgb) : null;
    const sw = readOnly ? "" : '<div class="swatches">' + NOTE_COLORS.map((c) => {
      const on = (c === cur) || (c === null && cur === null);
      return '<button type="button" class="swatch' + (c ? "" : " none") + (on ? " on" : "") + '" data-color="' + (c || "") + '"' +
        (c ? ' style="background:' + c + '"' : "") + ' aria-label="' + (c || "No colour") + '">' + (c ? "" : "⊘") + "</button>";
    }).join("") + "</div>";
    const seg = '<div class="segmented sm"><button type="button" class="seg' + (ui.noteEdit ? " on" : "") + '" data-nmode="edit">Edit</button>' +
      '<button type="button" class="seg' + (!ui.noteEdit ? " on" : "") + '" data-nmode="preview">Preview</button></div>';
    const editor = '<div class="note-editor" id="noteEditor" data-mode="' + (ui.noteEdit ? "edit" : "preview") + '">' +
      '<textarea id="iBody" rows="14" placeholder="Write in markdown…"' + ro + ">" + esc(n.body) + "</textarea>" +
      '<div class="md-preview" id="iPreview">' + (n.body ? mdToHtml(n.body) : '<p class="md-empty">Nothing to preview</p>') + "</div></div>";
    return inspectorHead(isNew ? "New note" : "Note") +
      '<div class="ins-body">' +
      '<div class="note-emoji-row">' +
      '<input id="iEmoji" class="emoji-in" type="text" maxlength="2" value="' + esc(n.coverEmoji || "") + '" placeholder="☺"' + ro + ">" +
      '<input id="iTitle" class="note-title-in" type="text" value="' + esc(n.title) + '" placeholder="Title"' + ro + "></div>" +
      sw +
      '<div class="note-body-head">' + seg +
      '<label class="chk-inline"><input id="iPin" type="checkbox"' + (n.pinned ? " checked" : "") + ro + "> Pin</label></div>" +
      editor +
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
    loadAttachments(n.id || null);
    const ins = el("inspector");
    // Edit / Preview toggle — done in place so unsaved edits survive the switch.
    ins.querySelectorAll(".seg[data-nmode]").forEach((b) => b.onclick = () => {
      const toPreview = b.dataset.nmode === "preview";
      ui.noteEdit = !toPreview;
      if (toPreview) { const t = el("iBody").value; el("iPreview").innerHTML = t.trim() ? mdToHtml(t) : '<p class="md-empty">Nothing to preview</p>'; }
      el("noteEditor").dataset.mode = toPreview ? "preview" : "edit";
      ins.querySelectorAll(".seg[data-nmode]").forEach((x) => x.classList.toggle("on", (x.dataset.nmode === "edit") === !toPreview));
      if (!toPreview) el("iBody").focus();
    });
    if (readOnly) return;
    ins.querySelectorAll(".swatch").forEach((s) => s.onclick = () => {
      ins.querySelectorAll(".swatch").forEach((x) => x.classList.remove("on")); s.classList.add("on");
    });
    el("iSave").onclick = async () => {
      const swOn = ins.querySelector(".swatch.on");
      const hex = swOn ? swOn.dataset.color : "";
      const colorArgb = hex ? (0xFF000000 + parseInt(hex.slice(1), 16)) : null;
      try {
        const res = await mutate("notes", "upsert", {
          id: n.id, title: el("iTitle").value, body: el("iBody").value,
          pinned: el("iPin").checked, colorArgb: colorArgb, coverEmoji: el("iEmoji").value,
        });
        await reloadDomain("notes");
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

  // ---- calendar events ---------------------------------------------------
  function wireCalendar() {
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = toggleSidebar;
    const head = el("mainHead");
    head.querySelectorAll(".seg[data-calview]").forEach((b) => b.onclick = () => { ui.calView = b.dataset.calview; saveUi(); reloadThenRender("calendar"); });
    head.querySelectorAll("[data-cal]").forEach((b) => b.onclick = () => onCalNav(b.dataset.cal));
    const body = el("mainBody");
    body.onclick = onCalendarClick;
    if (ui.calView === "week") { const wv = el("wvBody"); if (wv) wv.scrollTop = 7 * 44 - 14; }
  }
  function onCalNav(cmd) {
    if (cmd === "new") { newEvent(null); return; }
    if (cmd === "prev" || cmd === "next") {
      const dir = cmd === "next" ? 1 : -1;
      if (ui.calView === "month") { const a = new Date(ui.calAnchor); a.setDate(1); a.setMonth(a.getMonth() + dir); ui.calAnchor = a.getTime(); }
      else if (ui.calView === "week") ui.calAnchor = addDays(ui.calAnchor, 7 * dir);
      else ui.calAnchor = addDays(ui.calAnchor, 14 * dir);
      reloadThenRender("calendar");
    } else if (cmd === "jump-today" || cmd === "today") {
      ui.calAnchor = Date.now(); reloadThenRender("calendar");
    }
  }
  async function reloadThenRender(d) {
    try { await reloadDomain(d); } catch (e) { toast(e.message); }
    render();
  }
  function onCalendarClick(e) {
    const more = e.target.closest(".mv-more");
    if (more) { const cell = more.closest("[data-day]"); if (cell) { ui.calView = "agenda"; ui.calAnchor = Number(cell.dataset.day); saveUi(); reloadThenRender("calendar"); } return; }
    const chip = e.target.closest(".echip,.wv-ev,.ag-row");
    if (chip && chip.dataset.id) { openInspector("calendar", chip.dataset.id); return; }
    if (readOnly) return;
    const dayEl = e.target.closest("[data-day]");
    if (!dayEl) return;
    const day = Number(dayEl.dataset.day);
    if (dayEl.classList.contains("wv-col")) {
      const rect = dayEl.getBoundingClientRect();
      const hour = Math.max(0, Math.min(22, Math.floor((e.clientY - rect.top) / 44)));
      const start = new Date(day); start.setHours(hour, 0, 0, 0);
      newEvent({ start: start.getTime(), end: start.getTime() + 3600000, allDay: false });
    } else if (dayEl.classList.contains("wv-allday-cell")) {
      newEvent({ day, allDay: true });
    } else {
      newEvent({ day });
    }
  }
  function newEvent(seed) { ui.draft = seed || {}; openInspector("calendar", "__new__"); }
  function draftEvent() {
    const s = ui.draft || {};
    const allDay = !!s.allDay;
    let start, end;
    if (s.start) { start = s.start; end = s.end || (s.start + 3600000); }
    else {
      const base = s.day != null ? s.day : ui.calAnchor;
      const d = new Date(base);
      if (allDay) { d.setHours(0, 0, 0, 0); start = d.getTime(); end = start; }
      else { d.setHours(9, 0, 0, 0); start = d.getTime(); end = start + 3600000; }
    }
    const defCal = (state.calendars.find((c) => c.isDefault) || state.calendars[0] || {}).id || "";
    return { id: "", title: "", calendarId: defCal, location: "", notes: "", startMillis: start, endMillis: end, allDay: allDay, recurring: false };
  }
  function eventInspector(e) {
    const ro = readOnly ? " disabled" : "";
    const isNew = !e.id;
    const recurring = !!e.recurring;
    const timeRo = (readOnly || recurring) ? " disabled" : "";
    const calOpts = state.calendars.map((c) =>
      '<option value="' + esc(c.id) + '"' + (c.id === e.calendarId ? " selected" : "") + ">" + esc(c.name) + "</option>").join("");
    const hid = e.allDay ? " hidden" : "";
    return inspectorHead(isNew ? "New event" : "Event") +
      '<div class="ins-body">' +
      '<label class="fld"><span>Title</span><input id="iTitle" type="text" value="' + esc(e.title || "") + '"' + ro + "></label>" +
      (state.calendars.length ? '<label class="fld"><span>Calendar</span><select id="iCal"' + ro + ">" + calOpts + "</select></label>" : "") +
      (recurring ? '<p class="ins-note">&#x21BB; Repeats — text changes save to the whole series. Edit the times on your phone.</p>' : "") +
      '<label class="fld chk"><input id="iAllDay" type="checkbox"' + (e.allDay ? " checked" : "") + timeRo + "> All-day</label>" +
      '<div class="fld-row">' +
      '<label class="fld"><span>Starts</span><input id="iSDate" type="date" value="' + esc(toDateInput(e.startMillis)) + '"' + timeRo + "></label>" +
      '<label class="fld time' + hid + '" id="iSTimeWrap"><span>at</span><input id="iSTime" type="time" value="' + esc(toTimeInput(e.startMillis)) + '"' + timeRo + "></label>" +
      "</div>" +
      '<div class="fld-row">' +
      '<label class="fld"><span>Ends</span><input id="iEDate" type="date" value="' + esc(toDateInput(e.endMillis || e.startMillis)) + '"' + timeRo + "></label>" +
      '<label class="fld time' + hid + '" id="iETimeWrap"><span>at</span><input id="iETime" type="time" value="' + esc(toTimeInput(e.endMillis || e.startMillis)) + '"' + timeRo + "></label>" +
      "</div>" +
      '<label class="fld"><span>Location</span><input id="iLoc" type="text" value="' + esc(e.location || "") + '"' + ro + "></label>" +
      '<label class="fld grow"><span>Notes</span><textarea id="iNotes" rows="4"' + ro + ">" + esc(e.notes || "") + "</textarea></label>" +
      (readOnly ? "" :
        '<div class="ins-actions">' + (isNew ? "" : '<button class="btn danger" id="iDelete">Delete</button>') +
        '<span class="spacer"></span><button class="btn primary" id="iSave">Save</button></div>') +
      "</div>";
  }
  function wireEventInspector(e) {
    el("insClose").onclick = closeInspector;
    if (readOnly) return;
    const recurring = !!e.recurring;
    const allDayBox = el("iAllDay");
    if (allDayBox && !recurring) allDayBox.onchange = () => {
      const on = allDayBox.checked;
      el("iSTimeWrap").classList.toggle("hidden", on);
      el("iETimeWrap").classList.toggle("hidden", on);
    };
    el("iSave").onclick = async () => {
      const title = el("iTitle").value.trim();
      if (!title) { toast("Title can't be empty"); return; }
      const isAllDay = allDayBox ? allDayBox.checked : e.allDay;
      let startMillis, endMillis;
      if (recurring) { startMillis = e.startMillis; endMillis = e.endMillis; }
      else if (isAllDay) {
        startMillis = fromDateTimeInput(el("iSDate").value, "00:00");
        endMillis = fromDateTimeInput(el("iEDate").value || el("iSDate").value, "00:00");
      } else {
        startMillis = fromDateTimeInput(el("iSDate").value, el("iSTime").value);
        endMillis = fromDateTimeInput(el("iEDate").value || el("iSDate").value, el("iETime").value);
        if (startMillis && endMillis && endMillis <= startMillis) endMillis = startMillis + 3600000;
      }
      const now = Date.now();
      const payload = {
        id: e.id, title,
        calendarId: el("iCal") ? el("iCal").value : (e.calendarId || ""),
        location: el("iLoc").value, notes: el("iNotes").value,
        startMillis: startMillis || now, endMillis: endMillis || (startMillis || now) + 3600000,
        allDay: isAllDay,
      };
      try {
        const res = await mutate("calendar", "upsert", payload);
        ui.draft = null;
        await reloadDomain("calendar");
        const newId = res && res.id ? res.id : e.id;
        ui.sel = newId ? { domain: "calendar", id: newId } : null;
        render(); toast("Saved");
      } catch (err) { toast(err.message); }
    };
    const del = el("iDelete");
    if (del) del.onclick = async () => {
      if (!confirm(recurring ? "Delete the whole repeating series?" : "Delete this event?")) return;
      try { await mutate("calendar", "delete", { id: e.id }); closeInspector(); await reloadDomain("calendar"); render(); } catch (err) { toast(err.message); }
    };
  }

  // ---- time: live clock + start/stop --------------------------------------
  let clockTimer = null;
  function startClock() { stopClock(); clockTimer = setInterval(updateRunClock, 1000); }
  function stopClock() { if (clockTimer) { clearInterval(clockTimer); clockTimer = null; } }
  function fmtClock(ms) {
    const s = Math.max(0, Math.floor(ms / 1000)), p = (n) => String(n).padStart(2, "0");
    const hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60;
    return hh > 0 ? hh + ":" + p(mm) + ":" + p(ss) : mm + ":" + p(ss);
  }
  function updateRunClock() {
    const elx = el("runElapsed");
    if (!elx) { stopClock(); return; }
    const start = Number(elx.dataset.start || 0);
    if (start) elx.textContent = fmtClock(Date.now() - start);
  }
  function wireTime() {
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = toggleSidebar;
    if (!readOnly) el("mainBody").onclick = (e) => {
      const stopBtn = e.target.closest("[data-stop]");
      if (stopBtn) stopTimer(stopBtn.dataset.stop);
    };
    const run = runningEntry();
    if (run) { const elx = el("runElapsed"); if (elx) elx.textContent = fmtClock(Date.now() - run.startMillis); startClock(); }
  }
  async function startTimer(activityId) {
    try { await mutate("time", "start", { activityId: activityId }); await reloadDomain("time"); render(); toast("Timer started"); } catch (err) { toast(err.message); }
  }
  async function stopTimer(id) {
    try { await mutate("time", "stop", { id: id || "" }); await reloadDomain("time"); render(); toast("Stopped"); } catch (err) { toast(err.message); }
  }

  // ---- habits: check-in tap -----------------------------------------------
  function wireHabits() {
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = toggleSidebar;
    if (readOnly) return;
    el("mainBody").onclick = async (e) => {
      const c = e.target.closest("[data-checkin]");
      if (!c) return;
      try { await mutate("habits", "checkin", { id: c.dataset.checkin }); await reloadDomain("habits"); render(); } catch (err) { toast(err.message); }
    };
  }

  function wireSidebar() {
    el("sidebar").addEventListener("click", (e) => {
      const startBtn = e.target.closest("[data-start]");
      if (startBtn && !readOnly) { startTimer(startBtn.dataset.start); return; }
      const calBtn = e.target.closest("[data-cal]");
      if (calBtn) { onCalNav(calBtn.dataset.cal); if (window.innerWidth < 960) { ui.sidebar = false; render(); } return; }
      const item = e.target.closest(".sb-item");
      if (!item || item.classList.contains("static")) return;
      if (item.dataset.smart) { ui.taskFilter = { kind: "smart", value: item.dataset.smart }; }
      else if (item.dataset.list) { ui.taskFilter = { kind: "list", value: item.dataset.list }; }
      else if (item.dataset.nsmart) { ui.noteFilter = { kind: "smart", value: item.dataset.nsmart }; }
      else if (item.dataset.notebook) { ui.noteFilter = { kind: "notebook", value: item.dataset.notebook }; }
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
