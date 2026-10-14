"use strict";
/* Hexis Web — Calendar (Month / Week / Agenda). Ported into the H framework. Honours the phone's
 * weekStart, time-format and time-zone settings; Day/3-Day/Year parity lands in a later milestone. */
(function () {
  const H = window.H;
  const addDays = H.addDays, startOfDay = H.startOfDay, sameDay = H.sameDay, weekStart = H.weekStart;
  const startOfToday = () => startOfDay(Date.now());

  function monthGrid(anchorMs) {
    const a = new Date(anchorMs);
    const monthStart = new Date(a.getFullYear(), a.getMonth(), 1).getTime();
    const gridStart = weekStart(monthStart);
    return { gridStart, gridEnd: addDays(gridStart, 42), month: a.getMonth(), year: a.getFullYear() };
  }
  function calWindow() {
    if (H.ui.calView === "month") { const g = monthGrid(H.ui.calAnchor); return { from: g.gridStart, to: g.gridEnd }; }
    if (H.ui.calView === "week") { const s = weekStart(H.ui.calAnchor); return { from: s, to: addDays(s, 7) }; }
    const from = startOfDay(H.ui.calAnchor); return { from, to: addDays(from, 60) };
  }
  function calColor(e) {
    if (e && e.colorArgb != null) return H.argb(e.colorArgb);
    const c = H.state.calendars.find((x) => x.id === (e && e.calendarId));
    return (c && H.argb(c.colorArgb)) || "var(--accent)";
  }
  function dowLabels() { const base = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"], ws = H.weekStartDow(), out = []; for (let i = 0; i < 7; i++) out.push(base[(ws + i) % 7]); return out; }
  function eventsOnDay(dayMs) {
    const d0 = startOfDay(dayMs), d1 = addDays(d0, 1);
    return H.state.calendar.filter((e) => (e.startMillis < d1) && ((e.endMillis || e.startMillis) >= d0 || sameDay(e.startMillis, d0)))
      .sort((a, b) => (a.allDay === b.allDay ? a.startMillis - b.startMillis : (a.allDay ? -1 : 1)));
  }
  function eChip(e, withTime) {
    const t = e.allDay ? "" : (withTime ? H.fmtTime(e.startMillis) + " " : "");
    return '<button class="echip" data-id="' + H.esc(e.id) + '" style="--ec:' + calColor(e) + '" title="' + H.esc(e.title || "") + '">' +
      '<span class="echip-dot"></span><span class="echip-t">' + H.esc(t) + H.esc(e.title || "Untitled") + "</span>" +
      (e.recurring ? '<span class="echip-r">' + H.icon("repeat") + "</span>" : "") + "</button>";
  }

  function calTitle() {
    const a = new Date(H.ui.calAnchor);
    if (H.ui.calView === "month") return a.toLocaleDateString(undefined, H.dateOpts({ month: "long", year: "numeric" }));
    if (H.ui.calView === "week") {
      const s = new Date(weekStart(H.ui.calAnchor)), e = new Date(addDays(s.getTime(), 6));
      const sMon = s.toLocaleDateString(undefined, { month: "short" }), eMon = e.toLocaleDateString(undefined, { month: "short" });
      if (s.getMonth() === e.getMonth()) return sMon + " " + s.getDate() + " – " + e.getDate() + ", " + e.getFullYear();
      return sMon + " " + s.getDate() + " – " + eMon + " " + e.getDate() + ", " + e.getFullYear();
    }
    return "Agenda";
  }
  function head() {
    const views = [["month", "Month"], ["week", "Week"], ["agenda", "Agenda"]];
    const seg = views.map((v) => '<button class="seg' + (H.ui.calView === v[0] ? " on" : "") + '" data-calview="' + v[0] + '">' + v[1] + "</button>").join("");
    const nav = '<div class="cal-nav"><button class="icon-btn" data-cal="prev" title="Previous">' + H.icon("chevron_left") + "</button>" +
      '<button class="btn ghost" data-cal="jump-today">Today</button>' +
      '<button class="icon-btn" data-cal="next" title="Next">' + H.icon("chevron_right") + "</button></div>";
    return '<button class="icon-btn sb-toggle" id="sbToggle" title="Toggle sidebar" aria-label="Toggle sidebar">' + H.icon("menu") + "</button>" +
      '<h1 class="main-title">' + H.esc(calTitle()) + "</h1>" + nav +
      '<div class="segmented">' + seg + '</div><span class="head-spacer"></span>' +
      (H.readOnly ? "" : '<button class="btn primary" data-cal="new">' + H.icon("add") + "New event</button>");
  }
  function sidebar() {
    const d = new Date(H.ui.calAnchor);
    const big = '<div class="cal-today" data-cal="today">' +
      '<span class="cal-today-dow">' + d.toLocaleDateString(undefined, { weekday: "long" }) + "</span>" +
      '<span class="cal-today-day">' + d.getDate() + "</span>" +
      '<span class="cal-today-mon">' + d.toLocaleDateString(undefined, { month: "long", year: "numeric" }) + "</span></div>";
    const newBtn = H.readOnly ? "" : '<button class="btn primary block" data-cal="new">New event</button>';
    const legend = H.state.calendars.length
      ? '<h3 class="sb-sub">Calendars</h3><ul class="sb-list">' + H.state.calendars.map((c) =>
        '<li class="sb-item static"><span class="sb-ico"><span class="sb-dot" style="background:' + (H.argb(c.colorArgb) || "var(--accent)") + '"></span></span>' +
        '<span class="sb-label">' + H.esc(c.name) + "</span>" + (c.isDefault ? '<span class="sb-count">default</span>' : "") + "</li>").join("") + "</ul>"
      : "";
    return '<h2 class="sb-title">Calendar</h2>' + big + newBtn + legend;
  }
  function main() { return H.ui.calView === "month" ? monthView() : H.ui.calView === "week" ? weekView() : agendaView(); }

  function monthView() {
    const g = monthGrid(H.ui.calAnchor);
    const head = '<div class="mv-dow">' + dowLabels().map((d) => "<span>" + d + "</span>").join("") + "</div>";
    const today0 = startOfToday();
    let cells = "";
    for (let i = 0; i < 42; i++) {
      const day = addDays(g.gridStart, i), dd = new Date(day);
      const evs = eventsOnDay(day), shown = evs.slice(0, 4), extra = evs.length - shown.length;
      cells += '<div class="mv-cell' + (dd.getMonth() === g.month ? "" : " dim") + (day === today0 ? " today" : "") + '" data-day="' + day + '">' +
        '<div class="mv-daynum">' + dd.getDate() + '</div><div class="mv-evs">' + shown.map((e) => eChip(e, true)).join("") +
        (extra > 0 ? '<span class="mv-more">+' + extra + " more</span>" : "") + "</div></div>";
    }
    return head + '<div class="mv-grid">' + cells + "</div>";
  }
  function hourLabel(h) { if (H.use24() === true) return String(h).padStart(2, "0") + ":00"; if (h === 0) return "12 AM"; if (h === 12) return "12 PM"; return (h < 12 ? h + " AM" : (h - 12) + " PM"); }
  function hourLines(HOUR) { let s = ""; for (let h = 0; h < 24; h++) s += '<div class="wv-line" style="top:' + (h * HOUR) + 'px"></div>'; return s; }
  function nowIndicator(HOUR) { const n = new Date(), mins = n.getHours() * 60 + n.getMinutes(); return '<div class="wv-now" style="top:' + ((mins / 60) * HOUR).toFixed(1) + 'px"></div>'; }
  function layoutDay(events, d0, d1) {
    const items = events.map((e) => { const s = Math.max(e.startMillis, d0), en = Math.min(e.endMillis || (e.startMillis + 3600000), d1); return { e, top: (s - d0) / 60000, dur: Math.max(15, (en - s) / 60000) }; }).sort((a, b) => a.top - b.top);
    let i = 0;
    while (i < items.length) {
      let j = i, clusterEnd = items[i].top + items[i].dur; const cluster = [items[i]];
      while (j + 1 < items.length && items[j + 1].top < clusterEnd) { j++; cluster.push(items[j]); clusterEnd = Math.max(clusterEnd, items[j].top + items[j].dur); }
      const laneEnds = [];
      cluster.forEach((it) => { let lane = laneEnds.findIndex((end) => end <= it.top); if (lane === -1) { lane = laneEnds.length; laneEnds.push(0); } laneEnds[lane] = it.top + it.dur; it.lane = lane; });
      cluster.forEach((it) => { it.lanes = laneEnds.length; });
      i = j + 1;
    }
    return items;
  }
  function weekView() {
    const ws = weekStart(H.ui.calAnchor), days = []; for (let i = 0; i < 7; i++) days.push(addDays(ws, i));
    const today0 = startOfToday(), HOUR = 44;
    const heads = days.map((day) => { const d = new Date(day); return '<div class="wv-colh' + (day === today0 ? " today" : "") + '" data-day="' + day + '"><span class="wv-dow">' + d.toLocaleDateString(undefined, { weekday: "short" }) + '</span><span class="wv-dnum">' + d.getDate() + "</span></div>"; }).join("");
    const allDayCells = days.map((day) => '<div class="wv-allday-cell" data-day="' + day + '">' + eventsOnDay(day).filter((e) => e.allDay).map((e) => eChip(e, false)).join("") + "</div>").join("");
    let axis = ""; for (let h = 0; h < 24; h++) axis += '<div class="wv-hr" style="height:' + HOUR + 'px"><span>' + hourLabel(h) + "</span></div>";
    const cols = days.map((day) => {
      const d0 = startOfDay(day), d1 = addDays(d0, 1);
      const blocks = layoutDay(eventsOnDay(day).filter((e) => !e.allDay), d0, d1).map((p) => {
        const e = p.e, top = (p.top / 60) * HOUR, h = Math.max(16, (p.dur / 60) * HOUR);
        return '<button class="wv-ev" data-id="' + H.esc(e.id) + '" style="top:' + top + "px;height:" + h + "px;left:" + (p.lane * (100 / p.lanes)).toFixed(2) + "%;width:" + (100 / p.lanes).toFixed(2) + "%;--ec:" + calColor(e) + '"><span class="wv-ev-t">' + H.esc(e.title || "Untitled") + '</span><span class="wv-ev-m">' + H.esc(H.fmtTime(e.startMillis)) + "</span></button>";
      }).join("");
      return '<div class="wv-col" data-day="' + day + '" style="height:' + (24 * HOUR) + 'px">' + hourLines(HOUR) + (day === today0 ? nowIndicator(HOUR) : "") + blocks + "</div>";
    }).join("");
    return '<div class="wv"><div class="wv-head"><div class="wv-corner"></div>' + heads + '</div><div class="wv-alldays"><div class="wv-allday-label">all-day</div>' + allDayCells + '</div><div class="wv-body" id="wvBody"><div class="wv-axis">' + axis + "</div>" + cols + "</div></div>";
  }
  function agendaView() {
    const evs = H.state.calendar.filter((e) => (e.endMillis || e.startMillis) >= startOfToday()).sort((a, b) => a.startMillis - b.startMillis);
    if (!evs.length) return H.empty("Nothing scheduled", H.readOnly ? "" : "Create an event to see it here.");
    const groups = []; let cur = null;
    evs.forEach((e) => { const d0 = startOfDay(e.startMillis); if (!cur || cur.day !== d0) { cur = { day: d0, items: [] }; groups.push(cur); } cur.items.push(e); });
    return '<div class="agenda">' + groups.map((g) => {
      const d = new Date(g.day), isToday = g.day === startOfToday();
      const label = (isToday ? "Today · " : "") + d.toLocaleDateString(undefined, { weekday: "long", month: "short", day: "numeric" });
      return '<section class="ag-day"><h3 class="ag-h' + (isToday ? " today" : "") + '">' + H.esc(label) + '</h3><ul class="ag-list">' + g.items.map((e) => {
        const when = e.allDay ? "All day" : H.fmtTime(e.startMillis) + " – " + H.fmtTime(e.endMillis);
        return '<li class="ag-row" data-id="' + H.esc(e.id) + '"><span class="ag-time">' + H.esc(when) + '</span><span class="ag-bar" style="background:' + calColor(e) + '"></span><span class="ag-main"><span class="ag-title">' + H.esc(e.title || "Untitled") + (e.recurring ? ' <span class="chip">' + H.icon("repeat") + "</span>" : "") + "</span>" +
          (e.location || e.calendarName ? '<span class="ag-meta">' + H.esc([e.location, e.calendarName].filter(Boolean).join(" · ")) + "</span>" : "") + "</span></li>";
      }).join("") + "</ul></section>";
    }).join("") + "</div>";
  }

  async function reload() { try { await H.reloadDomain("calendar"); } catch (e) { H.toast(e.message); } H.render(); }
  function onCalNav(cmd) {
    if (cmd === "new") { newEvent(null); return; }
    if (cmd === "prev" || cmd === "next") {
      const dir = cmd === "next" ? 1 : -1;
      if (H.ui.calView === "month") { const a = new Date(H.ui.calAnchor); a.setDate(1); a.setMonth(a.getMonth() + dir); H.ui.calAnchor = a.getTime(); }
      else if (H.ui.calView === "week") H.ui.calAnchor = addDays(H.ui.calAnchor, 7 * dir);
      else H.ui.calAnchor = addDays(H.ui.calAnchor, 14 * dir);
      reload();
    } else if (cmd === "jump-today" || cmd === "today") { H.ui.calAnchor = Date.now(); reload(); }
  }
  function onClick(e) {
    const more = e.target.closest(".mv-more");
    if (more) { const cell = more.closest("[data-day]"); if (cell) { H.ui.calView = "agenda"; H.ui.calAnchor = Number(cell.dataset.day); H.saveUi(); reload(); } return; }
    const chip = e.target.closest(".echip,.wv-ev,.ag-row");
    if (chip && chip.dataset.id) { H.openInspector("calendar", chip.dataset.id); return; }
    if (H.readOnly) return;
    const dayEl = e.target.closest("[data-day]"); if (!dayEl) return;
    const day = Number(dayEl.dataset.day);
    if (dayEl.classList.contains("wv-col")) { const rect = dayEl.getBoundingClientRect(); const hour = Math.max(0, Math.min(22, Math.floor((e.clientY - rect.top) / 44))); const start = new Date(day); start.setHours(hour, 0, 0, 0); newEvent({ start: start.getTime(), end: start.getTime() + 3600000, allDay: false }); }
    else if (dayEl.classList.contains("wv-allday-cell")) newEvent({ day, allDay: true });
    else newEvent({ day });
  }
  function newEvent(seed) { H.ui.draft = seed || {}; H.openInspector("calendar", "__new__"); }
  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    const head = H.el("mainHead");
    head.querySelectorAll(".seg[data-calview]").forEach((b) => b.onclick = () => { H.ui.calView = b.dataset.calview; H.saveUi(); reload(); });
    head.querySelectorAll("[data-cal]").forEach((b) => b.onclick = () => onCalNav(b.dataset.cal));
    H.el("mainBody").onclick = onClick;
    const sb = H.el("sidebar"); if (sb) sb.onclick = (e) => { const b = e.target.closest("[data-cal]"); if (b) { onCalNav(b.dataset.cal); if (window.innerWidth < 960) { H.ui.sidebar = false; H.render(); } } };
    if (H.ui.calView === "week") { const wv = H.el("wvBody"); if (wv) wv.scrollTop = 7 * 44 - 14; }
  }
  H.views.calendar = { title: "Calendar", sidebar, head, main, wire, window: calWindow };

  // ---- event inspector ----
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
