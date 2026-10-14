"use strict";
/*
 * Hexis Web — core framework. Exposes a single `window.H` namespace that the per-section view modules
 * (tasks.js, calendar.js, …) extend. Transport/crypto are unchanged from v0.2: every /api body and every
 * /live frame is AES-256-GCM over HKDF-SHA256 (WebCrypto-native), keyed by the per-device pairing key.
 *
 * The guiding principle of the parity rebuild: the web renders what the PHONE is configured to show. It
 * reads the core's display settings (H.settings) and mirrors the mobile app's icons, colours and logic
 * rather than inventing its own.
 */
(function () {
  const H = (window.H = window.H || {});
  const AEAD_INFO = "hexis-web-aead-v1";
  const IV_LEN = 12;
  const DAY = 86400000;

  // ============================================================== base64url + crypto
  function b64urlEncode(bytes) { let s = ""; for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, ""); }
  function b64urlDecode(str) { const s = str.replace(/-/g, "+").replace(/_/g, "/"); const bin = atob(s + "===".slice((s.length + 3) % 4)); const out = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i); return out; }
  let aeadKey = null;
  async function deriveKey(pairingKeyBytes) {
    const base = await crypto.subtle.importKey("raw", pairingKeyBytes, "HKDF", false, ["deriveBits"]);
    const bits = await crypto.subtle.deriveBits({ name: "HKDF", hash: "SHA-256", salt: new Uint8Array(32), info: new TextEncoder().encode(AEAD_INFO) }, base, 256);
    return crypto.subtle.importKey("raw", bits, "AES-GCM", false, ["encrypt", "decrypt"]);
  }
  async function sealText(text) {
    const iv = crypto.getRandomValues(new Uint8Array(IV_LEN));
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, tagLength: 128 }, aeadKey, new TextEncoder().encode(text)));
    const blob = new Uint8Array(IV_LEN + ct.length); blob.set(iv, 0); blob.set(ct, IV_LEN); return b64urlEncode(blob);
  }
  async function openTextBlob(blobB64) {
    const blob = b64urlDecode(blobB64);
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv: blob.slice(0, IV_LEN), tagLength: 128 }, aeadKey, blob.slice(IV_LEN));
    return new TextDecoder().decode(pt);
  }
  function nonce() { return b64urlEncode(crypto.getRandomValues(new Uint8Array(16))); }

  // ============================================================== api
  async function call(req, signal) {
    const body = await sealText(JSON.stringify(Object.assign({ ts: Date.now(), nonce: nonce() }, req)));
    const res = await fetch("api", { method: "POST", headers: { "Content-Type": "text/plain" }, body, signal });
    if (!res.ok) { let d = ""; try { d = (await res.text()).slice(0, 120); } catch (_e) { /**/ } throw new Error("HTTP " + res.status + (d ? " — " + d : "")); }
    return JSON.parse(await openTextBlob(await res.text()));
  }
  async function query(domain, op, extra) {
    const api = await call(Object.assign({ kind: "query", domain, op: op || "list", limit: 2000 }, extra || {}));
    if (!api.ok) throw new Error(api.error || "request failed");
    const page = api.dataJson ? JSON.parse(api.dataJson) : { payloadJson: "[]" };
    return JSON.parse(page.payloadJson || "[]");
  }
  async function queryOne(domain, op, extra) {
    const api = await call(Object.assign({ kind: "query", domain, op: op || "get" }, extra || {}));
    if (!api.ok) throw new Error(api.error || "request failed");
    const page = api.dataJson ? JSON.parse(api.dataJson) : { payloadJson: "null" };
    return JSON.parse(page.payloadJson || "null");
  }
  async function mutate(domain, op, payload) {
    const api = await call({ kind: "mutate", domain, op, payloadJson: JSON.stringify(payload || {}) });
    if (!api.ok) throw new Error(api.error || "save failed");
    return api.dataJson ? JSON.parse(api.dataJson) : null;
  }
  async function hello() { const api = await call({ kind: "hello" }); return api.ok && api.dataJson ? JSON.parse(api.dataJson) : { readOnly: false }; }
  H.api = { call, query, queryOne, mutate, hello };

  // ============================================================== state
  const DOMAINS = ["tasks", "calendar", "timeline", "matrix", "habits", "time", "notes", "focus"];
  H.DOMAINS = DOMAINS;
  H.DAY = DAY;
  H.state = {
    tasks: [], lists: [], folders: [], tags: [], contexts: [], flags: [], dependencies: [],
    notes: [], notebooks: [], calendar: [], calendars: [], time: [], activities: [], habits: [], habitHistory: [],
  };
  H.settings = defaultSettings();
  H.readOnly = false;
  let seen = {};

  function defaultSettings() {
    return {
      activeWorkspaceId: "default", weekStart: 0, timeFormat: "SYSTEM", timeZone: "", dayStartHour: 0, dayStartMinute: 0,
      density: "DEFAULT", advancedPriority: false, smartListVis: {}, smartListNames: {}, smartOrder: [],
      editorFieldTiers: {}, editorFieldOrder: [], editorFieldFolded: [],
      matrixImportanceThreshold: 4, matrixUrgencyThreshold: 4, matrixShowCompleted: false, matrixHideEmpty: false,
      matrixNames: [], matrixListFilter: [], matrixFolderFilter: [], matrixMaxDuration: 0, matrixOverdueOnly: false,
      matrixDateFilter: "all", matrixSort: "priority",
      calendarDefaultMode: "month", calendarRememberLast: true, calendarShowCompleted: false, calendarListFilter: [],
      notesNotebookMode: "folderTree", noteDefaultView: "grid", notesViewMode: "cards", notesSort: "updated",
      habitSort: "manual", habitGroupByCategory: true, strengthMeter: false, calmMode: false, forgivingStreaks: true, hideStreaks: false,
      timeGridColumns: 2,
    };
  }

  // Per-viewer UI state (persisted in localStorage; never settings the phone owns).
  H.ui = {
    domain: "tasks",
    taskView: "list", taskGroup: "date", taskSort: "manual", taskFilter: { kind: "smart", value: "today" }, outline: false, boardBy: "priority",
    noteFilter: { kind: "smart", value: "all" }, noteEdit: true,
    calView: "month", calAnchor: Date.now(),
    timeRange: "week", timeAnchor: Date.now(),
    sel: null, draft: null, sidebar: true,
  };
  H.loadUi = function () {
    try {
      const s = JSON.parse(localStorage.getItem("hexis_ui2") || "{}");
      Object.assign(H.ui, s);
      if (!DOMAINS.includes(H.ui.domain)) H.ui.domain = "tasks";
      H.ui.calAnchor = Date.now(); H.ui.timeAnchor = Date.now(); H.ui.sel = null; H.ui.draft = null;
    } catch (_e) { /* defaults */ }
  };
  H.saveUi = function () {
    try {
      const u = H.ui;
      localStorage.setItem("hexis_ui2", JSON.stringify({
        domain: u.domain, taskView: u.taskView, taskGroup: u.taskGroup, taskSort: u.taskSort, taskFilter: u.taskFilter,
        outline: u.outline, boardBy: u.boardBy, noteFilter: u.noteFilter, calView: u.calView, timeRange: u.timeRange, sidebar: u.sidebar,
      }));
    } catch (_e) { /* private mode */ }
  };

  // ============================================================== helpers
  const el = (id) => document.getElementById(id);
  const app = () => el("app");
  H.el = el; H.app = app;
  function esc(s) { return (s == null ? "" : String(s)).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])); }
  H.esc = esc;
  H.icon = function (name, cls) {
    const inner = (window.HEXIS_ICONS && window.HEXIS_ICONS[name]) || "";
    return '<svg class="ic' + (cls ? " " + cls : "") + '" viewBox="0 0 24 24" aria-hidden="true">' + inner + "</svg>";
  };
  H.brandMark = function () {
    return '<svg class="brand-mark" viewBox="0 0 108 108" aria-hidden="true">' +
      '<circle cx="54" cy="54" r="12" fill="var(--accent)" opacity="0.18"/>' +
      '<g fill="none" stroke="currentColor" stroke-width="7.5" stroke-linecap="round" stroke-linejoin="round">' +
      '<path d="M41,26 L54,39 L67,26"/><path d="M82,41 L69,54 L82,67"/><path d="M41,82 L54,69 L67,82"/><path d="M26,41 L39,54 L26,67"/></g>' +
      '<circle cx="54" cy="54" r="6.5" fill="var(--accent)"/></svg>';
  };
  H.argb = function (color) { if (color == null) return null; const n = (Number(color) >>> 0) & 0xffffff; return "#" + n.toString(16).padStart(6, "0"); };

  // ---- date/time, respecting the phone's settings ----
  function use24() { const f = H.settings.timeFormat; if (f === "H24") return true; if (f === "H12") return false; return undefined; }
  function tz() { return H.settings.timeZone || undefined; }
  function dateOpts(o) { const z = tz(); return z ? Object.assign({ timeZone: z }, o) : o; }
  function timeOpts(o) { const h = use24(); const base = Object.assign({ hour: "numeric", minute: "2-digit" }, o); if (h !== undefined) base.hour12 = !h; return dateOpts(base); }
  H.use24 = use24; H.tz = tz; H.dateOpts = dateOpts;
  H.fmtTime = function (ms) { if (!ms) return ""; try { return new Date(ms).toLocaleTimeString(undefined, timeOpts({})); } catch (_e) { return ""; } };
  H.fmtDate = function (ms) { if (!ms) return ""; try { return new Date(ms).toLocaleDateString(undefined, dateOpts({ month: "short", day: "numeric" })); } catch (_e) { return ""; } };
  H.fmtDateTime = function (ms) { if (!ms) return ""; try { return new Date(ms).toLocaleString(undefined, timeOpts({ month: "short", day: "numeric" })); } catch (_e) { return ""; } };
  H.fmtDuration = function (min) { const m = Math.max(0, Math.round(min || 0)); if (m < 60) return m + "m"; const h = Math.floor(m / 60), r = m % 60; return r ? h + "h " + r + "m" : h + "h"; };
  H.toDateInput = function (ms) { if (!ms) return ""; const d = new Date(ms), p = (n) => String(n).padStart(2, "0"); return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()); };
  H.toTimeInput = function (ms) { if (!ms) return ""; const d = new Date(ms), p = (n) => String(n).padStart(2, "0"); return p(d.getHours()) + ":" + p(d.getMinutes()); };
  H.fromDateInput = function (v) { if (!v) return null; const [y, m, d] = v.split("-").map(Number); return new Date(y, m - 1, d, 12, 0, 0, 0).getTime(); };
  H.fromDateTimeInput = function (dateV, timeV) { if (!dateV) return null; const [y, m, d] = dateV.split("-").map(Number); const [hh, mm] = (timeV || "00:00").split(":").map(Number); return new Date(y, m - 1, d, hh || 0, mm || 0, 0, 0).getTime(); };

  // Local calendar-date helpers (midnight-based), with the phone's day-start rollover for "today".
  H.startOfDay = function (ms) { const d = new Date(ms); d.setHours(0, 0, 0, 0); return d.getTime(); };
  H.addDays = function (ms, n) { const d = new Date(ms); d.setDate(d.getDate() + n); return d.getTime(); };
  H.sameDay = function (a, b) { return H.startOfDay(a) === H.startOfDay(b); };
  // epoch-day (local) with day-start rollover subtracted, matching the core's localDate()/today.
  H.localEpochDay = function (ms) {
    const roll = (H.settings.dayStartHour * 60 + H.settings.dayStartMinute) * 60000;
    const d = new Date(ms - roll);
    return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / DAY);
  };
  H.todayEpochDay = function () { return H.localEpochDay(Date.now()); };
  // epoch-day of a stored due/start value: all-day (local-midnight) values are treated as pure dates.
  H.epochDayOf = function (ms) { if (ms == null) return null; return H.localEpochDay(ms); };
  H.epochDayToDate = function (day) { const d = new Date(day * DAY); return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()); };
  // first day of week per settings.weekStart (0 = locale, 1..7 = Mon..Sun → JS 1..0)
  H.weekStartDow = function () {
    const w = H.settings.weekStart;
    if (w >= 1 && w <= 7) return w % 7; // 7(Sun)->0, 1(Mon)->1 ... 6(Sat)->6
    try { const f = new Intl.Locale(navigator.language).weekInfo; if (f && f.firstDay) return f.firstDay % 7; } catch (_e) { /**/ }
    return 0; // Sunday fallback
  };
  H.weekStart = function (ms) { const d = new Date(H.startOfDay(ms)); const back = (d.getDay() - H.weekStartDow() + 7) % 7; return H.addDays(d.getTime(), -back); };

  // ---- toast / status / connection ----
  let toastTimer = null;
  H.toast = function (msg) { const t = el("toast"); if (!t) return; t.textContent = msg; t.hidden = false; if (toastTimer) clearTimeout(toastTimer); toastTimer = setTimeout(() => { t.hidden = true; }, 3200); };
  H.setStatus = function (msg, kind) { const s = el("status"); if (!s) return; s.textContent = msg || ""; s.hidden = !msg; s.className = "status" + (kind ? " " + kind : ""); };
  H.setConn = function (kind) { const d = el("connDot"); if (d) d.className = "dot" + (kind ? " " + kind : ""); };

  // ============================================================== views registry + render
  H.views = {}; // domain -> { title?, sidebar(), main(), wire(), onReload?() }
  H.render = function () {
    const a = app(); if (!a) return;
    a.dataset.domain = H.ui.domain;
    a.dataset.sidebar = H.ui.sidebar ? "open" : "closed";
    a.dataset.density = (H.settings.density || "DEFAULT").toLowerCase();
    document.querySelectorAll(".rail-btn[data-domain]").forEach((b) => b.classList.toggle("active", b.dataset.domain === H.ui.domain));
    const view = H.views[H.ui.domain];
    const sb = el("sidebar"), head = el("mainHead"), body = el("mainBody");
    if (H.stopClock) H.stopClock();
    if (!view) { if (body) body.innerHTML = H.empty("Coming soon", "This section isn't built yet."); if (sb) sb.innerHTML = ""; if (head) head.innerHTML = H.headTitle(H.ui.domain); return; }
    if (sb) sb.innerHTML = view.sidebar ? view.sidebar() : "";
    if (head) head.innerHTML = view.head ? view.head() : H.headTitle(view.title || H.ui.domain);
    if (body) body.innerHTML = view.main ? view.main() : "";
    const sbt = el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    if (view.wire) view.wire();
    H.renderInspector();
  };
  H.headTitle = function (title, right) {
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar" aria-label="Toggle sidebar">' + H.icon("menu") + "</button>" +
      '<h1 class="main-title">' + esc(title) + "</h1><span class=\"head-spacer\"></span>" + (right || "");
  };
  H.empty = function (title, sub) {
    return '<div class="empty-state"><div class="empty-ic">' + H.brandMark() + "</div><h3>" + esc(title) + "</h3>" + (sub ? "<p>" + esc(sub) + "</p>" : "") + "</div>";
  };
  H.selectDomain = function (d) { H.ui.domain = d; if (H.ui.sel && H.ui.sel.domain !== d) H.closeInspector(); H.saveUi(); H.render(); };
  H.toggleSidebar = function () { H.ui.sidebar = !H.ui.sidebar; H.saveUi(); H.render(); };

  // inspector hooks (defined by task/note/event modules via H.inspectors[domain])
  H.inspectors = {};
  H.openInspector = function (domain, id) { H.ui.sel = { domain, id }; app().dataset.inspector = "open"; const i = el("inspector"); if (i) i.hidden = false; H.renderInspector(); if (window.innerWidth < 1100) { const s = el("scrim"); if (s) s.hidden = false; } };
  H.closeInspector = function () { H.ui.sel = null; H.ui.draft = null; app().dataset.inspector = "closed"; const i = el("inspector"); if (i) i.hidden = true; const s = el("scrim"); if (s) s.hidden = true; document.querySelectorAll(".sel").forEach((n) => n.classList.remove("sel")); };
  H.renderInspector = function () {
    if (!H.ui.sel) return; const ins = el("inspector"); if (!ins) return;
    const fn = H.inspectors[H.ui.sel.domain];
    if (!fn) { H.closeInspector(); return; }
    fn(ins, H.ui.sel.id);
  };

  // ============================================================== data load
  H.reloadDomain = async function (d) {
    const q = H.api.query;
    if (d === "tasks" || d === "matrix" || d === "timeline") {
      const [tasks, lists, folders, tags, contexts, flags, deps] = await Promise.all([
        q("tasks", "list"), q("tasks", "lists").catch(() => []), q("tasks", "folders").catch(() => []),
        q("tasks", "tags").catch(() => []), q("tasks", "contexts").catch(() => []), q("tasks", "flags").catch(() => []),
        q("tasks", "dependencies").catch(() => []),
      ]);
      H.state.tasks = tasks; H.state.lists = lists; H.state.folders = folders; H.state.tags = tags;
      H.state.contexts = contexts; H.state.flags = flags; H.state.dependencies = deps;
    } else if (d === "notes") {
      const [notes, notebooks, folders] = await Promise.all([q("notes", "list"), q("notes", "notebooks").catch(() => []), q("tasks", "folders").catch(() => H.state.folders)]);
      H.state.notes = notes; H.state.notebooks = notebooks; if (folders) H.state.folders = folders;
    } else if (d === "calendar") {
      const w = (H.views.calendar && H.views.calendar.window) ? H.views.calendar.window() : { from: Date.now() - 7 * DAY, to: Date.now() + 45 * DAY };
      const [events, cals] = await Promise.all([q("calendar", "occurrences", { paramsJson: JSON.stringify({ from: w.from, to: w.to }) }), q("calendar", "calendars").catch(() => [])]);
      H.state.calendar = events; H.state.calendars = cals;
    } else if (d === "time" || d === "focus") {
      const [entries, acts] = await Promise.all([q("time", "list"), q("time", "activities").catch(() => [])]);
      H.state.time = entries; H.state.activities = acts;
    } else if (d === "habits") {
      const [habits, history] = await Promise.all([q("habits", "list"), q("habits", "history", { paramsJson: JSON.stringify({ days: 371 }) }).catch(() => [])]);
      H.state.habits = habits; H.state.habitHistory = history;
    }
  };
  // which core domains back a given UI section (for live ticks)
  H.sectionsForCoreDomain = function (core) {
    if (core === "tasks") return ["tasks", "matrix", "timeline"];
    if (core === "habits") return ["habits"];
    if (core === "time") return ["time", "focus"];
    if (core === "calendar") return ["calendar"];
    if (core === "notes") return ["notes"];
    return [];
  };
  async function loadSettings() { try { const s = await H.api.queryOne("settings", "get"); if (s) H.settings = Object.assign(defaultSettings(), s); } catch (_e) { /* keep defaults */ } }
  H.reloadVisible = async function () {
    // load the data the current section needs (plus tasks, which many sections reference)
    const d = H.ui.domain;
    const need = new Set(["tasks"]);
    if (d === "notes") need.add("notes");
    if (d === "calendar") need.add("calendar");
    if (d === "time" || d === "focus") need.add("time");
    if (d === "habits") need.add("habits");
    await Promise.all([...need].map((x) => H.reloadDomain(x).catch(() => {})));
  };
  async function loadAll() {
    H.setStatus("Loading…", "");
    try {
      await loadSettings();
      await Promise.all(["tasks", "notes", "calendar", "time", "habits"].map((d) => H.reloadDomain(d).catch(() => {})));
      H.setStatus("", "ok");
    } catch (e) { H.setStatus("Could not load. Make sure Hexis is connected on your phone. (" + e.message + ")", "err"); throw e; }
  }

  // ============================================================== live (WebSocket) — ported from v0.2
  const live = { ws: null, backoff: 0, reconnectTimer: null, hbTimer: null, pongTimer: null, bannerTimer: null };
  function wsUrl() { return (location.protocol === "https:" ? "wss" : "ws") + "://" + location.host + "/live"; }
  async function sealFrame(obj) { return sealText(JSON.stringify(obj)); }
  function startLive() {
    stopLive(); let sock;
    try { sock = new WebSocket(wsUrl()); } catch (_e) { scheduleReconnect(); return; }
    live.ws = sock;
    sock.onopen = async () => { live.backoff = 0; clearBannerTimer(); H.setConn("ok"); try { sock.send(await sealFrame({ t: "hello", nonce: nonce(), since: seen })); } catch (_e) { /**/ } startHeartbeat(); };
    sock.onmessage = async (ev) => {
      if (typeof ev.data !== "string") return;
      let msg; try { msg = JSON.parse(await openTextBlob(ev.data)); } catch (_e) { return; }
      if (msg.t === "pong") { clearPong(); return; }
      if (msg.t === "tick" && msg.versions) {
        const now = msg.versions;
        const changed = Object.keys(now).filter((d) => (now[d] || 0) > (seen[d] || 0));
        seen = Object.assign({}, seen, now);
        let touched = false;
        for (const core of changed) {
          if (core === "settings") { await loadSettings().catch(() => {}); touched = true; continue; }
          const sections = H.sectionsForCoreDomain(core);
          if (sections.includes(H.ui.domain) || core === "tasks") { try { await H.reloadDomain(core === "tasks" ? "tasks" : H.ui.domain); touched = true; } catch (_e) { /**/ } }
        }
        if (touched) H.render();
      }
    };
    sock.onclose = () => { if (live.ws === sock) { stopHeartbeat(); live.ws = null; H.setConn("err"); scheduleReconnect(); } };
    sock.onerror = () => { try { sock.close(); } catch (_e) { /**/ } };
  }
  function stopLive() { stopHeartbeat(); if (live.reconnectTimer) { clearTimeout(live.reconnectTimer); live.reconnectTimer = null; } if (live.ws) { const s = live.ws; live.ws = null; try { s.onclose = null; s.close(); } catch (_e) { /**/ } } }
  function scheduleReconnect() { if (live.reconnectTimer) return; showBannerSoon(); const delay = Math.floor(Math.random() * Math.min(10000, 1000 * Math.pow(2, live.backoff))); live.backoff = Math.min(live.backoff + 1, 6); live.reconnectTimer = setTimeout(() => { live.reconnectTimer = null; startLive(); }, delay); }
  function startHeartbeat() { stopHeartbeat(); live.hbTimer = setInterval(async () => { if (!live.ws || live.ws.readyState !== WebSocket.OPEN) return; try { live.ws.send(await sealFrame({ t: "ping", nonce: nonce() })); } catch (_e) { return; } if (!live.pongTimer) live.pongTimer = setTimeout(() => { live.pongTimer = null; try { live.ws && live.ws.close(); } catch (_e) { /**/ } }, 10000); }, 25000); }
  function stopHeartbeat() { if (live.hbTimer) { clearInterval(live.hbTimer); live.hbTimer = null; } clearPong(); }
  function clearPong() { if (live.pongTimer) { clearTimeout(live.pongTimer); live.pongTimer = null; } }
  function showBannerSoon() { if (live.bannerTimer) return; live.bannerTimer = setTimeout(() => { live.bannerTimer = null; H.setConn("err"); }, 2000); }
  function clearBannerTimer() { if (live.bannerTimer) { clearTimeout(live.bannerTimer); live.bannerTimer = null; } }
  function reconnectNow() { live.backoff = 0; if (live.reconnectTimer) { clearTimeout(live.reconnectTimer); live.reconnectTimer = null; } if (!live.ws || live.ws.readyState !== WebSocket.OPEN) startLive(); }

  // ============================================================== theme
  let theme = null;
  H.toggleTheme = function () {
    theme = theme === "dark" ? "light" : theme === "light" ? null : "dark";
    if (theme) document.documentElement.setAttribute("data-theme", theme); else document.documentElement.removeAttribute("data-theme");
    try { if (theme) localStorage.setItem("hexis_theme", theme); else localStorage.removeItem("hexis_theme"); } catch (_e) { /**/ }
  };

  // ============================================================== nav rail (mobile sections + real icons)
  const RAIL = [
    ["tasks", "Tasks", "format_list_bulleted"],
    ["calendar", "Calendar", "calendar_month"],
    ["timeline", "Timeline", "view_timeline"],
    ["matrix", "Matrix", "grid_view"],
    ["habits", "Habits", "local_fire_department"],
    ["time", "Time", "schedule"],
    ["notes", "Notes", "article"],
    ["focus", "Focus", "timer"],
  ];
  function buildRail() {
    const rail = el("rail"); if (!rail) return;
    let html = '<div class="rail-brand" title="Hexis"><span class="dot" id="connDot" title="Connection"></span>' + H.brandMark() + "</div>";
    html += RAIL.map((r) => '<button class="rail-btn" data-domain="' + r[0] + '" title="' + r[1] + '">' + H.icon(r[2]) + '<span class="rail-lbl">' + r[1] + "</span></button>").join("");
    html += '<div class="rail-spacer"></div>';
    html += '<button class="rail-btn" id="paletteBtn" title="Search (Ctrl/Cmd-K)">' + H.icon("search") + '<span class="rail-lbl">Search</span></button>';
    html += '<button class="rail-btn" data-domain="settings" title="Settings">' + H.icon("settings") + '<span class="rail-lbl">Settings</span></button>';
    html += '<button class="rail-btn" id="themeBtn" title="Theme">' + H.icon("bedtime") + '<span class="rail-lbl">Theme</span></button>';
    rail.innerHTML = html;
  }

  // ============================================================== global wiring + boot
  function wireGlobal() {
    document.querySelectorAll(".rail-btn[data-domain]").forEach((b) => b.onclick = () => H.selectDomain(b.dataset.domain));
    const tb = el("themeBtn"); if (tb) tb.onclick = H.toggleTheme;
    const pb = el("paletteBtn"); if (pb) pb.onclick = () => H.openPalette && H.openPalette();
    const sc = el("scrim"); if (sc) sc.onclick = () => { H.closeInspector(); if (window.innerWidth < 920) { H.ui.sidebar = false; H.render(); } };
    document.addEventListener("keydown", onKey);
    window.addEventListener("online", reconnectNow);
    window.addEventListener("offline", () => { H.setConn("err"); stopLive(); });
    document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") reconnectNow(); });
  }
  function onKey(e) {
    if ((e.metaKey || e.ctrlKey) && (e.key === "k" || e.key === "K")) { e.preventDefault(); if (H.paletteOpen && H.paletteOpen()) H.closePalette(); else H.openPalette && H.openPalette(); return; }
    if (H.paletteOpen && H.paletteOpen()) return;
    if (e.key === "Escape" && H.ui.sel) { H.closeInspector(); return; }
    const tag = (e.target.tagName || "").toLowerCase();
    if (tag === "input" || tag === "textarea" || tag === "select") return;
    if (e.metaKey || e.ctrlKey || e.altKey) return;
    const idx = Number(e.key);
    if (idx >= 1 && idx <= DOMAINS.length) { H.selectDomain(DOMAINS[idx - 1]); return; }
    if (e.key === "m" || e.key === "M") { H.toggleSidebar(); return; }
    if (e.key === "c" || e.key === "C") { H.paletteCreate && H.paletteCreate(); return; }
    if (e.key === "/") { e.preventDefault(); H.openPalette && H.openPalette(); }
  }

  async function boot() {
    H.loadUi();
    try { const t = localStorage.getItem("hexis_theme"); if (t) { theme = t; document.documentElement.setAttribute("data-theme", t); } } catch (_e) { /**/ }
    buildRail();
    wireGlobal();
    if (H.wireLive) H.wireLive();
    const m = /[#&]k=([A-Za-z0-9_-]+)/.exec(location.hash || "");
    let keyB64 = m && m[1];
    if (keyB64) { try { sessionStorage.setItem("hexis_k", keyB64); } catch (_e) { /**/ } }
    else { try { keyB64 = sessionStorage.getItem("hexis_k"); } catch (_e) { keyB64 = null; } }
    if (!keyB64) { H.setStatus("No pairing key. Open the exact link (or scan the QR) shown in the Hexis Web app.", "err"); return; }
    try { aeadKey = await deriveKey(b64urlDecode(keyB64)); } catch (_e) { H.setStatus("Pairing key looks malformed. Re-scan the QR from the Hexis Web app.", "err"); return; }
    if (m) { try { history.replaceState(null, "", location.pathname + location.search); } catch (_e) { /**/ } }
    try { H.readOnly = (await hello()).readOnly === true; } catch (_e) { H.readOnly = false; }
    H.render();
    try { await loadAll(); H.render(); } catch (_e) { /* status shown */ }
    startLive();
  }
  window.addEventListener("DOMContentLoaded", boot);
})();
