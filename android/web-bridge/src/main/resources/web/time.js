"use strict";
/* Hexis Web — Time (running timer + today/week tiles + 14-day chart + by-activity + recent). Ported into
 * the H framework. Day/Week/Month/Year breakdown + donut drill-down arrive in a later milestone. */
(function () {
  const H = window.H;
  const addDays = H.addDays;
  const startOfToday = () => H.startOfDay(Date.now());

  function runningEntry() { return H.state.time.find((e) => e.running) || null; }
  function minutesByActivity(from, to) { const map = {}; H.state.time.forEach((e) => { if (e.startMillis >= from && e.startMillis < to) map[e.activityId] = (map[e.activityId] || 0) + (e.minutes || 0); }); return map; }
  function dailyMinutes(days) {
    const t0 = startOfToday(), out = [];
    for (let i = days - 1; i >= 0; i--) { const d0 = addDays(t0, -i), d1 = addDays(d0, 1); out.push({ day: d0, min: H.state.time.filter((e) => e.startMillis >= d0 && e.startMillis < d1).reduce((s, e) => s + (e.minutes || 0), 0) }); }
    return out;
  }
  function tile(label, value, sub) { return '<div class="tile"><span class="tile-label">' + H.esc(label) + '</span><span class="tile-value">' + H.esc(value) + '</span><span class="tile-sub">' + H.esc(sub) + "</span></div>"; }

  function sidebar() {
    const run = runningEntry(), todayByAct = minutesByActivity(startOfToday(), Date.now());
    const acts = H.state.activities.slice().sort((a, b) => (todayByAct[b.id] || 0) - (todayByAct[a.id] || 0));
    const list = acts.length ? acts.map((a) => {
      const col = H.argb(a.colorArgb) || "var(--accent)", running = run && run.activityId === a.id, mins = todayByAct[a.id] || 0;
      return '<li class="sb-item act' + (running ? " running" : "") + '"' + (H.readOnly ? "" : ' data-start="' + H.esc(a.id) + '"') + '><span class="sb-ico"><span class="sb-dot" style="background:' + col + '"></span></span><span class="sb-label">' + (a.emoji ? H.esc(a.emoji) + " " : "") + H.esc(a.name) + '</span><span class="sb-count">' + (running ? "●" : (mins ? H.fmtDuration(mins) : (H.readOnly ? "" : "▶"))) + "</span></li>";
    }).join("") : '<li class="sb-item static"><span class="sb-label" style="color:var(--muted)">No activities yet</span></li>';
    return '<h2 class="sb-title">Activities</h2>' + (H.readOnly ? "" : '<p class="sb-hint">Tap one to start a timer.</p>') + '<ul class="sb-list">' + list + "</ul>";
  }
  function head() { return H.headTitle("Time", ""); }
  function main() {
    const run = runningEntry(), t0 = startOfToday(), weekStartMs = H.weekStart(Date.now());
    const todayMin = H.state.time.filter((e) => e.startMillis >= t0).reduce((s, e) => s + (e.minutes || 0), 0);
    const weekMin = H.state.time.filter((e) => e.startMillis >= weekStartMs).reduce((s, e) => s + (e.minutes || 0), 0);
    const todaySessions = H.state.time.filter((e) => e.startMillis >= t0).length;
    const runBanner = run
      ? '<div class="run-banner"><span class="run-pulse"></span><div class="run-info"><span class="run-act">' + H.esc(run.activityName || "Tracking") + '</span><span class="run-elapsed" id="runElapsed" data-start="' + run.startMillis + '">' + H.esc(H.fmtDuration(run.minutes)) + "</span></div>" + (H.readOnly ? "" : '<button class="btn danger solid" data-stop="' + H.esc(run.id) + '">Stop</button>') + "</div>"
      : (H.readOnly || !H.state.activities.length ? "" : '<div class="run-banner idle"><span class="run-info"><span class="run-act">Nothing tracking</span><span class="run-elapsed muted">Start an activity from the sidebar</span></span></div>');
    const tiles = '<div class="tiles">' + tile("Today", H.fmtDuration(todayMin), "tracked") + tile("This week", H.fmtDuration(weekMin), "tracked") + tile("Sessions", String(todaySessions), "today") + "</div>";
    const series = dailyMinutes(14), max = Math.max(60, ...series.map((d) => d.min));
    const bars = series.map((d) => { const h = Math.round((d.min / max) * 100); return '<div class="bar-col" title="' + H.esc(H.fmtDate(d.day) + " · " + H.fmtDuration(d.min)) + '"><div class="bar-track"><div class="bar-fill' + (d.day === t0 ? " today" : "") + '" style="height:' + h + '%"></div></div><span class="bar-lbl">' + new Date(d.day).getDate() + "</span></div>"; }).join("");
    const chart = '<section class="panel"><h3 class="panel-h">Last 14 days</h3><div class="barchart">' + bars + "</div></section>";
    const byAct = minutesByActivity(weekStartMs, Date.now());
    const actRows = H.state.activities.map((a) => ({ a, min: byAct[a.id] || 0 })).filter((x) => x.min > 0).sort((x, y) => y.min - x.min);
    const maxAct = Math.max(1, ...actRows.map((x) => x.min));
    const byActHtml = actRows.length ? '<section class="panel"><h3 class="panel-h">This week by activity</h3><div class="actbars">' + actRows.map((x) => {
      const col = H.argb(x.a.colorArgb) || "var(--accent)";
      return '<div class="actbar"><span class="actbar-name">' + (x.a.emoji ? H.esc(x.a.emoji) + " " : "") + H.esc(x.a.name) + '</span><div class="actbar-track"><div class="actbar-fill" style="width:' + Math.round((x.min / maxAct) * 100) + "%;background:" + col + '"></div></div><span class="actbar-val">' + H.esc(H.fmtDuration(x.min)) + "</span></div>";
    }).join("") + "</div></section>" : "";
    const recent = H.state.time.slice(0, 40);
    const recentHtml = recent.length ? '<section class="panel"><h3 class="panel-h">Recent</h3><ul class="elist">' + recent.map((e) => {
      const col = H.argb((H.state.activities.find((a) => a.id === e.activityId) || {}).colorArgb) || "var(--accent)";
      return '<li class="erow"><span class="cdot" style="background:' + col + '"></span><span class="einfo"><span class="etitle">' + H.esc(e.activityName || "Activity") + (e.running ? ' <span class="chip live">● live</span>' : "") + '</span><span class="emeta">' + H.esc(H.fmtDateTime(e.startMillis)) + (e.note ? " · " + H.esc(e.note) : "") + '</span></span><span class="dur">' + H.esc(H.fmtDuration(e.minutes)) + "</span></li>";
    }).join("") + "</ul></section>" : "";
    if (!H.state.time.length && !H.state.activities.length) return runBanner + H.empty("No time tracked yet", "Create activities on your phone, then start a timer here.");
    return runBanner + tiles + chart + byActHtml + recentHtml;
  }

  // ---- live clock ----
  let clockTimer = null;
  function fmtClock(ms) { const s = Math.max(0, Math.floor(ms / 1000)), p = (n) => String(n).padStart(2, "0"), hh = Math.floor(s / 3600), mm = Math.floor((s % 3600) / 60), ss = s % 60; return hh > 0 ? hh + ":" + p(mm) + ":" + p(ss) : mm + ":" + p(ss); }
  function updateRunClock() { const elx = H.el("runElapsed"); if (!elx) { stopClock(); return; } const start = Number(elx.dataset.start || 0); if (start) elx.textContent = fmtClock(Date.now() - start); }
  function startClock() { stopClock(); clockTimer = setInterval(updateRunClock, 1000); }
  function stopClock() { if (clockTimer) { clearInterval(clockTimer); clockTimer = null; } }
  H.stopClock = stopClock;

  async function startTimer(activityId) { try { await H.api.mutate("time", "start", { activityId }); await H.reloadDomain("time"); H.render(); H.toast("Timer started"); } catch (err) { H.toast(err.message); } }
  async function stopTimer(id) { try { await H.api.mutate("time", "stop", { id: id || "" }); await H.reloadDomain("time"); H.render(); H.toast("Stopped"); } catch (err) { H.toast(err.message); } }

  function wire() {
    const sbt = H.el("sbToggle"); if (sbt) sbt.onclick = H.toggleSidebar;
    if (!H.readOnly) { H.el("mainBody").onclick = (e) => { const stopBtn = e.target.closest("[data-stop]"); if (stopBtn) stopTimer(stopBtn.dataset.stop); }; const sb = H.el("sidebar"); if (sb) sb.onclick = (e) => { const b = e.target.closest("[data-start]"); if (b) startTimer(b.dataset.start); }; }
    const run = runningEntry(); if (run) { const elx = H.el("runElapsed"); if (elx) elx.textContent = fmtClock(Date.now() - run.startMillis); startClock(); }
  }
  H.views.time = { title: "Time", sidebar, head, main, wire };
})();
