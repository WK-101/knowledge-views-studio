"use strict";
/* Hexis Web — Habits (today check-in cards + 12-week heatmap). Ported into the H framework; uses the
 * phone's day-start rollover for "today". Schedule-aware due-today + full-year heatmap land later. */
(function () {
  const H = window.H;

  function sidebar() {
    const active = H.state.habits.filter((h) => !h.paused), done = active.filter((h) => h.doneToday).length;
    return '<h2 class="sb-title">Habits</h2><div class="cal-today"><span class="cal-today-dow">Today</span><span class="cal-today-day">' + done + "/" + active.length + '</span><span class="cal-today-mon">habits done</span></div>';
  }
  function head() { return H.headTitle("Habits", ""); }
  function habitCard(h) {
    const done = h.doneToday, multi = h.targetPerDay > 1;
    const pct = multi ? Math.min(100, Math.round((h.todayCount / h.targetPerDay) * 100)) : (done ? 100 : 0);
    const col = H.argb(h.colorArgb) || "var(--accent)";
    const sub = h.paused ? "Paused" : multi ? (h.todayCount + " / " + h.targetPerDay + (h.unit ? " " + h.unit : "")) : (done ? "Done today" : "Tap to check in");
    const tag = H.readOnly ? "div" : "button";
    return "<" + tag + ' class="hcard' + (done ? " done" : "") + (h.paused ? " paused" : "") + '"' + (H.readOnly ? "" : ' data-checkin="' + H.esc(h.id) + '"') + ' style="--hc:' + col + '"><span class="hcard-ring" style="--pct:' + pct + '"><span class="hcard-check">' + (done ? "✓" : (h.emoji ? H.esc(h.emoji) : "")) + '</span></span><span class="hcard-main"><span class="hcard-name">' + H.esc(h.name) + '</span><span class="hcard-sub">' + H.esc(sub) + "</span></span></" + tag + ">";
  }
  function heatRow(h, today, days, cell) {
    const col = H.argb(h.colorArgb) || "var(--accent)"; let cells = "";
    for (let i = days - 1; i >= 0; i--) {
      const day = today - i, c = cell[h.id + ":" + day]; let lvl = 0;
      if (c) { if (c.done) { const t = Math.max(1, h.targetPerDay); lvl = h.targetPerDay > 1 ? Math.min(4, 1 + Math.floor((c.count / t) * 3)) : 4; } else lvl = 1; }
      const title = H.epochDayToDate(day).toLocaleDateString(undefined, { month: "short", day: "numeric" }) + (c && c.done ? " ✓" : "");
      cells += '<span class="hcell l' + lvl + '" title="' + H.esc(title) + '"></span>';
    }
    return '<div class="heatrow" style="--hc:' + col + '"><span class="heatrow-name">' + (h.emoji ? H.esc(h.emoji) + " " : "") + H.esc(h.name) + '</span><div class="heatcells">' + cells + "</div></div>";
  }
  function main() {
    const habits = H.state.habits;
    if (!habits.length) return H.empty("No habits", "Create habits on your phone — they'll appear here to check in and review.");
    const active = habits.filter((h) => !h.paused), paused = habits.filter((h) => h.paused);
    const cards = '<div class="hcards">' + active.map(habitCard).join("") + "</div>";
    const today = H.todayEpochDay(), cell = {};
    H.state.habitHistory.forEach((c) => { cell[c.habitId + ":" + c.epochDay] = c; });
    const rows = active.map((h) => heatRow(h, today, 84, cell)).join("");
    const heat = active.length ? '<section class="panel"><h3 class="panel-h">Last 12 weeks</h3><div class="heatmap">' + rows + "</div></section>" : "";
    const pausedHtml = paused.length ? '<h3 class="group-h">Paused</h3><div class="hcards">' + paused.map(habitCard).join("") + "</div>" : "";
    return cards + heat + pausedHtml;
  }
  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    if (H.readOnly) return;
    H.el("mainBody").onclick = async (e) => {
      const c = e.target.closest("[data-checkin]"); if (!c) return;
      const h = H.state.habits.find((x) => x.id === c.dataset.checkin);
      if (h) { const target = Math.max(1, h.targetPerDay), next = (h.todayCount || 0) + 1; if (next > target) { h.todayCount = 0; h.doneToday = false; } else { h.todayCount = next; h.doneToday = true; } H.render(); }
      try { await H.api.mutate("habits", "checkin", { id: c.dataset.checkin }); await H.reloadDomain("habits"); H.render(); }
      catch (err) { await H.reloadDomain("habits").catch(() => {}); H.render(); H.toast(err.message); }
    };
  }
  H.views.habits = { title: "Habits", sidebar, head, main, wire };
})();
