"use strict";
/* Hexis Web — Calendar. Full parity with the mobile CalendarScreen: six modes (Agenda/Day/3-Day/Week/
 * Month/Year), tasks placed on the grid by dueDate (midnight = all-day), events as a read-only lane,
 * honouring week_start, time_format/zone, cal_mode default, cal_remember, cal_show_completed and the
 * task list/folder filter (cal_filter). Phone-only assist bands (capacity ledger, auto-fill, lived-time
 * shadows, quick-add) are intentionally out of scope for the web. */
(function () {
  const H = window.H;
  const addDays = H.addDays, startOfDay = H.startOfDay, sameDay = H.sameDay, weekStart = H.weekStart, DAY = H.DAY;
  const MODES = [["list", "Agenda"], ["day", "Day"], ["3day", "3-Day"], ["week", "Week"], ["month", "Month"], ["year", "Year"]];
  const MODE_KEYS = MODES.map((m) => m[0]);
  const HOUR = 46; // px per hour in the timeline grids
  const startOfToday = () => startOfDay(Date.now());

  // one-time: honour cal_remember / cal_mode and normalise a legacy persisted mode
  let inited = false;
  function initMode() {
    if (inited) return; inited = true;
    if (H.ui.calView === "agenda") H.ui.calView = "list";
    if (!MODE_KEYS.includes(H.ui.calView)) H.ui.calView = H.settings.calendarDefaultMode || "month";
    else if (!H.settings.calendarRememberLast) H.ui.calView = H.settings.calendarDefaultMode || "month";
  }

  // ---------- settings-driven task eligibility ----------
  function listFolder(listId) { const l = H.state.lists.find((x) => x.id === listId); return l ? l.folderId : null; }
  function taskInFilter(t) { const f = H.settings.calendarListFilter || []; if (!f.length) return true; return f.includes(t.listId) || f.includes(t.folderId) || f.includes(listFolder(t.listId)); }
  function calTasks() {
    const showDone = !!H.settings.calendarShowCompleted;
    return H.state.tasks.filter((t) => t.dueDate != null && !t.trashed && !t.abandoned && !t.isNote && (showDone || !t.completed) && taskInFilter(t));
  }
  function hasTime(ms) { if (ms == null) return false; const d = new Date(ms); return !(d.getHours() === 0 && d.getMinutes() === 0); }
  function isAllDayTask(t) { return t.isAllDay || !hasTime(t.dueDate); }
  function priLevel(t) { return Math.max(t.importance || 0, t.urgency || 0); }
  function priColor(t) { const l = priLevel(t); return l >= 5 ? "var(--p-high)" : l >= 4 ? "var(--p-med)" : l >= 3 ? "var(--p-low)" : "var(--accent)"; }
  function taskDur(t) { return Math.max(20, t.durationMin || t.estimateMin || 30); }
  function tasksDueOn(dayMs) { const d0 = startOfDay(dayMs); return calTasks().filter((t) => startOfDay(t.dueDate) === d0); }

  // ---------- events ----------
  function calColor(e) {
    if (e && e.colorArgb != null) return H.argb(e.colorArgb);
    const c = H.state.calendars.find((x) => x.id === (e && e.calendarId));
    return (c && H.argb(c.colorArgb)) || "#4f46e5";
  }
  function eventsOnDay(dayMs) {
    const d0 = startOfDay(dayMs), d1 = addDays(d0, 1);
    return H.state.calendar.filter((e) => (e.startMillis < d1) && ((e.endMillis || e.startMillis) >= d0 || sameDay(e.startMillis, d0)))
      .sort((a, b) => (a.allDay === b.allDay ? a.startMillis - b.startMillis : (a.allDay ? -1 : 1)));
  }

  // ---------- windows + labels ----------
  function monthGrid(anchorMs) { const a = new Date(anchorMs); const g = weekStart(new Date(a.getFullYear(), a.getMonth(), 1).getTime()); return { gridStart: g, gridEnd: addDays(g, 42), month: a.getMonth(), year: a.getFullYear() }; }
  function calWindow() {
    const a = H.ui.calAnchor, v = H.ui.calView;
    if (v === "month") { const g = monthGrid(a); return { from: g.gridStart, to: g.gridEnd }; }
    if (v === "week") { const s = weekStart(a); return { from: s, to: addDays(s, 7) }; }
    if (v === "3day") { const s = startOfDay(a); return { from: s, to: addDays(s, 3) }; }
    if (v === "day") { const s = startOfDay(a); return { from: s, to: addDays(s, 1) }; }
    if (v === "year") { const y = new Date(a).getFullYear(); return { from: new Date(y, 0, 1).getTime(), to: new Date(y + 1, 0, 1).getTime() }; }
    const s = startOfToday(); return { from: addDays(s, -31), to: addDays(s, 365) }; // agenda (events unused)
  }
  function dowLabels() { const base = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"], ws = H.weekStartDow(), out = []; for (let i = 0; i < 7; i++) out.push(base[(ws + i) % 7]); return out; }
  function rangeTitle(a, b) {
    const s = new Date(a), e = new Date(b), sMon = s.toLocaleDateString(undefined, { month: "short" }), eMon = e.toLocaleDateString(undefined, { month: "short" });
    if (s.getMonth() === e.getMonth()) return sMon + " " + s.getDate() + " – " + e.getDate() + ", " + e.getFullYear();
    return sMon + " " + s.getDate() + " – " + eMon + " " + e.getDate() + ", " + e.getFullYear();
  }
  function calTitle() {
    const a = H.ui.calAnchor, v = H.ui.calView;
    if (v === "month") return new Date(a).toLocaleDateString(undefined, { month: "long", year: "numeric" });
    if (v === "week") { const s = weekStart(a); return rangeTitle(s, addDays(s, 6)); }
    if (v === "3day") return rangeTitle(startOfDay(a), addDays(startOfDay(a), 2));
    if (v === "day") return new Date(a).toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short" });
    if (v === "year") return String(new Date(a).getFullYear());
    return "Agenda";
  }

  // ---------- head + sidebar ----------
  function head() {
    initMode();
    const modeSel = '<select class="head-select" id="calMode" title="View">' + MODES.map((m) => '<option value="' + m[0] + '"' + (H.ui.calView === m[0] ? " selected" : "") + ">" + m[1] + "</option>").join("") + "</select>";
    const nav = '<div class="cal-nav"><button class="icon-btn" data-cal="prev" title="Previous">' + H.icon("chevron_left") + '</button><button class="btn ghost" data-cal="jump-today">Today</button><button class="icon-btn" data-cal="next" title="Next">' + H.icon("chevron_right") + "</button></div>";
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar" aria-label="Toggle sidebar">' + H.icon("menu") + '</button><h1 class="main-title">' + H.esc(calTitle()) + "</h1>" + nav + modeSel +
      '<span class="head-spacer"></span>' + (H.readOnly ? "" : '<button class="btn primary" data-cal="new">' + H.icon("add") + "New event</button>");
  }
  function sidebar() {
    const d = new Date(H.ui.calAnchor);
    const big = '<div class="cal-today" data-cal="today"><span class="cal-today-dow">' + d.toLocaleDateString(undefined, { weekday: "long" }) + '</span><span class="cal-today-day">' + d.getDate() + '</span><span class="cal-today-mon">' + d.toLocaleDateString(undefined, { month: "long", year: "numeric" }) + "</span></div>";
    const newBtn = H.readOnly ? "" : '<button class="btn primary block" data-cal="new">New event</button>';
    const filterNote = (H.settings.calendarListFilter && H.settings.calendarListFilter.length) ? '<p class="sb-hint">Showing tasks from your calendar list filter.</p>' : "";
    const legend = H.state.calendars.length ? '<h3 class="sb-sub">Calendars</h3><ul class="sb-list">' + H.state.calendars.map((c) => '<li class="sb-item static"><span class="sb-ico"><span class="sb-dot" style="background:' + (H.argb(c.colorArgb) || "var(--accent)") + '"></span></span><span class="sb-label">' + H.esc(c.name) + "</span>" + (c.isDefault ? '<span class="sb-count">default</span>' : "") + "</li>").join("") + "</ul>" : "";
    const legendT = '<h3 class="sb-sub">On the calendar</h3><ul class="sb-list"><li class="sb-item static"><span class="sb-ico"><span class="sb-dot" style="background:var(--accent)"></span></span><span class="sb-label">Tasks (by due date)</span></li></ul>';
    return '<h2 class="sb-title">Calendar</h2>' + big + newBtn + legendT + filterNote + legend;
  }
  function main() {
    initMode();
    const v = H.ui.calView;
    if (v === "month") return monthView();
    if (v === "year") return yearView();
    if (v === "list") return agendaView();
    if (v === "day") return timeline([startOfDay(H.ui.calAnchor)]);
    if (v === "3day") { const s = startOfDay(H.ui.calAnchor); return timeline([s, addDays(s, 1), addDays(s, 2)]); }
    const ws = weekStart(H.ui.calAnchor); const days = []; for (let i = 0; i < 7; i++) days.push(addDays(ws, i)); return timeline(days);
  }

  // ---------- timeline (Day / 3-Day / Week) ----------
  function layout(items) { // items: {start(min), dur(min), ...}; returns with lane/lanes
    items.sort((a, b) => a.start - b.start); let i = 0;
    while (i < items.length) {
      let j = i, end = items[i].start + items[i].dur; const cl = [items[i]];
      while (j + 1 < items.length && items[j + 1].start < end) { j++; cl.push(items[j]); end = Math.max(end, items[j].start + items[j].dur); }
      const laneEnds = [];
      cl.forEach((it) => { let ln = laneEnds.findIndex((e) => e <= it.start); if (ln === -1) { ln = laneEnds.length; laneEnds.push(0); } laneEnds[ln] = it.start + it.dur; it.lane = ln; });
      cl.forEach((it) => { it.lanes = laneEnds.length; }); i = j + 1;
    }
    return items;
  }
  function timeline(days) {
    const today0 = startOfToday(), multi = days.length > 1;
    const heads = multi ? days.map((day) => { const d = new Date(day); return '<div class="wv-colh' + (day === today0 ? " today" : "") + '" data-day="' + day + '"><span class="wv-dow">' + d.toLocaleDateString(undefined, { weekday: "short" }) + '</span><span class="wv-dnum">' + d.getDate() + "</span></div>"; }).join("") : "";
    // all-day row: all-day tasks (priority) + all-day events (calendar colour)
    const allday = days.map((day) => {
      const chips = tasksDueOn(day).filter(isAllDayTask).map((t) => '<button class="echip task" data-task="' + H.esc(t.id) + '" style="--ec:' + priColor(t) + '"><span class="echip-dot"></span><span class="echip-t">' + H.esc(t.title || "Untitled") + "</span></button>").join("")
        + eventsOnDay(day).filter((e) => e.allDay).map((e) => eChip(e, false)).join("");
      return '<div class="wv-allday-cell" data-day="' + day + '">' + chips + "</div>";
    }).join("");
    let axis = ""; for (let h = 0; h < 24; h++) axis += '<div class="wv-hr" style="height:' + HOUR + 'px"><span>' + hourLabel(h) + "</span></div>";
    const cols = days.map((day) => {
      const d0 = startOfDay(day), d1 = addDays(d0, 1), items = [];
      tasksDueOn(day).filter((t) => !isAllDayTask(t)).forEach((t) => { const d = new Date(t.dueDate); items.push({ kind: "task", ref: t, start: d.getHours() * 60 + d.getMinutes(), dur: taskDur(t) }); });
      eventsOnDay(day).filter((e) => !e.allDay).forEach((e) => { const s = Math.max(e.startMillis, d0), en = Math.min(e.endMillis || (e.startMillis + 3600000), d1); items.push({ kind: "event", ref: e, start: (s - d0) / 60000, dur: Math.max(15, (en - s) / 60000) }); });
      const laid = layout(items).map((p) => {
        const top = (p.start / 60) * HOUR, h = Math.max(16, (p.dur / 60) * HOUR), w = (100 / p.lanes), left = p.lane * w;
        if (p.kind === "task") { const t = p.ref; return '<button class="wv-ev task' + (t.completed ? " done" : "") + '" data-task="' + H.esc(t.id) + '" style="top:' + top + "px;height:" + h + "px;left:" + left.toFixed(2) + "%;width:" + w.toFixed(2) + "%;--ec:" + priColor(t) + '"><span class="wv-ev-t">' + H.esc(t.title || "Untitled") + '</span><span class="wv-ev-m">' + H.esc(H.fmtTime(t.dueDate)) + "</span></button>"; }
        const e = p.ref; return '<button class="wv-ev" data-id="' + H.esc(e.id) + '" style="top:' + top + "px;height:" + h + "px;left:" + left.toFixed(2) + "%;width:" + w.toFixed(2) + "%;--ec:" + calColor(e) + '"><span class="wv-ev-t">' + H.esc(e.title || "Untitled") + '</span><span class="wv-ev-m">' + H.esc(H.fmtTime(e.startMillis)) + "</span></button>";
      }).join("");
      return '<div class="wv-col" data-day="' + day + '" style="height:' + (24 * HOUR) + 'px">' + hourLines() + (day === today0 ? nowLine() : "") + laid + "</div>";
    }).join("");
    return '<div class="wv' + (multi ? "" : " single") + '">' + (multi ? '<div class="wv-head"><div class="wv-corner"></div>' + heads + "</div>" : "") +
      '<div class="wv-alldays"><div class="wv-allday-label">all-day</div>' + allday + '</div><div class="wv-body" id="wvBody"><div class="wv-axis">' + axis + "</div>" + cols + "</div></div>";
  }
  function hourLabel(h) { if (H.use24() === true) return String(h).padStart(2, "0") + ":00"; if (h === 0) return "12 AM"; if (h === 12) return "12 PM"; return (h < 12 ? h + " AM" : (h - 12) + " PM"); }
  function hourLines() { let s = ""; for (let h = 0; h < 24; h++) s += '<div class="wv-line" style="top:' + (h * HOUR) + 'px"></div>'; return s; }
  function nowLine() { const n = new Date(), m = n.getHours() * 60 + n.getMinutes(); return '<div class="wv-now" style="top:' + ((m / 60) * HOUR).toFixed(1) + 'px"></div>'; }
  function eChip(e, withTime) {
    const t = e.allDay ? "" : (withTime ? H.fmtTime(e.startMillis) + " " : "");
    return '<button class="echip" data-id="' + H.esc(e.id) + '" style="--ec:' + calColor(e) + '"><span class="echip-dot"></span><span class="echip-t">' + H.esc(t) + H.esc(e.title || "Untitled") + "</span>" + (e.recurring ? '<span class="echip-r">' + H.icon("repeat") + "</span>" : "") + "</button>";
  }

  // ---------- month (chips: tasks + events) ----------
  function monthView() {
    const g = monthGrid(H.ui.calAnchor), today0 = startOfToday();
    const head = '<div class="mv-dow">' + dowLabels().map((d) => "<span>" + d + "</span>").join("") + "</div>";
    let cells = "";
    for (let i = 0; i < 42; i++) {
      const day = addDays(g.gridStart, i), dd = new Date(day), inMonth = dd.getMonth() === g.month;
      const evs = inMonth ? eventsOnDay(day) : [], tks = inMonth ? tasksDueOn(day) : [];
      const chips = [].concat(
        evs.filter((e) => e.allDay).map((e) => eChip(e, false)),
        tks.filter(isAllDayTask).map(taskChip),
        evs.filter((e) => !e.allDay).map((e) => eChip(e, true)),
        tks.filter((t) => !isAllDayTask(t)).map(taskChip)
      );
      const shown = chips.slice(0, 4), extra = chips.length - shown.length;
      cells += '<div class="mv-cell' + (inMonth ? "" : " dim") + (day === today0 ? " today" : "") + '" data-day="' + day + '"><div class="mv-daynum">' + dd.getDate() + '</div><div class="mv-evs">' + shown.join("") + (extra > 0 ? '<span class="mv-more">+' + extra + " more</span>" : "") + "</div></div>";
    }
    return head + '<div class="mv-grid">' + cells + "</div>";
  }
  function taskChip(t) {
    return '<button class="echip task' + (t.completed ? " done" : "") + '" data-task="' + H.esc(t.id) + '" style="--ec:' + priColor(t) + '"><span class="echip-dot"></span><span class="echip-t">' + (hasTime(t.dueDate) && !isAllDayTask(t) ? H.esc(H.fmtTime(t.dueDate)) + " " : "") + H.esc(t.title || "Untitled") + "</span>" + (t.star ? '<span class="echip-r">' + H.icon("star") + "</span>" : "") + "</button>";
  }

  // ---------- year (mini-months, Monday-start, task-count heat) ----------
  function yearView() {
    const y = new Date(H.ui.calAnchor).getFullYear(), today0 = startOfToday(), curMonth = new Date().getMonth(), curYear = new Date().getFullYear();
    const countByDay = {}; calTasks().forEach((t) => { const d = new Date(t.dueDate); if (d.getFullYear() === y) { const k = startOfDay(t.dueDate); countByDay[k] = (countByDay[k] || 0) + 1; } });
    let out = "";
    for (let m = 0; m < 12; m++) {
      const first = new Date(y, m, 1), lead = (first.getDay() - 1 + 7) % 7, dim = new Date(y, m + 1, 0).getDate();
      let cellsHtml = ["M", "T", "W", "T", "F", "S", "S"].map((d) => '<span class="ym-dow">' + d + "</span>").join("");
      for (let i = 0; i < lead; i++) cellsHtml += '<span class="ym-cell empty"></span>';
      for (let dnum = 1; dnum <= dim; dnum++) {
        const dayMs = new Date(y, m, dnum).getTime(), n = countByDay[dayMs] || 0, isToday = dayMs === today0;
        const op = n ? Math.min(0.72, 0.12 + 0.12 * Math.min(n, 5)) : 0;
        const style = isToday ? 'style="background:var(--accent);color:var(--accent-ink)"' : (n ? 'style="background:color-mix(in srgb, var(--accent) ' + Math.round(op * 100) + '%, transparent)"' : "");
        cellsHtml += '<span class="ym-cell' + (isToday ? " today" : "") + '" data-day="' + dayMs + '" ' + style + ">" + dnum + "</span>";
      }
      const isCur = m === curMonth && y === curYear;
      out += '<div class="ym"><h4 class="ym-h' + (isCur ? " cur" : "") + '" data-month="' + m + '">' + new Date(y, m, 1).toLocaleDateString(undefined, { month: "long" }) + '</h4><div class="ym-grid">' + cellsHtml + "</div></div>";
    }
    return '<div class="yv">' + out + "</div>";
  }

  // ---------- agenda (tasks only, by due date) ----------
  function agendaView() {
    const tks = calTasks().slice().sort((a, b) => a.dueDate - b.dueDate);
    if (!tks.length) return H.empty("Nothing scheduled", "Tasks with a due date show up here.");
    const groups = []; let cur = null;
    tks.forEach((t) => { const d0 = startOfDay(t.dueDate); if (!cur || cur.day !== d0) { cur = { day: d0, items: [] }; groups.push(cur); } cur.items.push(t); });
    const today0 = startOfToday();
    return '<div class="agenda">' + groups.map((g) => {
      const d = new Date(g.day), isToday = g.day === today0;
      const label = (isToday ? "Today · " : "") + d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
      return '<section class="ag-day"><h3 class="ag-h' + (isToday ? " today" : "") + '">' + H.esc(label) + '</h3><ul class="ag-list">' + g.items.map((t) => {
        const when = isAllDayTask(t) ? "All day" : H.fmtTime(t.dueDate);
        return '<li class="ag-row" data-task="' + H.esc(t.id) + '"><span class="ag-time">' + H.esc(when) + '</span><span class="ag-bar" style="background:' + priColor(t) + '"></span><span class="ag-main"><span class="ag-title' + (t.completed ? " done" : "") + '">' + H.esc(t.title || "Untitled") + (t.star ? ' <span class="chip">' + H.icon("star") + "</span>" : "") + (t.flagColorArgb != null ? ' <span class="chip">' + H.icon("bookmark") + "</span>" : "") + "</span></span></li>";
      }).join("") + "</ul></section>";
    }).join("") + "</div>";
  }

  // ---------- nav + wiring ----------
  async function reload() { try { await H.reloadDomain("calendar"); } catch (e) { H.toast(e.message); } H.render(); }
  function step(dir) {
    const v = H.ui.calView, a = new Date(H.ui.calAnchor);
    if (v === "month") { a.setDate(1); a.setMonth(a.getMonth() + dir); H.ui.calAnchor = a.getTime(); }
    else if (v === "week") H.ui.calAnchor = addDays(H.ui.calAnchor, 7 * dir);
    else if (v === "3day") H.ui.calAnchor = addDays(H.ui.calAnchor, 3 * dir);
    else if (v === "day") H.ui.calAnchor = addDays(H.ui.calAnchor, dir);
    else if (v === "year") { a.setFullYear(a.getFullYear() + dir); H.ui.calAnchor = a.getTime(); }
    else return; // agenda ignores anchor
    reload();
  }
  function onCalNav(cmd) {
    if (cmd === "new") { newEvent(null); return; }
    if (cmd === "prev") step(-1);
    else if (cmd === "next") step(1);
    else if (cmd === "jump-today" || cmd === "today") { H.ui.calAnchor = Date.now(); reload(); }
  }
  function newEvent(seed) { H.ui.draft = seed || {}; H.openInspector("calendar", "__new__"); }
  function setMode(m) { if (H.settings.calendarRememberLast) { H.ui.calView = m; H.saveUi(); } else H.ui.calView = m; reload(); }
  function onClick(e) {
    const tk = e.target.closest("[data-task]"); if (tk) { H.openInspector("tasks", tk.dataset.task); return; }
    const chip = e.target.closest(".echip,.wv-ev,.ag-row"); if (chip && chip.dataset.id) { H.openInspector("calendar", chip.dataset.id); return; }
    const more = e.target.closest(".mv-more"); if (more) { const cell = more.closest("[data-day]"); if (cell) { H.ui.calView = "day"; H.ui.calAnchor = Number(cell.dataset.day); H.saveUi(); reload(); } return; }
    const ymCell = e.target.closest(".ym-cell[data-day]"); if (ymCell) { H.ui.calView = "day"; H.ui.calAnchor = Number(ymCell.dataset.day); H.saveUi(); reload(); return; }
    const ymH = e.target.closest(".ym-h[data-month]"); if (ymH) { const d = new Date(H.ui.calAnchor); d.setMonth(Number(ymH.dataset.month), 1); H.ui.calView = "month"; H.ui.calAnchor = d.getTime(); H.saveUi(); reload(); return; }
    if (H.readOnly) return;
    const dayEl = e.target.closest(".mv-cell[data-day],.wv-col[data-day],.wv-allday-cell[data-day]"); if (!dayEl) return;
    const day = Number(dayEl.dataset.day);
    if (dayEl.classList.contains("wv-col")) { const rect = dayEl.getBoundingClientRect(); const hour = Math.max(0, Math.min(22, Math.floor((e.clientY - rect.top) / HOUR))); const s = new Date(day); s.setHours(hour, 0, 0, 0); newEvent({ start: s.getTime(), end: s.getTime() + 3600000, allDay: false }); }
    else if (dayEl.classList.contains("wv-allday-cell")) newEvent({ day, allDay: true });
    else newEvent({ day });
  }
  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    const head = H.el("mainHead");
    const sel = head.querySelector("#calMode"); if (sel) sel.onchange = () => setMode(sel.value);
    head.querySelectorAll("[data-cal]").forEach((b) => b.onclick = () => onCalNav(b.dataset.cal));
    H.el("mainBody").onclick = onClick;
    const sb = H.el("sidebar"); if (sb) sb.onclick = (e) => { const b = e.target.closest("[data-cal]"); if (b) { onCalNav(b.dataset.cal); if (window.innerWidth < 960) { H.ui.sidebar = false; H.render(); } } };
    if (H.ui.calView === "week" || H.ui.calView === "3day" || H.ui.calView === "day") { const wv = H.el("wvBody"); if (wv) wv.scrollTop = 7 * HOUR - 14; }
  }
  H.views.calendar = { title: "Calendar", sidebar, head, main, wire, window: calWindow };

  // ---------- event inspector (unchanged editor) ----------
  function draftEvent() {
    const s = H.ui.draft || {}, allDay = !!s.allDay; let start, end;
    if (s.start) { start = s.start; end = s.end || (s.start + 3600000); }
    else { const base = s.day != null ? s.day : H.ui.calAnchor, d = new Date(base); if (allDay) { d.setHours(0, 0, 0, 0); start = d.getTime(); end = start; } else { d.setHours(9, 0, 0, 0); start = d.getTime(); end = start + 3600000; } }
    const defCal = (H.state.calendars.find((c) => c.isDefault) || H.state.calendars[0] || {}).id || "";
    return { id: "", title: "", calendarId: defCal, location: "", notes: "", startMillis: start, endMillis: end, allDay, recurring: false };
  }
  function insHead(title) { return '<div class="ins-head"><h2>' + H.esc(title) + '</h2><button class="icon-btn" id="insClose" title="Close (Esc)">' + H.icon("close") + "</button></div>"; }
  H.inspectors.calendar = function (ins, id) {
    const e = id === "__new__" ? draftEvent() : H.state.calendar.find((x) => x.id === id);
    if (!e) { H.closeInspector(); return; }
    const ro = H.readOnly ? " disabled" : "", isNew = !e.id, recurring = !!e.recurring, timeRo = (H.readOnly || recurring) ? " disabled" : "";
    const calOpts = H.state.calendars.map((c) => '<option value="' + H.esc(c.id) + '"' + (c.id === e.calendarId ? " selected" : "") + ">" + H.esc(c.name) + "</option>").join("");
    const hid = e.allDay ? " hidden" : "";
    ins.innerHTML = insHead(isNew ? "New event" : "Event") + '<div class="ins-body">' +
      '<label class="fld"><span>Title</span><input id="iTitle" type="text" value="' + H.esc(e.title || "") + '"' + ro + "></label>" +
      (H.state.calendars.length ? '<label class="fld"><span>Calendar</span><select id="iCal"' + ro + ">" + calOpts + "</select></label>" : "") +
      (recurring ? '<p class="ins-note">' + H.icon("repeat") + " Repeats — text changes save to the whole series. Edit the times on your phone.</p>" : "") +
      '<label class="fld chk"><input id="iAllDay" type="checkbox"' + (e.allDay ? " checked" : "") + timeRo + "> All-day</label>" +
      '<div class="fld-row"><label class="fld"><span>Starts</span><input id="iSDate" type="date" value="' + H.esc(H.toDateInput(e.startMillis)) + '"' + timeRo + '></label><label class="fld time' + hid + '" id="iSTimeWrap"><span>at</span><input id="iSTime" type="time" value="' + H.esc(H.toTimeInput(e.startMillis)) + '"' + timeRo + "></label></div>" +
      '<div class="fld-row"><label class="fld"><span>Ends</span><input id="iEDate" type="date" value="' + H.esc(H.toDateInput(e.endMillis || e.startMillis)) + '"' + timeRo + '></label><label class="fld time' + hid + '" id="iETimeWrap"><span>at</span><input id="iETime" type="time" value="' + H.esc(H.toTimeInput(e.endMillis || e.startMillis)) + '"' + timeRo + "></label></div>" +
      '<label class="fld"><span>Location</span><input id="iLoc" type="text" value="' + H.esc(e.location || "") + '"' + ro + "></label>" +
      '<label class="fld grow"><span>Notes</span><textarea id="iNotes" rows="4"' + ro + ">" + H.esc(e.notes || "") + "</textarea></label>" +
      (H.readOnly ? "" : '<div class="ins-actions">' + (isNew ? "" : '<button class="btn danger" id="iDelete">Delete</button>') + '<span class="spacer"></span><button class="btn primary" id="iSave">Save</button></div>') + "</div>";
    H.el("insClose").onclick = H.closeInspector;
    if (H.readOnly) return;
    const allDayBox = H.el("iAllDay");
    if (allDayBox && !recurring) allDayBox.onchange = () => { const on = allDayBox.checked; H.el("iSTimeWrap").classList.toggle("hidden", on); H.el("iETimeWrap").classList.toggle("hidden", on); };
    H.el("iSave").onclick = async () => {
      const title = H.el("iTitle").value.trim(); if (!title) { H.toast("Title can't be empty"); return; }
      const isAllDay = allDayBox ? allDayBox.checked : e.allDay; let startMillis, endMillis;
      if (recurring) { startMillis = e.startMillis; endMillis = e.endMillis; }
      else if (isAllDay) { startMillis = H.fromDateTimeInput(H.el("iSDate").value, "00:00"); endMillis = H.fromDateTimeInput(H.el("iEDate").value || H.el("iSDate").value, "00:00"); }
      else { startMillis = H.fromDateTimeInput(H.el("iSDate").value, H.el("iSTime").value); endMillis = H.fromDateTimeInput(H.el("iEDate").value || H.el("iSDate").value, H.el("iETime").value); if (startMillis && endMillis && endMillis <= startMillis) endMillis = startMillis + 3600000; }
      const now = Date.now();
      try { const res = await H.api.mutate("calendar", "upsert", { id: e.id, title, calendarId: H.el("iCal") ? H.el("iCal").value : (e.calendarId || ""), location: H.el("iLoc").value, notes: H.el("iNotes").value, startMillis: startMillis || now, endMillis: endMillis || (startMillis || now) + 3600000, allDay: isAllDay }); H.ui.draft = null; await H.reloadDomain("calendar"); const newId = res && res.id ? res.id : e.id; H.ui.sel = newId ? { domain: "calendar", id: newId } : null; H.render(); H.toast("Saved"); } catch (err) { H.toast(err.message); }
    };
    const del = H.el("iDelete"); if (del) del.onclick = async () => { if (!confirm(recurring ? "Delete the whole repeating series?" : "Delete this event?")) return; try { await H.api.mutate("calendar", "delete", { id: e.id }); H.closeInspector(); await H.reloadDomain("calendar"); H.render(); } catch (err) { H.toast(err.message); } };
  };
})();
