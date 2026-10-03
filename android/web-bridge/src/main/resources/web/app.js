"use strict";
/*
 * Hexis Web client. Talks to the phone's /api over app-layer AES-256-GCM so the channel is confidential +
 * authenticated even on plain HTTP: the pairing key (carried in the URL fragment, never sent to the server)
 * is run through HKDF-SHA256 to the same AEAD key the phone derives, and every request/response is a
 * base64url( iv(12) || ciphertext+tag ) blob. Parameters mirror CryptoBox.kt exactly.
 *
 * Live refresh is a long-poll over the SAME encrypted channel (kind:"await"): the phone holds the request
 * until a watched domain changes (or ~25s), then the client reloads just that domain. No separate socket.
 */
(function () {
  const AEAD_INFO = "hexis-web-aead-v1";
  const IV_LEN = 12;

  // ---- base64url ---------------------------------------------------------------------------------
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

  // ---- crypto ------------------------------------------------------------------------------------
  let aeadKey = null; // CryptoKey (AES-GCM)

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
    const iv = blob.slice(0, IV_LEN);
    const ct = blob.slice(IV_LEN);
    const pt = await crypto.subtle.decrypt({ name: "AES-GCM", iv, tagLength: 128 }, aeadKey, ct);
    return new TextDecoder().decode(pt);
  }

  // ---- api ---------------------------------------------------------------------------------------
  function nonce() {
    return b64urlEncode(crypto.getRandomValues(new Uint8Array(16)));
  }

  async function call(req, signal) {
    const body = await sealText(JSON.stringify(Object.assign({ ts: Date.now(), nonce: nonce() }, req)));
    const res = await fetch("api", { method: "POST", headers: { "Content-Type": "text/plain" }, body, signal });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return JSON.parse(await openText(await res.text()));
  }

  async function query(domain, op, extra) {
    const api = await call(Object.assign({ kind: "query", domain, op: op || "list", limit: 500 }, extra || {}));
    if (!api.ok) throw new Error(api.error || "request failed");
    const page = api.dataJson ? JSON.parse(api.dataJson) : { payloadJson: "[]" };
    return JSON.parse(page.payloadJson || "[]");
  }

  async function mutate(domain, op, payload) {
    const api = await call({ kind: "mutate", domain, op, payloadJson: JSON.stringify(payload || {}) });
    if (!api.ok) throw new Error(api.error || "save failed");
    return api.dataJson ? JSON.parse(api.dataJson) : null;
  }

  async function awaitChanges(since, signal) {
    const api = await call({ kind: "await", paramsJson: JSON.stringify(since || {}) }, signal);
    return api.ok && api.dataJson ? JSON.parse(api.dataJson) : null; // null = timeout, re-poll
  }

  // ---- state + render ----------------------------------------------------------------------------
  const el = (id) => document.getElementById(id);
  const state = { tasks: [], notes: [], calendar: [], time: [], habits: [] };
  const TABS = [
    { key: "tasks", tab: "tabTasks", view: "tasksView" },
    { key: "notes", tab: "tabNotes", view: "notesView" },
    { key: "calendar", tab: "tabCalendar", view: "calendarView" },
    { key: "time", tab: "tabTime", view: "timeView" },
    { key: "habits", tab: "tabHabits", view: "habitsView" },
  ];
  let seen = {}; // last-seen change versions per domain
  let tab = "tasks";
  let selectMode = false;
  const selected = new Set();

  function setStatus(msg, kind) {
    const s = el("status");
    s.textContent = msg || "";
    s.hidden = !msg;
    el("statusDot").className = "dot" + (kind ? " " + kind : "");
  }

  function esc(s) {
    return (s == null ? "" : String(s)).replace(/[&<>"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])
    );
  }

  function fmtDate(ms) {
    if (!ms) return "";
    try {
      return new Date(ms).toLocaleDateString(undefined, { month: "short", day: "numeric" });
    } catch (_e) {
      return "";
    }
  }
  function toDateInput(ms) {
    if (!ms) return "";
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, "0");
    return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
  }
  function fromDateInput(v) {
    if (!v) return null;
    const [y, m, d] = v.split("-").map(Number);
    return new Date(y, m - 1, d, 12, 0, 0, 0).getTime(); // local noon, avoids TZ day-shift
  }
  function fmtTime(ms) {
    if (!ms) return "";
    try {
      return new Date(ms).toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    } catch (_e) {
      return "";
    }
  }
  function fmtDateTime(ms) {
    if (!ms) return "";
    try {
      return new Date(ms).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
    } catch (_e) {
      return "";
    }
  }
  function fmtDuration(min) {
    const m = Math.max(0, Math.round(min || 0));
    if (m < 60) return m + "m";
    const h = Math.floor(m / 60);
    const r = m % 60;
    return r ? h + "h " + r + "m" : h + "h";
  }
  function argb(color) {
    if (color == null) return null;
    const n = (Number(color) >>> 0) & 0xffffff;
    return "#" + n.toString(16).padStart(6, "0");
  }

  function taskRow(t) {
    if (selectMode) {
      const on = selected.has(t.id);
      return (
        '<li class="row sel' + (on ? " on" : "") + (t.completed ? " done" : "") + '" data-id="' + esc(t.id) + '">' +
        '<span class="box' + (on ? " checked" : "") + '" aria-hidden="true">' + (on ? "&#x2713;" : "") + "</span>" +
        '<span class="label">' + (t.star ? '<span class="star">&#x2605;</span> ' : "") + esc(t.title) + "</span>" +
        (t.dueDate ? '<span class="due">' + esc(fmtDate(t.dueDate)) + "</span>" : "") +
        "</li>"
      );
    }
    return (
      '<li class="row' + (t.completed ? " done" : "") + '" data-id="' + esc(t.id) + '">' +
      '<button class="box" data-act="toggle" aria-label="Toggle complete">' + (t.completed ? "&#x2713;" : "") + "</button>" +
      '<span class="label" data-act="edit">' + (t.star ? '<span class="star">&#x2605;</span> ' : "") + esc(t.title) + "</span>" +
      (t.dueDate ? '<span class="due">' + esc(fmtDate(t.dueDate)) + "</span>" : "") +
      '<button class="del" data-act="del" aria-label="Delete" title="Move to Trash">&#x2715;</button>' +
      "</li>"
    );
  }

  function renderTasks() {
    const tasks = state.tasks;
    const open = tasks.filter((t) => !t.completed);
    const done = tasks.filter((t) => t.completed);
    const bulkBar =
      selectMode && selected.size
        ? '<div class="bulkbar"><span>' + selected.size + " selected</span>" +
          '<span class="spacer"></span>' +
          '<button data-bulk="complete">Complete</button>' +
          '<button data-bulk="star">Star</button>' +
          '<button data-bulk="delete" class="danger">Delete</button></div>'
        : "";
    el("tasksView").innerHTML =
      '<div class="tasktools">' +
      (selectMode
        ? '<span class="hint">Tap tasks to select</span>'
        : '<form id="addTask" class="add"><input id="addTaskInput" type="text" placeholder="Add a task…" autocomplete="off" />' +
          '<button type="submit">Add</button></form>') +
      '<button id="selectToggle" class="ghost">' + (selectMode ? "Done" : "Select") + "</button>" +
      "</div>" +
      bulkBar +
      (open.length ? '<ul class="list">' + open.map(taskRow).join("") + "</ul>" : '<p class="empty">No open tasks.</p>') +
      (done.length ? '<h3 class="subhead">Completed</h3><ul class="list">' + done.map(taskRow).join("") + "</ul>" : "");
  }

  function renderNotes() {
    const sorted = state.notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.updatedAt - a.updatedAt));
    el("notesView").innerHTML =
      '<div class="add"><button id="addNote" type="button">New note</button></div>' +
      (sorted.length
        ? '<div class="cards">' +
          sorted
            .map(
              (n) =>
                '<article class="card" data-id="' + esc(n.id) + '">' +
                (n.pinned ? '<span class="pin">&#x1f4cc;</span>' : "") +
                "<h4>" + esc(n.title || "Untitled") + "</h4>" +
                "<p>" + esc((n.body || "").slice(0, 280)) + "</p>" +
                "</article>"
            )
            .join("") +
          "</div>"
        : '<p class="empty">No notes.</p>');
  }

  function renderCalendar() {
    const now = Date.now();
    const upcoming = state.calendar
      .filter((e) => (e.endMillis || e.startMillis) >= now - 12 * 3600 * 1000)
      .slice(0, 200);
    if (!upcoming.length) {
      el("calendarView").innerHTML = '<p class="empty">No upcoming events.</p>';
      return;
    }
    const row = (e) => {
      const when = e.allDay ? fmtDate(e.startMillis) + " · all day" : fmtDateTime(e.startMillis) + "–" + fmtTime(e.endMillis);
      const dot = argb(e.colorArgb);
      return (
        '<li class="erow">' +
        '<span class="cdot" style="background:' + (dot || "var(--accent)") + '"></span>' +
        '<span class="einfo"><span class="etitle">' + esc(e.title || "Untitled") + "</span>" +
        '<span class="emeta">' + esc(when) + (e.location ? " · " + esc(e.location) : "") +
        (e.calendarName ? " · " + esc(e.calendarName) : "") + "</span></span>" +
        (e.recurring ? '<span class="chip">↻</span>' : "") +
        "</li>"
      );
    };
    el("calendarView").innerHTML = '<ul class="list">' + upcoming.map(row).join("") + "</ul>";
  }

  function renderTime() {
    const entries = state.time.slice(0, 200);
    const todayStart = new Date().setHours(0, 0, 0, 0);
    const todayMin = entries
      .filter((e) => e.startMillis >= todayStart)
      .reduce((sum, e) => sum + (e.minutes || 0), 0);
    if (!entries.length) {
      el("timeView").innerHTML = '<p class="empty">No time entries.</p>';
      return;
    }
    const row = (e) =>
      '<li class="erow">' +
      '<span class="einfo"><span class="etitle">' + esc(e.activityName || "Activity") +
      (e.running ? ' <span class="chip live">● live</span>' : "") + "</span>" +
      '<span class="emeta">' + esc(fmtDateTime(e.startMillis)) + (e.note ? " · " + esc(e.note) : "") + "</span></span>" +
      '<span class="dur">' + esc(fmtDuration(e.minutes)) + "</span>" +
      "</li>";
    el("timeView").innerHTML =
      '<div class="summary">Today · <strong>' + esc(fmtDuration(todayMin)) + "</strong> tracked</div>" +
      '<ul class="list">' + entries.map(row).join("") + "</ul>";
  }

  function renderHabits() {
    const habits = state.habits;
    if (!habits.length) {
      el("habitsView").innerHTML = '<p class="empty">No habits.</p>';
      return;
    }
    const row = (h) => {
      const done = h.doneToday;
      const prog = h.targetPerDay > 1 ? " " + (h.todayCount || 0) + "/" + h.targetPerDay : "";
      return (
        '<li class="row' + (done ? " done" : "") + '">' +
        '<span class="box" aria-hidden="true">' + (done ? "&#x2713;" : "") + "</span>" +
        '<span class="label">' + (h.emoji ? esc(h.emoji) + " " : "") + esc(h.name) +
        (h.paused ? ' <span class="chip">paused</span>' : "") + "</span>" +
        (prog ? '<span class="due">' + esc(prog.trim()) + "</span>" : "") +
        "</li>"
      );
    };
    const active = habits.filter((h) => !h.paused);
    const paused = habits.filter((h) => h.paused);
    el("habitsView").innerHTML =
      '<ul class="list">' + active.map(row).join("") + "</ul>" +
      (paused.length ? '<h3 class="subhead">Paused</h3><ul class="list">' + paused.map(row).join("") + "</ul>" : "");
  }

  function selectTab(which) {
    tab = which;
    for (const t of TABS) {
      const on = t.key === which;
      el(t.tab).setAttribute("aria-selected", String(on));
      el(t.view).hidden = !on;
    }
  }

  // ---- editor modal ------------------------------------------------------------------------------
  function openModal(html, onSave, onDelete) {
    const back = document.createElement("div");
    back.className = "modal-back";
    back.innerHTML =
      '<div class="modal" role="dialog" aria-modal="true">' + html +
      '<div class="modal-actions">' +
      (onDelete ? '<button class="danger" data-m="del">Delete</button>' : "") +
      '<span class="spacer"></span>' +
      '<button data-m="cancel">Cancel</button><button class="primary" data-m="save">Save</button>' +
      "</div></div>";
    const close = () => back.remove();
    back.addEventListener("click", (e) => {
      if (e.target === back || e.target.dataset.m === "cancel") close();
      else if (e.target.dataset.m === "save") Promise.resolve(onSave(back)).then(close).catch((err) => alert(err.message));
      else if (e.target.dataset.m === "del") {
        if (confirm("Move to Trash?")) Promise.resolve(onDelete(back)).then(close).catch((err) => alert(err.message));
      }
    });
    document.body.appendChild(back);
    const first = back.querySelector("input, textarea");
    if (first) first.focus();
  }

  function editTask(t) {
    openModal(
      '<h3>Edit task</h3>' +
        '<label>Title<input id="mTitle" type="text" value="' + esc(t.title) + '" /></label>' +
        '<label>Note<textarea id="mNote" rows="3">' + esc(t.note || "") + "</textarea></label>" +
        '<label>Due<input id="mDue" type="date" value="' + esc(toDateInput(t.dueDate)) + '" /></label>' +
        '<label class="check"><input id="mStar" type="checkbox"' + (t.star ? " checked" : "") + " /> Star</label>",
      async (root) => {
        const title = root.querySelector("#mTitle").value.trim();
        if (!title) throw new Error("Title can't be empty");
        await mutate("tasks", "upsert", {
          id: t.id,
          title,
          note: root.querySelector("#mNote").value,
          dueDate: fromDateInput(root.querySelector("#mDue").value),
          importance: t.importance,
          urgency: t.urgency,
          star: root.querySelector("#mStar").checked,
        });
        await reload("tasks");
      },
      async () => {
        await mutate("tasks", "delete", { id: t.id });
        await reload("tasks");
      }
    );
  }

  function editNote(n) {
    const isNew = !n;
    n = n || { id: "", title: "", body: "", pinned: false };
    openModal(
      "<h3>" + (isNew ? "New note" : "Edit note") + "</h3>" +
        '<label>Title<input id="mTitle" type="text" value="' + esc(n.title) + '" /></label>' +
        '<label>Body<textarea id="mBody" rows="8">' + esc(n.body) + "</textarea></label>" +
        '<label class="check"><input id="mPin" type="checkbox"' + (n.pinned ? " checked" : "") + " /> Pin</label>",
      async (root) => {
        await mutate("notes", "upsert", {
          id: n.id,
          title: root.querySelector("#mTitle").value,
          body: root.querySelector("#mBody").value,
          pinned: root.querySelector("#mPin").checked,
        });
        await reload("notes");
      },
      isNew ? null : async () => {
        await mutate("notes", "delete", { id: n.id });
        await reload("notes");
      }
    );
  }

  // ---- events ------------------------------------------------------------------------------------
  function wireViewEvents() {
    el("tasksView").addEventListener("submit", async (e) => {
      if (e.target.id !== "addTask") return;
      e.preventDefault();
      const input = el("addTaskInput");
      const title = input.value.trim();
      if (!title) return;
      input.value = "";
      try {
        await mutate("tasks", "upsert", { id: "", title });
        await reload("tasks");
      } catch (err) {
        alert(err.message);
      }
    });
    el("tasksView").addEventListener("click", async (e) => {
      if (e.target.id === "selectToggle") {
        selectMode = !selectMode;
        selected.clear();
        renderTasks();
        return;
      }
      if (e.target.dataset.bulk) {
        await doBulk(e.target.dataset.bulk);
        return;
      }
      const li = e.target.closest(".row");
      if (!li) return;
      if (selectMode) {
        if (selected.has(li.dataset.id)) selected.delete(li.dataset.id);
        else selected.add(li.dataset.id);
        renderTasks();
        return;
      }
      const t = state.tasks.find((x) => x.id === li.dataset.id);
      if (!t) return;
      const act = e.target.dataset.act;
      try {
        if (act === "toggle") {
          await mutate("tasks", "complete", { id: t.id, completed: !t.completed });
          await reload("tasks");
        } else if (act === "del") {
          await mutate("tasks", "delete", { id: t.id });
          await reload("tasks");
        } else if (act === "edit") {
          editTask(t);
        }
      } catch (err) {
        alert(err.message);
      }
    });
    el("notesView").addEventListener("click", (e) => {
      if (e.target.id === "addNote") return editNote(null);
      const card = e.target.closest(".card");
      if (card) {
        const n = state.notes.find((x) => x.id === card.dataset.id);
        if (n) editNote(n);
      }
    });
  }

  async function doBulk(action) {
    const ids = [...selected];
    if (!ids.length) return;
    if (action === "delete" && !confirm("Move " + ids.length + " task(s) to Trash?")) return;
    try {
      await mutate("tasks", "bulk", { ids, action, value: true });
      selectMode = false;
      selected.clear();
      await reload("tasks");
    } catch (err) {
      alert(err.message);
    }
  }

  // ---- load + live refresh -----------------------------------------------------------------------
  async function reload(domain) {
    if (domain === "tasks" || !domain) {
      state.tasks = await query("tasks", "list");
      renderTasks();
    }
    if (domain === "notes" || !domain) {
      state.notes = await query("notes", "list");
      renderNotes();
    }
    if (domain === "calendar" || !domain) {
      state.calendar = await query("calendar", "list");
      renderCalendar();
    }
    if (domain === "time" || !domain) {
      state.time = await query("time", "list");
      renderTime();
    }
    if (domain === "habits" || !domain) {
      state.habits = await query("habits", "list");
      renderHabits();
    }
    el("footText").textContent =
      state.tasks.length + " tasks · " + state.notes.length + " notes · end-to-end encrypted";
  }

  async function loadAll() {
    setStatus("Loading…", "");
    try {
      await reload();
      setStatus("", "ok");
    } catch (e) {
      setStatus("Could not load. Make sure Hexis is connected on your phone, then refresh. (" + e.message + ")", "err");
      throw e;
    }
  }

  async function liveLoop() {
    // Long-poll the encrypted /api for change ticks; reload only what changed. Backs off on error.
    for (;;) {
      try {
        const now = await awaitChanges(seen);
        if (now) {
          const known = TABS.map((t) => t.key);
          const changed = Object.keys(now).filter((d) => (now[d] || 0) > (seen[d] || 0));
          seen = now;
          for (const d of changed) {
            if (known.includes(d)) await reload(d);
          }
          setStatus("", "ok");
        }
      } catch (_e) {
        setStatus("Reconnecting…", "err");
        await new Promise((r) => setTimeout(r, 3000));
      }
    }
  }

  async function boot() {
    for (const t of TABS) el(t.tab).addEventListener("click", () => selectTab(t.key));
    el("refreshBtn").addEventListener("click", () => reload().catch((e) => alert(e.message)));
    wireViewEvents();

    // The key arrives in the URL fragment (never sent to the server). Keep it in sessionStorage so an
    // in-tab reload still works, but strip it from the visible URL/history. Closing the tab clears it.
    const m = /[#&]k=([A-Za-z0-9_-]+)/.exec(location.hash || "");
    let keyB64 = m && m[1];
    if (keyB64) {
      try { sessionStorage.setItem("hexis_k", keyB64); } catch (_e) { /* private mode */ }
    } else {
      try { keyB64 = sessionStorage.getItem("hexis_k"); } catch (_e) { keyB64 = null; }
    }
    if (!keyB64) {
      setStatus("No pairing key. Open the exact link (or scan the QR) shown in the Hexis Web app.", "err");
      return;
    }
    try {
      aeadKey = await deriveKey(b64urlDecode(keyB64));
    } catch (_e) {
      setStatus("Pairing key looks malformed. Re-scan the QR from the Hexis Web app.", "err");
      return;
    }
    if (m) {
      try {
        history.replaceState(null, "", location.pathname + location.search);
      } catch (_e) {
        /* non-fatal */
      }
    }

    try {
      await loadAll();
      liveLoop();
    } catch (_e) {
      /* status already shown; user can hit refresh */
    }
  }

  window.addEventListener("DOMContentLoaded", boot);
})();
