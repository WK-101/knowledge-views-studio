"use strict";
/*
 * Hexis Web client. Talks to the phone's /api over app-layer AES-256-GCM so the channel is confidential +
 * authenticated even on plain HTTP: the pairing key (carried in the URL fragment, never sent to the server)
 * is run through HKDF-SHA256 to the same AEAD key the phone derives, and every request/response is a
 * base64url( iv(12) || ciphertext+tag ) blob. Parameters mirror CryptoBox.kt exactly.
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

  async function query(domain, op, limit) {
    const req = { ts: Date.now(), nonce: nonce(), kind: "query", domain, op: op || "list", limit: limit || 200 };
    const res = await fetch("api", {
      method: "POST",
      headers: { "Content-Type": "text/plain" },
      body: await sealText(JSON.stringify(req)),
    });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const api = JSON.parse(await openText(await res.text()));
    if (!api.ok) throw new Error(api.error || "request failed");
    const page = api.dataJson ? JSON.parse(api.dataJson) : { payloadJson: "[]" };
    return JSON.parse(page.payloadJson || "[]");
  }

  // ---- state + render ----------------------------------------------------------------------------
  const el = (id) => document.getElementById(id);
  let tab = "tasks";

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

  function renderTasks(tasks) {
    const view = el("tasksView");
    if (!tasks.length) {
      view.innerHTML = '<p class="empty">No tasks.</p>';
      return;
    }
    const open = tasks.filter((t) => !t.completed);
    const done = tasks.filter((t) => t.completed);
    const row = (t) =>
      '<li class="row' + (t.completed ? " done" : "") + '">' +
      '<span class="box" aria-hidden="true">' + (t.completed ? "&#x2713;" : "") + "</span>" +
      '<span class="label">' + (t.star ? '<span class="star">&#x2605;</span> ' : "") + esc(t.title) + "</span>" +
      (t.dueDate ? '<span class="due">' + esc(fmtDate(t.dueDate)) + "</span>" : "") +
      "</li>";
    view.innerHTML =
      '<ul class="list">' + open.map(row).join("") + "</ul>" +
      (done.length ? '<h3 class="subhead">Completed</h3><ul class="list">' + done.map(row).join("") + "</ul>" : "");
  }

  function renderNotes(notes) {
    const view = el("notesView");
    if (!notes.length) {
      view.innerHTML = '<p class="empty">No notes.</p>';
      return;
    }
    const sorted = notes.slice().sort((a, b) => (b.pinned - a.pinned) || (b.updatedAt - a.updatedAt));
    view.innerHTML =
      '<div class="cards">' +
      sorted
        .map(
          (n) =>
            '<article class="card">' +
            (n.pinned ? '<span class="pin">&#x1f4cc;</span>' : "") +
            "<h4>" + esc(n.title || "Untitled") + "</h4>" +
            '<p>' + esc((n.body || "").slice(0, 280)) + "</p>" +
            "</article>"
        )
        .join("") +
      "</div>";
  }

  function selectTab(which) {
    tab = which;
    const isTasks = which === "tasks";
    el("tabTasks").setAttribute("aria-selected", String(isTasks));
    el("tabNotes").setAttribute("aria-selected", String(!isTasks));
    el("tasksView").hidden = !isTasks;
    el("notesView").hidden = isTasks;
  }

  async function load() {
    setStatus("Loading…", "");
    try {
      const [tasks, notes] = await Promise.all([query("tasks", "list", 500), query("notes", "list", 500)]);
      renderTasks(tasks);
      renderNotes(notes);
      setStatus("", "ok");
      el("footText").textContent =
        tasks.length + " tasks · " + notes.length + " notes · end-to-end encrypted";
    } catch (e) {
      setStatus("Could not load. Make sure Hexis is connected on your phone, then refresh. (" + e.message + ")", "err");
    }
  }

  async function boot() {
    el("tabTasks").addEventListener("click", () => selectTab("tasks"));
    el("tabNotes").addEventListener("click", () => selectTab("notes"));
    el("refreshBtn").addEventListener("click", load);

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
    load();
  }

  window.addEventListener("DOMContentLoaded", boot);
})();
