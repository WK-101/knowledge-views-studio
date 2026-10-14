"use strict";
/* Hexis Web — command palette (Ctrl/Cmd-K). Searches tasks/notes/events + commands. */
(function () {
  const H = window.H;
  const palette = { open: false, items: [], sel: 0 };
  H.paletteOpen = function () { return palette.open; };

  function commands() {
    const cmds = [];
    const labels = { tasks: "Tasks", calendar: "Calendar", timeline: "Timeline", matrix: "Matrix", habits: "Habits", time: "Time", notes: "Notes", focus: "Focus", settings: "Settings" };
    Object.keys(labels).forEach((d) => cmds.push({ icon: "search", label: "Go to " + labels[d], hint: "Navigate", run: () => H.selectDomain(d) }));
    if (!H.readOnly) {
      cmds.push({ icon: "add", label: "New task", hint: "Create", run: () => { H.selectDomain("tasks"); setTimeout(() => { const i = H.el("quickAddInput"); if (i) i.focus(); }, 0); } });
      cmds.push({ icon: "add", label: "New note", hint: "Create", run: () => { H.selectDomain("notes"); H.openInspector("notes", "__new__"); } });
      cmds.push({ icon: "add", label: "New event", hint: "Create", run: () => { H.selectDomain("calendar"); if (H.newEvent) H.newEvent(null); } });
    }
    cmds.push({ icon: "format_list_bulleted", label: "Tasks: List view", hint: "View", run: () => setTaskView("list") });
    cmds.push({ icon: "view_week", label: "Tasks: Board view", hint: "View", run: () => setTaskView("board") });
    cmds.push({ icon: "bedtime", label: "Toggle theme", hint: "Command", run: H.toggleTheme });
    cmds.push({ icon: "menu", label: "Toggle sidebar", hint: "Command", run: H.toggleSidebar });
    return cmds;
  }
  function setTaskView(v) { H.ui.domain = "tasks"; H.ui.taskView = v; H.saveUi(); H.render(); }

  function items() {
    const out = [];
    H.state.tasks.filter((t) => !t.completed && !t.trashed && !t.abandoned).forEach((t) => out.push({ icon: "format_list_bulleted", label: t.title, hint: "Task", domain: "tasks", id: t.id }));
    H.state.notes.forEach((n) => out.push({ icon: "article", label: n.title || "Untitled note", hint: "Note", domain: "notes", id: n.id }));
    const seen = {};
    H.state.calendar.forEach((e) => { if (!seen[e.id]) { seen[e.id] = 1; out.push({ icon: "calendar_month", label: e.title || "Untitled event", hint: "Event", domain: "calendar", id: e.id }); } });
    return out;
  }
  function results(q) {
    const ql = q.trim().toLowerCase();
    const cmds = commands();
    if (!ql) return cmds.slice(0, 40);
    return cmds.concat(items()).filter((c) => c.label.toLowerCase().includes(ql)).sort((a, b) => a.label.toLowerCase().indexOf(ql) - b.label.toLowerCase().indexOf(ql)).slice(0, 40);
  }

  H.openPalette = function () {
    if (palette.open) return;
    palette.open = true;
    let p = H.el("palette");
    if (!p) {
      p = document.createElement("div");
      p.id = "palette"; p.className = "palette-scrim";
      p.innerHTML = '<div class="palette" role="dialog" aria-modal="true" aria-label="Command palette">' +
        '<input id="palInput" class="palette-input" type="text" placeholder="Search tasks, notes, events — or a command…" autocomplete="off" spellcheck="false">' +
        '<ul id="palList" class="palette-list"></ul>' +
        '<div class="palette-foot"><span>&#8593;&#8595; navigate &#183; &#8629; open &#183; esc close</span></div></div>';
      document.body.appendChild(p);
      p.addEventListener("mousedown", (e) => { if (e.target === p) H.closePalette(); });
      H.el("palInput").addEventListener("input", (e) => renderPalette(e.target.value));
      H.el("palInput").addEventListener("keydown", onKey);
      H.el("palList").addEventListener("mousedown", (e) => { const li = e.target.closest("[data-i]"); if (li) { e.preventDefault(); run(Number(li.dataset.i)); } });
    }
    p.hidden = false;
    const inp = H.el("palInput"); inp.value = ""; renderPalette(""); inp.focus();
  };
  H.closePalette = function () { palette.open = false; const p = H.el("palette"); if (p) p.hidden = true; };
  function renderPalette(q) { palette.items = results(q); palette.sel = 0; draw(); }
  function draw() {
    const list = H.el("palList"); if (!list) return;
    list.innerHTML = palette.items.length ? palette.items.map((c, i) =>
      '<li class="pal-row' + (i === palette.sel ? " sel" : "") + '" data-i="' + i + '">' + H.icon(c.icon || "search") + '<span class="pal-label">' + H.esc(c.label) + "</span>" +
      (c.hint ? '<span class="pal-hint">' + H.esc(c.hint) + "</span>" : "") + "</li>").join("") : '<li class="pal-empty">No matches</li>';
    const sel = list.querySelector(".pal-row.sel"); if (sel) sel.scrollIntoView({ block: "nearest" });
  }
  function onKey(e) {
    if (e.key === "ArrowDown") { e.preventDefault(); palette.sel = Math.min(palette.items.length - 1, palette.sel + 1); draw(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); palette.sel = Math.max(0, palette.sel - 1); draw(); }
    else if (e.key === "Enter") { e.preventDefault(); run(palette.sel); }
    else if (e.key === "Escape") { e.preventDefault(); H.closePalette(); }
  }
  function run(i) { const c = palette.items[i]; if (!c) return; H.closePalette(); if (c.domain) { H.selectDomain(c.domain); H.openInspector(c.domain, c.id); } else if (c.run) c.run(); }
  H.paletteCreate = function () {
    if (H.readOnly) return;
    if (H.ui.domain === "tasks") { const i = H.el("quickAddInput"); if (i) i.focus(); }
    else if (H.ui.domain === "notes") H.openInspector("notes", "__new__");
    else if (H.ui.domain === "calendar" && H.newEvent) H.newEvent(null);
  };
})();
