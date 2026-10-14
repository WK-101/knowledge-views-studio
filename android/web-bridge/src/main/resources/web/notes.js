"use strict";
/* Hexis Web — Notes (Keep-style masonry + markdown editor). Ported to the H framework; respects the
 * phone's notebook mode / sort where it can. Full folder-tree parity lands in a later milestone. */
(function () {
  const H = window.H;
  const NOTE_COLORS = [null, "#e5484d", "#f5a623", "#f6c744", "#1e9e64", "#30a0c0", "#5b57d9", "#9b59b6", "#8a93a3"];

  // ---- markdown (escape first, then format) ----
  function mdInline(s) {
    return s.replace(/`([^`]+)`/g, "<code>$1</code>").replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\s][^*]*?)\*(?!\*)/g, "$1<em>$2</em>").replace(/~~([^~]+)~~/g, "<del>$1</del>")
      .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
  }
  function mdToHtml(src) {
    const lines = String(src || "").replace(/\r\n/g, "\n").split("\n");
    let html = "", i = 0, inUl = false, inOl = false;
    const esc = H.esc;
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

  function sortNotes(list) {
    const s = H.settings.notesSort || "updated";
    const cmp = { updated: (a, b) => b.updatedAt - a.updatedAt, created: (a, b) => (b.createdAt || 0) - (a.createdAt || 0), titleAsc: (a, b) => (a.title || "").localeCompare(b.title || ""), titleDesc: (a, b) => (b.title || "").localeCompare(a.title || "") }[s] || ((a, b) => b.updatedAt - a.updatedAt);
    return list.slice().sort((a, b) => (Number(b.pinned) - Number(a.pinned)) || cmp(a, b));
  }
  function filtered() {
    const f = H.ui.noteFilter;
    if (f.kind === "notebook") return H.state.notes.filter((n) => n.notebookId === f.value && !n.archived);
    if (f.kind === "folder") return H.state.notes.filter((n) => n.folderId === f.value && !n.archived);
    if (f.value === "archived") return H.state.notes.filter((n) => n.archived);
    const base = H.state.notes.filter((n) => !n.archived);
    if (f.value === "pinned") return base.filter((n) => n.pinned);
    return base;
  }
  function label() {
    const f = H.ui.noteFilter;
    if (f.kind === "notebook") { const b = H.state.notebooks.find((x) => x.id === f.value); return b ? b.name : "Notebook"; }
    if (f.kind === "folder") { const b = H.state.folders.find((x) => x.id === f.value); return b ? b.name : "Folder"; }
    return { all: "Notes", pinned: "Pinned", archived: "Archived" }[f.value] || "Notes";
  }
  function sidebar() {
    const f = H.ui.noteFilter;
    const nonArch = H.state.notes.filter((n) => !n.archived);
    const smart = [["all", "All notes", "article", nonArch.length], ["pinned", "Pinned", "push_pin", nonArch.filter((n) => n.pinned).length], ["archived", "Archived", "folder", H.state.notes.filter((n) => n.archived).length]];
    const smartHtml = smart.map((s) => { const on = f.kind === "smart" && f.value === s[0]; return '<li class="sb-item' + (on ? " active" : "") + '" data-nsmart="' + s[0] + '"><span class="sb-ico">' + H.icon(s[2]) + "</span><span class=\"sb-label\">" + s[1] + "</span>" + (s[3] ? '<span class="sb-count">' + s[3] + "</span>" : "") + "</li>"; }).join("");
    // grouping source: notebooks (notes_notebook_mode=notebooks) else folders
    const useNotebooks = H.settings.notesNotebookMode === "notebooks";
    const groups = useNotebooks ? H.state.notebooks : H.state.folders;
    const groupsHtml = groups && groups.length ? '<h3 class="sb-sub">' + (useNotebooks ? "Notebooks" : "Folders") + '</h3><ul class="sb-list">' + groups.map((b) => {
      const on = f.kind === (useNotebooks ? "notebook" : "folder") && f.value === b.id; const dot = H.argb(b.colorArgb);
      const cnt = H.state.notes.filter((n) => !n.archived && (useNotebooks ? n.notebookId === b.id : n.folderId === b.id)).length;
      return '<li class="sb-item' + (on ? " active" : "") + '" data-' + (useNotebooks ? "notebook" : "folder") + '="' + H.esc(b.id) + '"><span class="sb-ico">' + ((b.emoji || b.icon) ? H.esc(b.emoji || b.icon) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : H.icon("folder"))) + "</span><span class=\"sb-label\">" + H.esc(b.name) + "</span>" + (cnt ? '<span class="sb-count">' + cnt + "</span>" : "") + "</li>";
    }).join("") + "</ul>" : "";
    return '<h2 class="sb-title">Notes</h2><ul class="sb-list">' + smartHtml + "</ul>" + groupsHtml;
  }
  function head() { return H.headTitle(label(), H.readOnly ? "" : '<button class="btn primary" data-new="note">' + H.icon("add") + "New note</button>"); }
  function noteCard(n) {
    const sel = H.ui.sel && H.ui.sel.domain === "notes" && H.ui.sel.id === n.id;
    const tint = H.argb(n.colorArgb); const style = tint ? ' style="--nc:' + tint + '"' : "";
    const preview = (n.body || "").slice(0, 500);
    return '<article class="ncard' + (tint ? " tinted" : "") + (sel ? " sel" : "") + '" data-id="' + H.esc(n.id) + '" data-act="open"' + style + ">" +
      (n.pinned ? '<span class="pin">' + H.icon("push_pin") + "</span>" : "") + (n.coverEmoji ? '<div class="ncard-emoji">' + H.esc(n.coverEmoji) + "</div>" : "") +
      (n.title ? "<h4>" + H.esc(n.title) + "</h4>" : "") + (preview ? '<div class="ncard-md">' + mdToHtml(preview) + "</div>" : (n.title ? "" : '<p class="ncard-untitled">Untitled</p>')) + "</article>";
  }
  function main() {
    const notes = filtered();
    if (!notes.length) return H.empty("No notes here", H.readOnly ? "" : "Create one with “New note”.");
    const showGroups = H.ui.noteFilter.kind === "smart" && H.ui.noteFilter.value === "all";
    if (showGroups) {
      const sorted = sortNotes(notes); const pinned = sorted.filter((n) => n.pinned), others = sorted.filter((n) => !n.pinned);
      let html = "";
      if (pinned.length) html += '<h3 class="group-h">Pinned</h3><div class="ncards">' + pinned.map(noteCard).join("") + "</div>";
      if (others.length) html += (pinned.length ? '<h3 class="group-h">Others</h3>' : "") + '<div class="ncards">' + others.map(noteCard).join("") + "</div>";
      return html;
    }
    return '<div class="ncards">' + sortNotes(notes).map(noteCard).join("") + "</div>";
  }
  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    const nb = H.el("mainHead").querySelector('[data-new="note"]'); if (nb) nb.onclick = () => H.openInspector("notes", "__new__");
    H.el("mainBody").onclick = (e) => { const card = e.target.closest(".ncard"); if (card) H.openInspector("notes", card.dataset.id); };
    const sb = H.el("sidebar"); if (sb) sb.onclick = (e) => {
      const item = e.target.closest(".sb-item"); if (!item) return;
      if (item.dataset.nsmart) H.ui.noteFilter = { kind: "smart", value: item.dataset.nsmart };
      else if (item.dataset.notebook) H.ui.noteFilter = { kind: "notebook", value: item.dataset.notebook };
      else if (item.dataset.folder) H.ui.noteFilter = { kind: "folder", value: item.dataset.folder };
      else return;
      H.saveUi(); H.closeInspector(); H.render(); if (window.innerWidth < 960) { H.ui.sidebar = false; H.render(); }
    };
  }
  H.views.notes = { title: "Notes", sidebar, head, main, wire };

  // ---- inspector (markdown editor) ----
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
