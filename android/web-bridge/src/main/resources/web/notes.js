"use strict";
/* Hexis Web — Notes. Parity with the mobile NotesScreen browse surface: a flat container picker (not a
 * tree — mobile Notes never nests), three view modes (cards grid/list · board · calendar), the mobile
 * NoteCard (left colour bar, cover emoji, 2-line title, plain 160-char preview), pinned-float-first in a
 * single list, and the notes_notebook_mode / note_default_view / notes_view_mode / notes_sort settings.
 * Favorites/Untagged/Trash + the favorite star need the `favorite`/tag fields and arrive with M3. */
(function () {
  const H = window.H;
  const NOTE_COLORS = [null, "#e5484d", "#f5a623", "#f6c744", "#1e9e64", "#30a0c0", "#5b57d9", "#9b59b6", "#8a93a3"];

  // ---- markdown (for the editor preview only; cards use a plain stripped preview) ----
  function mdInline(s) {
    return s.replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, "$1<em>$2</em>").replace(/~~([^~]+)~~/g, "<del>$1</del>")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  }
  function mdToHtml(src) {
    const lines = String(src || "").replace(/\r\n/g, "\n").split("\n");
    let html = "", i = 0, inUl = false, inOl = false; const esc = H.esc;
    const closeLists = () => { if (inUl) { html += "</ul>"; inUl = false; } if (inOl) { html += "</ol>"; inOl = false; } };
    while (i < lines.length) {
      const raw = lines[i];
      if (/^\s*```/.test(raw)) { closeLists(); i++; let code = ""; while (i < lines.length && !/^\s*```/.test(lines[i])) { code += esc(lines[i]) + "\n"; i++; } i++; html += "<pre><code>" + code + "</code></pre>"; continue; }
      const line = esc(raw); let m;
      if ((m = line.match(/^(#{1,6})\s+(.*)$/))) { closeLists(); const lv = m[1].length; html += "<h" + lv + ">" + mdInline(m[2]) + "</h" + lv + ">"; }
      else if (/^\s*[-*]\s+\[[ xX]\]\s+/.test(line)) { if (inOl) { html += "</ol>"; inOl = false; } if (!inUl) { html += '<ul class="md-tasks">'; inUl = true; } const done = /\[[xX]\]/.test(line.match(/\[[ xX]\]/)[0]); html += '<li class="md-task">' + (done ? "☑" : "☐") + " " + mdInline(line.replace(/^\s*[-*]\s+\[[ xX]\]\s+/, "")) + "</li>"; }
      else if (/^\s*[-*]\s+/.test(line)) { if (inOl) { html += "</ol>"; inOl = false; } if (!inUl) { html += "<ul>"; inUl = true; } html += "<li>" + mdInline(line.replace(/^\s*[-*]\s+/, "")) + "</li>"; }
      else if (/^\s*\d+\.\s+/.test(line)) { if (inUl) { html += "</ul>"; inUl = false; } if (!inOl) { html += "<ol>"; inOl = true; } html += "<li>" + mdInline(line.replace(/^\s*\d+\.\s+/, "")) + "</li>"; }
      else if (/^\s*&gt;\s?/.test(line)) { closeLists(); html += "<blockquote>" + mdInline(line.replace(/^\s*&gt;\s?/, "")) + "</blockquote>"; }
      else if (/^\s*([-*_])\1\1+\s*$/.test(raw)) { closeLists(); html += "<hr>"; }
      else if (raw.trim() === "") { closeLists(); }
      else { closeLists(); html += "<p>" + mdInline(line) + "</p>"; }
      i++;
    }
    closeLists(); return html;
  }
  H.mdToHtml = mdToHtml;

  // NoteDerived.preview: per line strip leading markers, drop blanks, join with two spaces, strip inline marks, cap 160.
  function notePreview(body) {
    const out = [];
    String(body || "").replace(/\r\n/g, "\n").split("\n").forEach((raw) => {
      let s = raw.trim(); if (!s) return;
      s = s.replace(/^#{1,6}\s+/, "").replace(/^&gt;\s?/, "").replace(/^>\s?/, "").replace(/^[-*+]\s+(\[[ xX]\]\s+)?/, "").replace(/^`+/, "");
      out.push(s);
    });
    const t = out.join("  ").replace(/[*_~`]/g, "");
    return t.length > 160 ? t.slice(0, 160) : t;
  }

  // ---- view settings (phone defaults; locally switchable like the mobile toolbar) ----
  let inited = false;
  function initView() {
    if (inited) return; inited = true;
    if (H.ui.notesView == null) H.ui.notesView = ["cards", "board", "calendar"].includes(H.settings.notesViewMode) ? H.settings.notesViewMode : "cards";
    if (H.ui.notesGrid == null) H.ui.notesGrid = (H.settings.noteDefaultView || "grid") !== "list";
  }
  function useNotebooks() { return H.settings.notesNotebookMode === "notebooks"; }
  function containers() { const src = useNotebooks() ? H.state.notebooks : H.state.folders; return (src || []).slice().sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)); }
  function containerOf(n) { return useNotebooks() ? n.notebookId : n.folderId; }

  function sortNotes(list) {
    const s = H.settings.notesSort || "updated";
    const cmp = {
      created: (a, b) => (b.createdAt || 0) - (a.createdAt || 0),
      titleAsc: (a, b) => (a.title || "￿").toLowerCase().localeCompare((b.title || "￿").toLowerCase()),
      titleDesc: (a, b) => (b.title || "").toLowerCase().localeCompare((a.title || "").toLowerCase()),
    }[s] || ((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    return list.slice().sort((a, b) => (Number(b.pinned) - Number(a.pinned)) || cmp(a, b));
  }
  function filtered() {
    const f = H.ui.noteFilter;
    if (f.kind === "container") return H.state.notes.filter((n) => !n.archived && containerOf(n) === f.value);
    if (f.value === "archived") return H.state.notes.filter((n) => n.archived);
    const base = H.state.notes.filter((n) => !n.archived);
    if (f.value === "pinned") return base.filter((n) => n.pinned);
    return base;
  }
  function filterLabel() {
    const f = H.ui.noteFilter;
    if (f.kind === "container") { const c = containers().find((x) => x.id === f.value); return c ? c.name : (useNotebooks() ? "Notebook" : "Folder"); }
    return { all: "All notes", pinned: "Pinned", archived: "Archived" }[f.value] || "Notes";
  }

  // ---- sidebar: flat filters + flat container list (by sortOrder) ----
  function sidebar() {
    const f = H.ui.noteFilter, nonArch = H.state.notes.filter((n) => !n.archived);
    const smart = [["all", "All notes", "article", nonArch.length], ["pinned", "Pinned", "push_pin", nonArch.filter((n) => n.pinned).length], ["archived", "Archived", "inventory_2", H.state.notes.filter((n) => n.archived).length]];
    const smartHtml = smart.map((s) => { const on = f.kind === "smart" && f.value === s[0]; return '<li class="sb-item' + (on ? " active" : "") + '" data-nsmart="' + s[0] + '"><span class="sb-ico">' + H.icon(s[2]) + '</span><span class="sb-label">' + s[1] + "</span>" + (s[3] ? '<span class="sb-count">' + s[3] + "</span>" : "") + "</li>"; }).join("");
    const cs = containers();
    const csHtml = cs.length ? '<h3 class="sb-sub">' + (useNotebooks() ? "Notebooks" : "Folders") + '</h3><ul class="sb-list">' + cs.map((c) => {
      const on = f.kind === "container" && f.value === c.id, dot = H.argb(c.colorArgb), ico = (c.emoji || c.icon);
      const cnt = H.state.notes.filter((n) => !n.archived && containerOf(n) === c.id).length;
      return '<li class="sb-item' + (on ? " active" : "") + '" data-container="' + H.esc(c.id) + '"><span class="sb-ico">' + (ico ? H.esc(ico) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : H.icon("folder"))) + '</span><span class="sb-label">' + H.esc(c.name) + "</span>" + (cnt ? '<span class="sb-count">' + cnt + "</span>" : "") + "</li>";
    }).join("") + "</ul>" : "";
    return '<h2 class="sb-title">Notes</h2><ul class="sb-list">' + smartHtml + "</ul>" + csHtml;
  }
  function head() {
    initView();
    const modes = [["cards", "grid_view"], ["board", "view_kanban"], ["calendar", "calendar_month"]];
    const seg = '<div class="segmented">' + modes.map((m) => '<button class="seg' + (H.ui.notesView === m[0] ? " on" : "") + '" data-nview="' + m[0] + '" title="' + m[0] + '">' + H.icon(m[1]) + "</button>").join("") + "</div>";
    const gridToggle = H.ui.notesView === "cards" ? '<div class="segmented"><button class="seg' + (H.ui.notesGrid ? " on" : "") + '" data-ngrid="1" title="Grid">' + H.icon("grid_view") + '</button><button class="seg' + (!H.ui.notesGrid ? " on" : "") + '" data-ngrid="0" title="List">' + H.icon("format_list_bulleted") + "</button></div>" : "";
    const right = seg + gridToggle + (H.readOnly ? "" : '<button class="btn primary" data-new="note">' + H.icon("add") + "New note</button>");
    return H.headTitle(filterLabel(), right);
  }

  // ---- card ----
  function noteCard(n, boardW) {
    const sel = H.ui.sel && H.ui.sel.domain === "notes" && H.ui.sel.id === n.id;
    const tint = H.argb(n.colorArgb), prev = notePreview(n.body);
    const bar = tint ? '<span class="ncard-bar" style="background:' + tint + ";height:" + ((n.body || "").trim() ? 92 : 54) + 'px"></span>' : "";
    const style = boardW ? ' style="width:' + boardW + 'px"' : "";
    return '<article class="ncard' + (sel ? " sel" : "") + '" data-id="' + H.esc(n.id) + '"' + style + ">" + bar +
      (n.pinned ? '<span class="pin">' + H.icon("push_pin") + "</span>" : "") +
      '<div class="ncard-in">' + (n.coverEmoji ? '<div class="ncard-emoji">' + H.esc(n.coverEmoji) + "</div>" : "") +
      '<h4>' + H.esc(n.title || "Untitled") + "</h4>" + (prev ? '<p class="ncard-prev">' + H.esc(prev) + "</p>" : "") + "</div></article>";
  }
  function main() {
    initView();
    if (H.ui.notesView === "board") return boardView();
    if (H.ui.notesView === "calendar") return calendarView();
    const notes = sortNotes(filtered());
    if (!notes.length) return H.empty("No notes here", H.readOnly ? "" : "Create one with “New note”.");
    return '<div class="ncards ' + (H.ui.notesGrid ? "grid" : "list") + '">' + notes.map((n) => noteCard(n)).join("") + "</div>";
  }
  function boardView() {
    const cs = containers(), notes = H.state.notes.filter((n) => !n.archived);
    const shelves = cs.map((c) => ({ name: c.name, emoji: c.emoji || c.icon, items: sortNotes(notes.filter((n) => containerOf(n) === c.id)) }));
    const loose = sortNotes(notes.filter((n) => !containerOf(n)));
    if (loose.length) shelves.push({ name: useNotebooks() ? "Unsorted" : "Unfiled", items: loose });
    if (!shelves.some((s) => s.items.length)) return H.empty("No notes", H.readOnly ? "" : "Create one with “New note”.");
    return '<div class="nboard">' + shelves.filter((s) => s.items.length).map((s) =>
      '<section class="nshelf"><h3 class="group-h">' + (s.emoji ? H.esc(s.emoji) + " " : "") + H.esc(s.name) + ' <span class="shelf-n">' + s.items.length + '</span></h3><div class="nshelf-row">' + s.items.map((n) => noteCard(n, 210)).join("") + "</div></section>"
    ).join("") + "</div>";
  }
  function calendarView() {
    const notes = H.state.notes.filter((n) => !n.archived).slice().sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    if (!notes.length) return H.empty("No notes", H.readOnly ? "" : "Create one with “New note”.");
    const groups = []; let cur = null;
    notes.forEach((n) => { const d = new Date(n.updatedAt || 0), key = d.getFullYear() + "-" + d.getMonth(); if (!cur || cur.key !== key) { cur = { key, label: d.toLocaleDateString(undefined, { month: "long", year: "numeric" }), items: [] }; groups.push(cur); } cur.items.push(n); });
    return '<div class="ncal">' + groups.map((g) =>
      '<section class="ncal-mo"><h3 class="group-h">' + H.esc(g.label) + '</h3><ul class="ncal-list">' + g.items.map((n) => {
        const d = new Date(n.updatedAt || 0);
        return '<li class="ncal-row" data-id="' + H.esc(n.id) + '"><span class="ncal-date">' + d.toLocaleDateString(undefined, { weekday: "short", day: "numeric" }) + "</span>" + (n.coverEmoji ? '<span class="ncal-emoji">' + H.esc(n.coverEmoji) + "</span>" : "") + '<span class="ncal-title">' + H.esc(n.title || "Untitled") + "</span></li>";
      }).join("") + "</ul></section>"
    ).join("") + "</div>";
  }
  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    const head = H.el("mainHead");
    const nb = head.querySelector('[data-new="note"]'); if (nb) nb.onclick = () => H.openInspector("notes", "__new__");
    head.querySelectorAll("[data-nview]").forEach((b) => b.onclick = () => { H.ui.notesView = b.dataset.nview; H.saveUi(); H.render(); });
    head.querySelectorAll("[data-ngrid]").forEach((b) => b.onclick = () => { H.ui.notesGrid = b.dataset.ngrid === "1"; H.saveUi(); H.render(); });
    H.el("mainBody").onclick = (e) => { const card = e.target.closest("[data-id]"); if (card) H.openInspector("notes", card.dataset.id); };
    const sb = H.el("sidebar"); if (sb) sb.onclick = (e) => {
      const item = e.target.closest(".sb-item"); if (!item) return;
      if (item.dataset.nsmart) H.ui.noteFilter = { kind: "smart", value: item.dataset.nsmart };
      else if (item.dataset.container) H.ui.noteFilter = { kind: "container", value: item.dataset.container };
      else return;
      H.saveUi(); H.closeInspector(); H.render(); if (window.innerWidth < 960) { H.ui.sidebar = false; H.render(); }
    };
  }
  H.views.notes = { title: "Notes", sidebar, head, main, wire };

  // ---- inspector (markdown editor) — unchanged ----
  H.inspectors.notes = function (ins, id) {
    if (id === "__new__") H.ui.noteEdit = true;
    const n = id === "__new__" ? { id: "", title: "", body: "", pinned: false, archived: false, colorArgb: null, coverEmoji: "" } : H.state.notes.find((x) => x.id === id);
    if (!n) { H.closeInspector(); return; }
    ins.innerHTML = editorHtml(n); wireEditor(ins, n);
  };
  function editorHtml(n) {
    const ro = H.readOnly ? " disabled" : "", isNew = !n.id, cur = n.colorArgb != null ? H.argb(n.colorArgb) : null;
    const sw = H.readOnly ? "" : '<div class="swatches">' + NOTE_COLORS.map((c) => { const on = (c === cur) || (c === null && cur === null); return '<button type="button" class="swatch' + (c ? "" : " none") + (on ? " on" : "") + '" data-color="' + (c || "") + '"' + (c ? ' style="background:' + c + '"' : "") + ">" + (c ? "" : "⊘") + "</button>"; }).join("") + "</div>";
    const seg = '<div class="segmented sm"><button type="button" class="seg' + (H.ui.noteEdit ? " on" : "") + '" data-nmode="edit">Edit</button><button type="button" class="seg' + (!H.ui.noteEdit ? " on" : "") + '" data-nmode="preview">Preview</button></div>';
    const editor = '<div class="note-editor" id="noteEditor" data-mode="' + (H.ui.noteEdit ? "edit" : "preview") + '"><textarea id="iBody" rows="14" placeholder="Write in markdown…"' + ro + ">" + H.esc(n.body) + '</textarea><div class="md-preview" id="iPreview">' + (n.body ? mdToHtml(n.body) : '<p class="md-empty">Nothing to preview</p>') + "</div></div>";
    return '<div class="ins-head"><h2>' + (isNew ? "New note" : "Note") + '</h2><button class="icon-btn" id="insClose">' + H.icon("close") + "</button></div>" +
      '<div class="ins-body"><div class="note-emoji-row"><input id="iEmoji" class="emoji-in" type="text" maxlength="2" value="' + H.esc(n.coverEmoji || "") + '" placeholder="☺"' + ro + '><input id="iTitle" class="note-title-in" type="text" value="' + H.esc(n.title) + '" placeholder="Title"' + ro + "></div>" +
      sw + '<div class="note-body-head">' + seg + '<label class="chk-inline"><input id="iPin" type="checkbox"' + (n.pinned ? " checked" : "") + ro + "> Pin</label></div>" + editor +
      '<div class="attachments" id="iAttach"></div>' + (H.readOnly ? "" : '<div class="ins-actions">' + (isNew ? "" : '<button class="btn danger" id="iDelete">Delete</button>') + '<span class="spacer"></span><button class="btn primary" id="iSave">Save</button></div>') + "</div>";
  }
  function wireEditor(ins, n) {
    ins.querySelector("#insClose").onclick = H.closeInspector;
    loadAttachments(ins, n.id || null);
    ins.querySelectorAll(".seg[data-nmode]").forEach((b) => b.onclick = () => {
      const toPreview = b.dataset.nmode === "preview"; H.ui.noteEdit = !toPreview;
      if (toPreview) { const t = ins.querySelector("#iBody").value; ins.querySelector("#iPreview").innerHTML = t.trim() ? mdToHtml(t) : '<p class="md-empty">Nothing to preview</p>'; }
      ins.querySelector("#noteEditor").dataset.mode = toPreview ? "preview" : "edit";
      ins.querySelectorAll(".seg[data-nmode]").forEach((x) => x.classList.toggle("on", (x.dataset.nmode === "edit") === !toPreview));
      if (!toPreview) ins.querySelector("#iBody").focus();
    });
    if (H.readOnly) return;
    ins.querySelectorAll(".swatch").forEach((s) => s.onclick = () => { ins.querySelectorAll(".swatch").forEach((x) => x.classList.remove("on")); s.classList.add("on"); });
    ins.querySelector("#iSave").onclick = async () => {
      const swOn = ins.querySelector(".swatch.on"); const hex = swOn ? swOn.dataset.color : "";
      const colorArgb = hex ? (0xFF000000 + parseInt(hex.slice(1), 16)) : null;
      try { const res = await H.api.mutate("notes", "upsert", { id: n.id, title: ins.querySelector("#iTitle").value, body: ins.querySelector("#iBody").value, pinned: ins.querySelector("#iPin").checked, colorArgb, coverEmoji: ins.querySelector("#iEmoji").value }); await H.reloadDomain("notes"); const newId = res && res.id ? res.id : n.id; H.ui.sel = { domain: "notes", id: newId }; H.render(); H.toast("Saved"); } catch (e) { H.toast(e.message); }
    };
    const del = ins.querySelector("#iDelete"); if (del) del.onclick = async () => { if (!confirm("Move this note to Trash?")) return; try { await H.api.mutate("notes", "delete", { id: n.id }); H.closeInspector(); await H.reloadDomain("notes"); H.render(); } catch (e) { H.toast(e.message); } };
  }
  async function loadAttachments(ins, noteId) {
    const box = ins.querySelector("#iAttach"); if (!box || !noteId) return;
    let list; try { list = await H.api.query("notes", "attachments", { paramsJson: JSON.stringify({ id: noteId }) }); } catch (_e) { return; }
    if (!list || !list.length) return;
    box.innerHTML = '<h4 class="att-h">Attachments</h4><div class="att-grid"></div>'; const grid = box.querySelector(".att-grid");
    for (const a of list) {
      const cell = document.createElement("div"); cell.className = "att"; cell.innerHTML = '<span class="att-name">' + H.esc(a.fileName || "file") + "</span>"; grid.appendChild(cell);
      if (a.isImage) { H.api.query("notes", "attachmentData", { paramsJson: JSON.stringify({ id: a.id, noteId }) }).then((d) => { if (d && d.dataB64) { const img = document.createElement("img"); img.loading = "lazy"; img.alt = a.fileName || ""; img.src = "data:" + (d.mime || "image/*") + ";base64," + d.dataB64; cell.insertBefore(img, cell.firstChild); } else cell.insertAdjacentHTML("afterbegin", '<span class="att-ph">open on phone</span>'); }).catch(() => {}); }
      else cell.insertAdjacentHTML("afterbegin", '<span class="att-ph">open on phone</span>');
    }
  }
})();
