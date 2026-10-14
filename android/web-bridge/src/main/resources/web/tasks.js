"use strict";
/*
 * Hexis Web — Tasks (+ Matrix). Ports the mobile app's pure logic from domain/view/Views.kt,
 * ListPipeline.kt, DoNext.kt and priority/Priority.kt so the web shows EXACTLY the tasks the phone does.
 * Row rendering mirrors ui/components/Common.kt (priority checkbox, due chip, bookmark-flag + gold star).
 */
(function () {
  const H = window.H;
  const DAY = H.DAY;

  // ----------------------------------------------------------------- pure logic
  const dOf = (ms) => H.epochDayOf(ms);
  const today = () => H.todayEpochDay();
  function isOpen(t) { return !t.trashed && !t.completed && !t.abandoned && !t.someday; }
  function priLevel(t) { const m = Math.max(t.importance == null ? 3 : t.importance, t.urgency == null ? 3 : t.urgency); return m >= 5 ? "HIGH" : m >= 4 ? "MED" : m >= 3 ? "LOW" : "NONE"; }
  function priColor(lvl) { return { HIGH: "var(--p-high)", MED: "var(--p-med)", LOW: "var(--p-low)", NONE: "var(--p-none)" }[lvl]; }
  function priRank(t) { return Math.max(t.importance == null ? 3 : t.importance, t.urgency == null ? 3 : t.urgency); }

  function blockedSet(all) {
    const byId = {}; all.forEach((t) => { byId[t.id] = t; });
    const blocked = new Set();
    (H.state.dependencies || []).forEach((d) => { const dep = byId[d.dependsOnTaskId]; if (dep && !dep.completed && !dep.trashed && !dep.abandoned) blocked.add(d.taskId); });
    return blocked;
  }
  function waitingTasks(all) {
    const blocked = blockedSet(all);
    return all.filter((t) => !t.trashed && !t.completed && !t.abandoned && !t.someday && (blocked.has(t.id) || (t.waitingForWho || "").trim() !== ""));
  }
  function needsAttention(all) {
    const parents = new Set(all.filter((t) => !t.trashed).map((t) => t.parentId).filter(Boolean));
    const STALE = 21 * DAY, now = Date.now();
    return all.filter(isOpen).filter((t) => !t.isNote && t.dueDate == null && !parents.has(t.id) && (now - (t.updatedAt || 0)) >= STALE);
  }
  function doNext(all) {
    const blocked = blockedSet(all), now = Date.now(), t0 = today();
    let c = all.filter(isOpen).filter((t) => !blocked.has(t.id) && (t.waitingForWho || "").trim() === "" && !(t.startDate != null && t.startDate > now));
    c = c.filter((t) => { const dd = t.dueDate != null ? dOf(t.dueDate) : null; return dd != null ? dd <= t0 : (t.star || t.flagId != null); });
    return c.sort((a, b) => (priRank(b) - priRank(a)) || ((a.dueDate || 1e15) - (b.dueDate || 1e15)) || ((a.sortOrder || 0) - (b.sortOrder || 0)));
  }
  function smartFilter(all, kind) {
    const t0 = today(), open = all.filter(isOpen);
    switch (kind) {
      case "INBOX": return open.filter((t) => t.listId === "inbox");
      case "TODAY": return open.filter((t) => (t.dueDate != null && dOf(t.dueDate) <= t0) || (t.startDate != null && dOf(t.startDate) === t0));
      case "TOMORROW": return open.filter((t) => t.dueDate != null && dOf(t.dueDate) === t0 + 1);
      case "NEXT7": return open.filter((t) => t.dueDate != null && dOf(t.dueDate) <= t0 + 7);
      case "SCHEDULED": return open.filter((t) => t.dueDate != null);
      case "FLAGGED": return open.filter((t) => t.flagId != null);
      case "GOALS": return open.filter((t) => t.isGoal);
      case "WAITING": return waitingTasks(all);
      case "NEEDS_ATTENTION": return needsAttention(all);
      case "SOMEDAY": return all.filter((t) => !t.trashed && !t.completed && !t.abandoned && t.someday);
      case "ALL": return open;
      case "DO_NEXT": return doNext(all);
      case "COMPLETED": return all.filter((t) => t.completed && !t.trashed);
      case "WONT_DO": return all.filter((t) => t.abandoned && !t.trashed);
      case "TRASH": return all.filter((t) => t.trashed);
      default: return open;
    }
  }
  const SMART = [
    ["INBOX", "Inbox", "inbox"], ["TODAY", "Today", "today"], ["TOMORROW", "Tomorrow", "wb_sunny"],
    ["NEXT7", "Next 7 Days", "date_range"], ["DO_NEXT", "Do Next", "bolt"], ["SCHEDULED", "Scheduled", "event"],
    ["FLAGGED", "Flagged", "bookmark"], ["GOALS", "Goals", "flag"], ["WAITING", "Waiting On", "hourglass_empty"],
    ["NEEDS_ATTENTION", "Needs Attention", "priority_high"], ["SOMEDAY", "Someday", "weekend"],
    ["ALL", "All", "format_list_bulleted"], ["COMPLETED", "Completed", "check_circle"],
    ["WONT_DO", "Won't Do", "block"], ["TRASH", "Trash", "delete"],
  ];
  function smartTitle(kind, def) { return (H.settings.smartListNames && H.settings.smartListNames[kind]) || def; }
  H.T = { isOpen, priLevel, priColor, priRank, smartFilter, dOf, today, doNext, SMART };

  // ----------------------------------------------------------------- filtered set for the current view
  function folderListIds(folderId) { return H.state.lists.filter((l) => l.folderId === folderId).map((l) => l.id); }
  function currentTasks() {
    const f = H.ui.taskFilter, all = H.state.tasks;
    if (f.kind === "smart") return smartFilter(all, f.value);
    if (f.kind === "list") return all.filter((t) => isOpen(t) && t.listId === f.value);
    if (f.kind === "folder") { const ids = new Set(folderListIds(f.value)); return all.filter((t) => isOpen(t) && (ids.has(t.listId) || t.folderId === f.value)); }
    if (f.kind === "tag") return all.filter((t) => isOpen(t) && (t.tagIds || []).includes(f.value));
    if (f.kind === "context") return all.filter((t) => isOpen(t) && (t.contextIds || []).includes(f.value));
    return all.filter(isOpen);
  }
  function filterLabel() {
    const f = H.ui.taskFilter;
    if (f.kind === "smart") { const s = SMART.find((x) => x[0] === f.value); return s ? smartTitle(s[0], s[1]) : "Tasks"; }
    if (f.kind === "list") { const l = H.state.lists.find((x) => x.id === f.value); return l ? l.name : "List"; }
    if (f.kind === "folder") { const fo = H.state.folders.find((x) => x.id === f.value); return fo ? fo.name : "Folder"; }
    if (f.kind === "tag") { const g = H.state.tags.find((x) => x.id === f.value); return g ? "#" + g.name : "Tag"; }
    if (f.kind === "context") { const c = H.state.contexts.find((x) => x.id === f.value); return c ? "@" + c.name : "Context"; }
    return "Tasks";
  }
  function isCompletedView() { return H.ui.taskFilter.kind === "smart" && (H.ui.taskFilter.value === "COMPLETED" || H.ui.taskFilter.value === "WONT_DO"); }

  // ----------------------------------------------------------------- sort + group
  function sortTasks(ts) {
    let mode = H.ui.taskSort;
    if (isCompletedView()) mode = "COMPLETED";
    const base = ts.slice();
    const cmp = {
      MANUAL: (a, b) => (a.sortOrder || 0) - (b.sortOrder || 0),
      PRIORITY: (a, b) => priRank(b) - priRank(a),
      DUE: (a, b) => (a.dueDate || 8.64e15) - (b.dueDate || 8.64e15),
      TITLE: (a, b) => (a.title || "").toLowerCase().localeCompare((b.title || "").toLowerCase()),
      FLAG: (a, b) => flagRank(a) - flagRank(b),
      COMPLETED: (a, b) => (b.completedAt || 0) - (a.completedAt || 0),
      SCORE: (a, b) => (priRank(b) - priRank(a)) || ((a.dueDate || 1e15) - (b.dueDate || 1e15)),
    }[mode] || ((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
    base.sort((a, b) => (Number(b.pinned) - Number(a.pinned)) || cmp(a, b) || ((a.createdAt || 0) - (b.createdAt || 0)));
    return base;
  }
  function flagRank(t) { if (!t.flagId) return 9999; const i = H.state.flags.findIndex((f) => f.id === t.flagId); return i < 0 ? 9998 : i; }
  function effectiveGroup() {
    if (H.ui.outline) return "none";
    if (H.ui.taskSort === "manual" && !isCompletedView()) return "none";
    if (H.ui.taskFilter.kind === "smart" && H.ui.taskFilter.value === "DO_NEXT") return "none";
    return H.ui.taskGroup;
  }
  function groupTasks(ts) {
    const g = effectiveGroup();
    if (g === "none") return [{ title: "", tasks: ts }];
    const t0 = today();
    if (isCompletedView()) {
      const buckets = [["Today", []], ["Yesterday", []], ["Earlier this week", []], ["Earlier this month", []], ["Earlier", []]];
      const now = new Date();
      ts.forEach((t) => {
        const d = t.completedAt ? dOf(t.completedAt) : null; const diff = d == null ? 99999 : t0 - d;
        let i = 4;
        if (diff <= 0) i = 0; else if (diff === 1) i = 1; else if (diff <= 6) i = 2;
        else if (t.completedAt && new Date(t.completedAt).getMonth() === now.getMonth() && new Date(t.completedAt).getFullYear() === now.getFullYear()) i = 3;
        buckets[i][1].push(t);
      });
      return buckets.filter((b) => b[1].length).map((b) => ({ title: b[0], tasks: b[1] }));
    }
    if (g === "date") {
      const B = { overdue: [], today: [], tomorrow: [], week: [], later: [], none: [] };
      ts.forEach((t) => {
        if (t.dueDate == null) { B.none.push(t); return; }
        const d = dOf(t.dueDate);
        if (d < t0) B.overdue.push(t); else if (d === t0) B.today.push(t); else if (d === t0 + 1) B.tomorrow.push(t);
        else if (d < t0 + 8) B.week.push(t); else B.later.push(t);
      });
      return [["Overdue", B.overdue], ["Today", B.today], ["Tomorrow", B.tomorrow], ["Next 7 days", B.week], ["Later", B.later], ["No date", B.none]]
        .filter((b) => b[1].length).map((b) => ({ title: b[0], tasks: b[1] }));
    }
    if (g === "priority") {
      const order = ["HIGH", "MED", "LOW", "NONE"], names = { HIGH: "High", MED: "Medium", LOW: "Low", NONE: "None" };
      return order.map((lv) => ({ title: names[lv], tasks: ts.filter((t) => priLevel(t) === lv) })).filter((x) => x.tasks.length);
    }
    if (g === "context") {
      const groups = [];
      H.state.contexts.forEach((c) => { const items = ts.filter((t) => (t.contextIds || []).includes(c.id)); if (items.length) groups.push({ title: "@" + c.name, tasks: items }); });
      const none = ts.filter((t) => !(t.contextIds || []).length); if (none.length) groups.push({ title: "No context", tasks: none });
      return groups;
    }
    if (g === "flag") {
      const groups = [];
      H.state.flags.forEach((fl) => { const items = ts.filter((t) => t.flagId === fl.id); if (items.length) groups.push({ title: fl.name, tasks: items }); });
      const none = ts.filter((t) => !t.flagId); if (none.length) groups.push({ title: "No flag", tasks: none });
      return groups;
    }
    return [{ title: "", tasks: ts }];
  }

  // ----------------------------------------------------------------- row rendering
  function firstLine(s) { const l = (s || "").split("\n").map((x) => x.trim()).filter(Boolean); return l.length ? l[0].slice(0, 90) : ""; }
  function isOverdue(t) { return t.dueDate != null && !t.completed && t.dueDate < Date.now(); }
  function dueLabel(t) {
    if (t.dueDate == null) return "";
    const d0 = dOf(t.dueDate), t0 = today(), diff = d0 - t0;
    let s;
    if (diff === 0) s = "Today"; else if (diff === 1) s = "Tomorrow"; else if (diff === -1) s = "Yesterday";
    else if (diff > 1 && diff <= 6) s = H.epochDayToDate(d0).toLocaleDateString(undefined, { weekday: "short" });
    else s = H.fmtDate(t.dueDate);
    if (!t.isAllDay) { const dt = new Date(t.dueDate); if (dt.getHours() !== 0 || dt.getMinutes() !== 0) s += " " + H.fmtTime(t.dueDate); }
    return s;
  }
  function childrenOf(id) { return H.state.tasks.filter((t) => t.parentId === id && !t.trashed).sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)); }
  function labelsFor(t) {
    const out = [];
    (t.contextIds || []).slice(0, 2).forEach((id) => { const c = H.state.contexts.find((x) => x.id === id); if (c) out.push('<span class="lbl ctx">@' + H.esc(c.name) + "</span>"); });
    (t.tagIds || []).slice(0, 2).forEach((id) => { const g = H.state.tags.find((x) => x.id === id); if (g) out.push('<span class="lbl tag">#' + H.esc(g.name) + "</span>"); });
    if (!(H.ui.taskFilter.kind === "list" && H.ui.taskFilter.value === t.listId)) { const l = H.state.lists.find((x) => x.id === t.listId); if (l && l.id !== "inbox") out.push('<span class="lbl list">' + H.esc(l.name) + "</span>"); }
    return out;
  }
  function row(t, depth) {
    const lvl = priLevel(t), done = t.completed || t.abandoned;
    const sel = H.ui.sel && H.ui.sel.domain === "tasks" && H.ui.sel.id === t.id;
    const pcfill = { NONE: "0%", LOW: "10%", MED: "14%", HIGH: "18%" }[lvl];
    const kids = H.ui.outline ? childrenOf(t.id) : [];
    const indent = depth ? ' style="margin-left:' + (depth * 22) + 'px"' : "";
    let html = '<li class="trow' + (done ? " done" : "") + (sel ? " sel" : "") + '" data-id="' + H.esc(t.id) + '"' + indent + ">";
    if (H.ui.outline && kids.length) html += '<button class="subtask-toggle' + (t.collapsed ? " collapsed" : "") + '" data-act="open">' + H.icon("expand_more") + "</button>";
    html += '<button class="pcheck' + (H.readOnly ? " static" : "") + '" data-act="toggle" style="--pc:' + priColor(lvl) + ";--pcfill:" + pcfill + '" aria-label="Complete">' + H.icon("check") + "</button>";
    html += '<span class="trow-main" data-act="open"><span class="trow-titleline">' +
      (t.pinned ? '<span class="trow-pin">' + H.icon("push_pin") + "</span>" : "") +
      '<span class="trow-title">' + H.esc(t.title) + "</span></span>";
    const metas = [];
    if (t.dueDate != null) metas.push('<span class="due' + (isOverdue(t) ? " overdue" : "") + '">' + H.icon("event") + H.esc(dueLabel(t)) + "</span>");
    if (t.rrule) metas.push('<span class="due">' + H.icon("repeat") + "</span>");
    labelsFor(t).forEach((l) => metas.push(l));
    if (metas.length) html += '<span class="trow-meta">' + metas.join("") + "</span>";
    if (t.note) html += '<span class="trow-note">' + H.icon("article") + H.esc(firstLine(t.note)) + "</span>";
    html += "</span>";
    if (!H.readOnly) {
      const fc = t.flagColorArgb != null ? H.argb(t.flagColorArgb) : null;
      html += '<span class="trow-trail">' +
        '<button class="flagstar flag ' + (t.flagId ? "on" : "off") + '" data-act="flag" style="' + (fc ? "--fc:" + fc : "") + '" aria-label="Flag">' + H.icon(t.flagId ? "bookmark" : "bookmark_border") + "</button>" +
        '<button class="flagstar star ' + (t.star ? "on" : "off") + '" data-act="star" aria-label="Star">' + H.icon(t.star ? "star" : "star_border") + "</button></span>";
    }
    html += "</li>";
    if (H.ui.outline && kids.length && !t.collapsed) kids.forEach((k) => { html += row(k, (depth || 0) + 1); });
    return html;
  }

  // ----------------------------------------------------------------- views: sidebar
  function treeLists(folderId) {
    return H.state.lists.filter((l) => l.folderId === (folderId || null) && l.id !== "inbox" && !l.archived)
      .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
  }
  function listItem(l) {
    const on = H.ui.taskFilter.kind === "list" && H.ui.taskFilter.value === l.id;
    const dot = H.argb(l.colorArgb);
    const openCount = l.count || 0;
    return '<li class="sb-item' + (on ? " active" : "") + '" data-list="' + H.esc(l.id) + '">' +
      '<span class="sb-ico">' + (l.emoji ? H.esc(l.emoji) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : H.icon("format_list_bulleted"))) + "</span>" +
      '<span class="sb-label">' + H.esc(l.name) + "</span>" + (openCount ? '<span class="sb-count">' + openCount + "</span>" : "") + "</li>";
  }
  function sidebar() {
    const all = H.state.tasks;
    // smart lists, honouring visibility (SHOW/AUTO/HIDE) + custom names
    const smartHtml = SMART.map((s) => {
      const vis = (H.settings.smartListVis && H.settings.smartListVis[s[0]]) || "SHOW";
      const on = H.ui.taskFilter.kind === "smart" && H.ui.taskFilter.value === s[0];
      const count = smartFilter(all, s[0]).length;
      if (vis === "HIDE" && !on) return "";
      if (vis === "AUTO" && count === 0 && !on) return "";
      return '<li class="sb-item' + (on ? " active" : "") + '" data-smart="' + s[0] + '">' +
        '<span class="sb-ico">' + H.icon(s[2]) + "</span><span class=\"sb-label\">" + H.esc(smartTitle(s[0], s[1])) + "</span>" +
        (count ? '<span class="sb-count">' + count + "</span>" : "") + "</li>";
    }).join("");
    // folders (each with its lists) + top-level lists
    const inbox = H.state.lists.find((l) => l.id === "inbox");
    let containers = "";
    const folders = H.state.folders.slice().sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
    folders.forEach((fo) => {
      const onF = H.ui.taskFilter.kind === "folder" && H.ui.taskFilter.value === fo.id;
      const dot = H.argb(fo.colorArgb);
      containers += '<li class="sb-item' + (onF ? " active" : "") + '" data-folder="' + H.esc(fo.id) + '">' +
        '<span class="sb-ico">' + (fo.emoji ? H.esc(fo.emoji) : (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : H.icon("folder"))) + "</span>" +
        '<span class="sb-label">' + H.esc(fo.name) + "</span></li>";
      const kids = treeLists(fo.id);
      if (kids.length) containers += '<ul class="sb-list sb-children">' + kids.map(listItem).join("") + "</ul>";
    });
    containers += treeLists(null).map(listItem).join("");
    const tagsHtml = H.state.tags.length ? '<h3 class="sb-sub">Tags</h3><ul class="sb-list">' + H.state.tags.map((g) => {
      const on = H.ui.taskFilter.kind === "tag" && H.ui.taskFilter.value === g.id; const dot = H.argb(g.colorArgb);
      return '<li class="sb-item' + (on ? " active" : "") + '" data-tag="' + H.esc(g.id) + '"><span class="sb-ico">' + (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : "#") + "</span><span class=\"sb-label\">" + H.esc(g.name) + "</span></li>";
    }).join("") + "</ul>" : "";
    const ctxHtml = H.state.contexts.length ? '<h3 class="sb-sub">Contexts</h3><ul class="sb-list">' + H.state.contexts.map((c) => {
      const on = H.ui.taskFilter.kind === "context" && H.ui.taskFilter.value === c.id; const dot = H.argb(c.colorArgb);
      return '<li class="sb-item' + (on ? " active" : "") + '" data-context="' + H.esc(c.id) + '"><span class="sb-ico">' + (dot ? '<span class="sb-dot" style="background:' + dot + '"></span>' : "@") + "</span><span class=\"sb-label\">" + H.esc(c.name) + "</span></li>";
    }).join("") + "</ul>" : "";
    return '<h2 class="sb-title">Tasks</h2><ul class="sb-list">' + smartHtml + "</ul>" +
      (containers ? '<h3 class="sb-sub">Folders & lists</h3><ul class="sb-list">' + containers + "</ul>" : "") + tagsHtml + ctxHtml;
  }

  // ----------------------------------------------------------------- views: head + main
  function head() {
    const views = [["list", "List", "format_list_bulleted"], ["board", "Board", "view_week"]];
    const seg = views.map((v) => '<button class="seg' + (H.ui.taskView === v[0] ? " on" : "") + '" data-view="' + v[0] + '">' + H.icon(v[2]) + v[1] + "</button>").join("");
    let controls = "";
    if (H.ui.taskView === "list") {
      const groups = [["none", "No grouping"], ["date", "Group: Date"], ["priority", "Group: Priority"], ["context", "Group: Context"], ["flag", "Group: Flag"]];
      const sorts = [["manual", "Sort: Manual"], ["priority", "Sort: Priority"], ["due", "Sort: Due"], ["title", "Sort: Title"], ["flag", "Sort: Flag"], ["score", "Sort: Score"]];
      controls = '<select class="head-select" id="grpSel">' + groups.map((g) => '<option value="' + g[0] + '"' + (H.ui.taskGroup === g[0] ? " selected" : "") + ">" + g[1] + "</option>").join("") + "</select>" +
        '<select class="head-select" id="srtSel">' + sorts.map((g) => '<option value="' + g[0] + '"' + (H.ui.taskSort === g[0] ? " selected" : "") + ">" + g[1] + "</option>").join("") + "</select>" +
        '<button class="icon-btn' + (H.ui.outline ? " on" : "") + '" id="outlineBtn" title="Outline / nest subtasks">' + H.icon("checklist") + "</button>";
    } else {
      const bys = [["priority", "Priority"], ["flag", "Flag"], ["list", "List"]];
      controls = '<div class="segmented sm">' + bys.map((b) => '<button class="seg' + (H.ui.boardBy === b[0] ? " on" : "") + '" data-by="' + b[0] + '">' + b[1] + "</button>").join("") + "</div>";
    }
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar">' + H.icon("menu") + "</button>" +
      '<h1 class="main-title">' + H.esc(filterLabel()) + "</h1>" +
      '<div class="segmented">' + seg + "</div>" + controls + '<span class="head-spacer"></span>' +
      (H.readOnly ? "" : '<button class="btn primary" data-new="task">' + H.icon("add") + "New task</button>");
  }
  function main() {
    if (H.ui.taskView === "board") return boardMain();
    return listMain();
  }
  function listMain() {
    const quick = H.readOnly ? "" : '<form id="quickAdd" class="quickadd"><input id="quickAddInput" type="text" autocomplete="off" placeholder="Add a task…  (!today  *star  #tag  @ctx)"><button class="btn primary">Add</button></form>';
    let ts = currentTasks();
    if (!ts.length) return quick + H.empty("Nothing here", H.readOnly ? "" : "Add a task above, or pick another list.");
    ts = sortTasks(ts);
    const groups = groupTasks(ts);
    const body = groups.map((g) => (g.title ? '<h3 class="group-h">' + H.esc(g.title) + "</h3>" : "") + '<ul class="tlist">' + g.tasks.map((t) => row(t, 0)).join("") + "</ul>").join("");
    return quick + body;
  }
  function boardMain() {
    const ts = currentTasks().filter((t) => isOpen(t));
    let cols;
    if (H.ui.boardBy === "flag") {
      cols = H.state.flags.map((f) => ({ key: f.id, name: f.name, color: H.argb(f.colorArgb) || "var(--accent)", items: ts.filter((t) => t.flagId === f.id) }));
      cols.push({ key: "none", name: "No flag", color: "var(--muted)", items: ts.filter((t) => !t.flagId) });
    } else if (H.ui.boardBy === "list") {
      cols = H.state.lists.filter((l) => !l.archived).map((l) => ({ key: l.id, name: l.name, color: H.argb(l.colorArgb) || "var(--accent)", items: ts.filter((t) => t.listId === l.id) }));
    } else {
      const order = [["HIGH", "High", "var(--p-high)"], ["MED", "Medium", "var(--p-med)"], ["LOW", "Low", "var(--p-low)"], ["NONE", "None", "var(--p-none)"]];
      cols = order.map((o) => ({ key: o[0], name: o[1], color: o[2], items: ts.filter((t) => priLevel(t) === o[0]) }));
    }
    const html = cols.map((c) => {
      const cards = c.items.map(taskCard).join("") || '<p class="col-empty">—</p>';
      return '<div class="bcol" data-col="' + H.esc(c.key) + '" style="--bcol:' + c.color + '"><div class="bcol-h"><span class="bcol-dot"></span>' + H.esc(c.name) + '<span class="bcol-n">' + c.items.length + '</span></div><div class="bcol-body">' + cards + "</div></div>";
    }).join("");
    return '<div class="board">' + html + "</div>";
  }
  function taskCard(t) {
    const lvl = priLevel(t);
    return '<div class="tcard" draggable="' + (!H.readOnly) + '" data-id="' + H.esc(t.id) + '" data-act="open">' +
      '<span class="tcard-title">' + (t.star ? H.icon("star") : "") + H.esc(t.title) + "</span>" +
      (t.dueDate != null ? '<span class="tcard-meta"><span class="due' + (isOverdue(t) ? " overdue" : "") + '">' + H.esc(dueLabel(t)) + "</span></span>" : "") + "</div>";
  }

  // ----------------------------------------------------------------- wiring
  function parseQuick(raw) {
    let title = (raw || "").trim(); let star = false, due = null; const tags = [], ctx = [];
    if (/(^|\s)\*(\s|$)/.test(title)) { star = true; title = title.replace(/(^|\s)\*(\s|$)/, " ").trim(); }
    title = title.replace(/#(\w[\w-]*)/g, (_m, n) => { tags.push(n); return ""; }).replace(/@(\w[\w-]*)/g, (_m, n) => { ctx.push(n); return ""; });
    const m = title.match(/!(today|tomorrow|mon|tue|wed|thu|fri|sat|sun)\b/i);
    if (m) { due = dueFromWord(m[1].toLowerCase()); title = title.replace(m[0], ""); }
    return { title: title.replace(/\s+/g, " ").trim(), star, due, tags, ctx };
  }
  function dueFromWord(w) {
    const d = new Date(); d.setHours(12, 0, 0, 0);
    if (w === "today") return d.getTime(); if (w === "tomorrow") return d.getTime() + DAY;
    const map = { sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 };
    if (w in map) { let delta = (map[w] - d.getDay() + 7) % 7; if (delta === 0) delta = 7; return d.getTime() + delta * DAY; }
    return d.getTime();
  }
  async function wire() {
    const head = H.el("mainHead"), body = H.el("mainBody");
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    head.querySelectorAll(".seg[data-view]").forEach((b) => b.onclick = () => { H.ui.taskView = b.dataset.view; H.saveUi(); H.render(); });
    head.querySelectorAll(".seg[data-by]").forEach((b) => b.onclick = () => { H.ui.boardBy = b.dataset.by; H.saveUi(); H.render(); });
    const gs = H.el("grpSel"); if (gs) gs.onchange = () => { H.ui.taskGroup = gs.value; H.saveUi(); H.render(); };
    const ss = H.el("srtSel"); if (ss) ss.onchange = () => { H.ui.taskSort = ss.value; H.saveUi(); H.render(); };
    const ob = H.el("outlineBtn"); if (ob) ob.onclick = () => { H.ui.outline = !H.ui.outline; H.saveUi(); H.render(); };
    const nb = head.querySelector('[data-new="task"]'); if (nb) nb.onclick = () => H.openInspector("tasks", "__new__");
    if (!H.readOnly) {
      const form = H.el("quickAdd");
      if (form) form.onsubmit = async (e) => {
        e.preventDefault(); const input = H.el("quickAddInput"); const p = parseQuick(input.value);
        if (!p.title) return; input.value = "";
        const payload = { id: "", title: p.title, star: p.star };
        if (p.due != null) payload.dueDate = p.due;
        if (H.ui.taskFilter.kind === "list") payload.listId = H.ui.taskFilter.value;
        try { const res = await H.api.mutate("tasks", "upsert", payload); const id = res && res.id; await H.reloadDomain("tasks"); if (id && (p.tags.length || p.ctx.length)) await applyQuickLabels(id, p); H.render(); } catch (err) { H.toast(err.message); }
      };
    }
    body.onclick = onBodyClick;
    if (!H.readOnly && H.ui.taskView === "board") wireDrag(body);
  }
  async function applyQuickLabels(taskId, p) {
    try {
      if (p.tags.length) { const ids = p.tags.map((n) => (H.state.tags.find((g) => g.name.toLowerCase() === n.toLowerCase()) || {}).id).filter(Boolean); if (ids.length) await H.api.mutate("tasks", "setTags", { id: taskId, tagIds: ids }); }
      if (p.ctx.length) { const ids = p.ctx.map((n) => (H.state.contexts.find((c) => c.name.toLowerCase() === n.toLowerCase()) || {}).id).filter(Boolean); if (ids.length) await H.api.mutate("tasks", "setContexts", { id: taskId, contextIds: ids }); }
      await H.reloadDomain("tasks");
    } catch (_e) { /* labels best-effort */ }
  }
  async function onBodyClick(e) {
    const act = e.target.closest("[data-act]") ? e.target.closest("[data-act]").dataset.act : null;
    const rowEl = e.target.closest("[data-id]"); if (!rowEl) return;
    const t = H.state.tasks.find((x) => x.id === rowEl.dataset.id); if (!t) return;
    if (act === "toggle" && !H.readOnly) {
      const want = !t.completed; t.completed = want; H.render();
      try { await H.api.mutate("tasks", "complete", { id: t.id, completed: want }); await H.reloadDomain("tasks"); H.render(); } catch (err) { t.completed = !want; H.render(); H.toast(err.message); }
    } else if (act === "star" && !H.readOnly) {
      const want = !t.star; t.star = want; H.render();
      try { await H.api.mutate("tasks", "star", { id: t.id, star: want }); await H.reloadDomain("tasks"); H.render(); } catch (err) { t.star = !want; H.render(); H.toast(err.message); }
    } else if (act === "flag" && !H.readOnly) {
      await cycleFlag(t);
    } else {
      H.openInspector("tasks", t.id);
    }
  }
  async function cycleFlag(t) {
    const flags = H.state.flags; if (!flags.length) { H.toast("No flags defined"); return; }
    const idx = t.flagId ? flags.findIndex((f) => f.id === t.flagId) : -1;
    const next = idx < 0 ? flags[0] : (idx + 1 >= flags.length ? null : flags[idx + 1]);
    try { await H.api.mutate("tasks", "flag", { id: t.id, flagId: next ? next.id : null }); await H.reloadDomain("tasks"); H.render(); } catch (err) { H.toast(err.message); }
  }
  function wireDrag(body) {
    let dragId = null;
    body.addEventListener("dragstart", (e) => { const c = e.target.closest("[data-id]"); if (c) { dragId = c.dataset.id; e.dataTransfer.effectAllowed = "move"; } });
    body.addEventListener("dragover", (e) => { if (e.target.closest(".bcol")) e.preventDefault(); });
    body.addEventListener("drop", async (e) => {
      const col = e.target.closest(".bcol"); if (!dragId || !col) return; e.preventDefault();
      const t = H.state.tasks.find((x) => x.id === dragId); const key = col.dataset.col; dragId = null; if (!t) return;
      try {
        if (H.ui.boardBy === "priority") { const map = { HIGH: [5, 5], MED: [4, 4], LOW: [3, 3], NONE: [2, 2] }; const v = map[key] || [3, 3]; await H.api.mutate("tasks", "setPriority", { id: t.id, importance: v[0], urgency: v[1] }); }
        else if (H.ui.boardBy === "flag") await H.api.mutate("tasks", "flag", { id: t.id, flagId: key === "none" ? null : key });
        else if (H.ui.boardBy === "list") await H.api.mutate("tasks", "move", { id: t.id, listId: key });
        await H.reloadDomain("tasks"); H.render();
      } catch (err) { H.toast(err.message); }
    });
  }
  function wireSidebar() {
    const sb = H.el("sidebar"); if (!sb) return;
    sb.onclick = (e) => {
      const item = e.target.closest(".sb-item"); if (!item) return;
      if (item.dataset.smart) H.ui.taskFilter = { kind: "smart", value: item.dataset.smart };
      else if (item.dataset.list) H.ui.taskFilter = { kind: "list", value: item.dataset.list };
      else if (item.dataset.folder) H.ui.taskFilter = { kind: "folder", value: item.dataset.folder };
      else if (item.dataset.tag) H.ui.taskFilter = { kind: "tag", value: item.dataset.tag };
      else if (item.dataset.context) H.ui.taskFilter = { kind: "context", value: item.dataset.context };
      else return;
      H.saveUi(); H.closeInspector(); H.render();
      if (window.innerWidth < 960) { H.ui.sidebar = false; H.render(); }
    };
  }

  H.views.tasks = { title: "Tasks", sidebar, head, main, wire: function () { wire(); wireSidebar(); } };
  H.newTaskSeed = function () { const s = {}; if (H.ui.taskFilter.kind === "list") s.listId = H.ui.taskFilter.value; return s; };

  // =============================================================== MATRIX (own top-level view)
  const QUAD_COLORS = ["var(--p-high)", "var(--warn)", "var(--p-low)", "var(--c2)"];
  const QUAD_HINTS = ["Do first", "Schedule", "Delegate", "Later"];
  const ROMAN = ["I", "II", "III", "IV"];
  function matrixNames() { const n = H.settings.matrixNames; return (n && n.length === 4) ? n : ["Urgent & Important", "Not Urgent & Important", "Urgent & Unimportant", "Not Urgent & Unimportant"]; }
  function quadrantOf(t) {
    const imp = (t.importance == null ? 3 : t.importance) >= H.settings.matrixImportanceThreshold;
    const urg = (t.urgency == null ? 3 : t.urgency) >= H.settings.matrixUrgencyThreshold;
    return urg && imp ? 0 : !urg && imp ? 1 : urg && !imp ? 2 : 3;
  }
  function matrixDateEnd() {
    const t0 = today();
    switch (H.settings.matrixDateFilter) {
      case "today": return t0 + 1; case "today_tom": return t0 + 2; case "this_week": return H.localEpochDay(H.addDays(H.weekStart(Date.now()), 7));
      case "week_next": return H.localEpochDay(H.addDays(H.weekStart(Date.now()), 14));
      case "this_month": { const d = new Date(); return H.localEpochDay(new Date(d.getFullYear(), d.getMonth() + 1, 1).getTime()); }
      case "month_next": { const d = new Date(); return H.localEpochDay(new Date(d.getFullYear(), d.getMonth() + 2, 1).getTime()); }
      default: return null;
    }
  }
  function matrixTasks() {
    const s = H.settings, now = Date.now(), dateEnd = matrixDateEnd();
    const lf = new Set(s.matrixListFilter || []), ff = new Set(s.matrixFolderFilter || []);
    return H.state.tasks.filter((t) => {
      if (t.trashed || t.abandoned || t.someday) return false;
      if (!s.matrixShowCompleted && t.completed) return false;
      if (lf.size && !lf.has(t.listId)) return false;
      if (ff.size && !ff.has(t.folderId)) return false;
      if (s.matrixMaxDuration > 0) { const d = t.estimateMin != null ? t.estimateMin : (t.estimateMax != null ? t.estimateMax : t.durationMin); if (d != null && d > s.matrixMaxDuration) return false; }
      if (s.matrixOverdueOnly && !(t.dueDate != null && t.dueDate < now && !t.completed)) return false;
      if (dateEnd != null && !(t.dueDate != null && dOf(t.dueDate) < dateEnd)) return false;
      return true;
    });
  }
  function matrixSortCmp() {
    switch (H.settings.matrixSort) {
      case "due": return (a, b) => (a.dueDate || 8.64e15) - (b.dueDate || 8.64e15);
      case "created": return (a, b) => (a.createdAt || 0) - (b.createdAt || 0);
      case "alpha": return (a, b) => (a.title || "").toLowerCase().localeCompare((b.title || "").toLowerCase());
      case "manual": return (a, b) => (a.sortOrder || 0) - (b.sortOrder || 0) || (a.createdAt || 0) - (b.createdAt || 0);
      default: return (a, b) => (priRank(b) - priRank(a)) || (Number(b.star) - Number(a.star));
    }
  }
  function matrixMain() {
    const names = matrixNames(), ts = matrixTasks(), cmp = matrixSortCmp();
    const byQ = [[], [], [], []]; ts.forEach((t) => byQ[quadrantOf(t)].push(t)); byQ.forEach((q) => q.sort(cmp));
    const cells = [0, 1, 2, 3].map((q) => {
      if (H.settings.matrixHideEmpty && !byQ[q].length) return "";
      const cards = byQ[q].map(taskCard).join("") || '<p class="col-empty">—</p>';
      return '<div class="quad" data-quad="' + q + '" style="--qc:' + QUAD_COLORS[q] + '"><div class="quad-h"><span class="quad-roman">' + ROMAN[q] + "</span>" + H.esc(names[q]) + '<span class="quad-hint">' + QUAD_HINTS[q] + "</span></div><div class=\"quad-body\">" + cards + "</div></div>";
    }).join("");
    return '<div class="matrix">' + cells + "</div>";
  }
  function matrixWire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    const body = H.el("mainBody");
    body.onclick = (e) => { const c = e.target.closest("[data-id]"); if (c) H.openInspector("tasks", c.dataset.id); };
    if (H.readOnly) return;
    let dragId = null;
    body.addEventListener("dragstart", (e) => { const c = e.target.closest("[data-id]"); if (c) { dragId = c.dataset.id; e.dataTransfer.effectAllowed = "move"; } });
    body.addEventListener("dragover", (e) => { if (e.target.closest(".quad")) e.preventDefault(); });
    body.addEventListener("drop", async (e) => {
      const quad = e.target.closest(".quad"); if (!dragId || !quad) return; e.preventDefault();
      const t = H.state.tasks.find((x) => x.id === dragId); const q = Number(quad.dataset.quad); dragId = null; if (!t) return;
      const important = q === 0 || q === 1, urgent = q === 0 || q === 2;
      const imp = important ? 5 : Math.min(5, Math.max(1, H.settings.matrixImportanceThreshold - 1));
      const urg = urgent ? 5 : Math.min(5, Math.max(1, H.settings.matrixUrgencyThreshold - 1));
      try { await H.api.mutate("tasks", "setPriority", { id: t.id, importance: imp, urgency: urg }); await H.reloadDomain("tasks"); H.render(); } catch (err) { H.toast(err.message); }
    });
  }
  H.views.matrix = {
    title: "Matrix",
    head: function () { return H.headTitle("Eisenhower Matrix"); },
    sidebar: function () { return '<h2 class="sb-title">Matrix</h2><p class="sb-hint">Quadrants by importance × urgency. Drag a task to reclassify.</p>'; },
    main: matrixMain, wire: matrixWire,
  };
})();
