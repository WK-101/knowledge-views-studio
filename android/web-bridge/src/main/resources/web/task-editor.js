"use strict";
/*
 * Hexis Web — the task editor (right inspector), mirroring ui/screens/TaskDetailScreen.kt.
 * Staged edit: the main fields live in a local draft and persist on Save (one authoritative `upsert`);
 * side tables (subtasks, checklist, reminders, dependencies) save immediately like the phone.
 */
(function () {
  const H = window.H;
  const ed = { id: null, draft: null, checklist: [], reminders: [], deps: [], more: false };

  function newDraft() {
    const seed = H.newTaskSeed ? H.newTaskSeed() : {};
    return {
      id: "", title: "", note: "", listId: seed.listId || "inbox", folderId: null,
      dueDate: null, startDate: null, deadlineDate: null, isAllDay: false,
      importance: 3, urgency: 3, energy: null, estimateMin: null, durationMin: null, leadTimeMin: null,
      star: false, flagId: null, pinned: false, someday: false, isGoal: false, isProject: false,
      progressPct: null, completeInOrder: false, rrule: null, recurrenceMode: null, waitingForWho: "",
      reviewEveryDays: null, defaultActivityId: null, colorArgb: null,
      winFlag: false, mood: null, outcomeNote: null, learnedNote: null, praiseQuote: null, whyText: "", rewardText: "",
      tagIds: [], contextIds: [],
    };
  }
  function draftFrom(t) {
    return Object.assign(newDraft(), {
      id: t.id, title: t.title || "", note: t.note || "", listId: t.listId || "inbox", folderId: t.folderId || null,
      dueDate: t.dueDate, startDate: t.startDate, deadlineDate: t.deadlineDate, isAllDay: !!t.isAllDay,
      importance: t.importance == null ? 3 : t.importance, urgency: t.urgency == null ? 3 : t.urgency,
      energy: t.energy, estimateMin: t.estimateMin, durationMin: t.durationMin, leadTimeMin: t.leadTimeMin,
      star: !!t.star, flagId: t.flagId || null, pinned: !!t.pinned, someday: !!t.someday,
      isGoal: !!t.isGoal, isProject: !!t.isProject, progressPct: t.progressPct, completeInOrder: !!t.completeInOrder,
      rrule: t.rrule || null, recurrenceMode: t.recurrenceMode, waitingForWho: t.waitingForWho || "",
      reviewEveryDays: t.reviewEveryDays, defaultActivityId: t.defaultActivityId, colorArgb: t.colorArgb,
      winFlag: !!t.winFlag, mood: t.mood, outcomeNote: t.outcomeNote, learnedNote: t.learnedNote, praiseQuote: t.praiseQuote,
      whyText: t.whyText || "", rewardText: t.rewardText || "", tagIds: (t.tagIds || []).slice(), contextIds: (t.contextIds || []).slice(),
    });
  }
  async function loadSub(id) {
    try {
      const [cl, rm] = await Promise.all([
        H.api.query("tasks", "checklist", { paramsJson: JSON.stringify({ id }) }).catch(() => []),
        H.api.query("tasks", "reminders", { paramsJson: JSON.stringify({ id }) }).catch(() => []),
      ]);
      ed.checklist = cl; ed.reminders = rm;
      ed.deps = (H.state.dependencies || []).filter((d) => d.taskId === id);
      if (ed.id === id) H.renderInspector();
    } catch (_e) { /* */ }
  }

  // ---- rrule (FREQ=…;INT=…;DAYS=… ; COUNT|UNTIL) -------------------------
  function parseRrule(s) {
    if (!s) return { freq: "NONE", interval: 1, byDays: [], ends: "never", count: null, until: null };
    const m = {}; s.split(";").forEach((p) => { const i = p.indexOf("="); if (i > 0) m[p.slice(0, i)] = p.slice(i + 1); });
    return { freq: m.FREQ || "NONE", interval: Number(m.INT) || 1, byDays: (m.DAYS || "").split(",").filter(Boolean).map(Number), ends: m.COUNT ? "count" : m.UNTIL ? "until" : "never", count: Number(m.COUNT) || null, until: Number(m.UNTIL) || null };
  }
  function buildRrule(r) {
    if (!r.freq || r.freq === "NONE") return null;
    let s = "FREQ=" + r.freq + ";INT=" + (r.interval || 1);
    if (r.freq === "WEEKLY" && r.byDays.length) s += ";DAYS=" + r.byDays.slice().sort((a, b) => a - b).join(",");
    if (r.ends === "count" && r.count) s += ";COUNT=" + r.count; else if (r.ends === "until" && r.until) s += ";UNTIL=" + r.until;
    return s;
  }

  // ---- render ------------------------------------------------------------
  function tier(fieldId) { const t = H.settings.editorFieldTiers || {}; return t[fieldId] != null ? t[fieldId] : null; }
  function showField(fieldId, defaultTier) { const ov = tier(fieldId); const tr = ov != null ? ov : defaultTier; if (tr === 2) return false; return tr === 0 || ed.more; }

  function sectionDetails(title, bodyHtml, open) {
    return '<details class="ins-section"' + (open ? " open" : "") + '><summary>' + H.icon("expand_more") + H.esc(title) + '</summary><div class="ins-section-body">' + bodyHtml + "</div></details>";
  }
  function dateTimeRow(label, dateVal, timeVal, idPrefix, allDay) {
    return '<div class="fld-row"><label class="fld"><span>' + label + '</span><input id="' + idPrefix + 'Date" type="date" value="' + H.esc(dateVal) + '"></label>' +
      (allDay ? "" : '<label class="fld" style="flex:0 0 118px"><span>at</span><input id="' + idPrefix + 'Time" type="time" value="' + H.esc(timeVal) + '"></label>') + "</div>";
  }
  function renderEditor() {
    const d = ed.draft, ro = H.readOnly ? " disabled" : "", isNew = !d.id;
    const t = isNew ? null : H.state.tasks.find((x) => x.id === d.id);
    const done = t && (t.completed || t.abandoned);
    const lvl = H.T.priLevel(d);
    const pcfill = { NONE: "0%", LOW: "10%", MED: "14%", HIGH: "18%" }[lvl];
    let h = '<div class="ins-head"><h2>' + (isNew ? "New task" : "Task") + "</h2><div class=\"ins-head-actions\">" +
      (H.readOnly || isNew ? "" : '<button class="icon-btn" id="edPin" title="Pin">' + H.icon(d.pinned ? "push_pin" : "push_pin") + "</button>") +
      '<button class="icon-btn" id="edStar" title="Star">' + H.icon(d.star ? "star" : "star_border") + "</button>" +
      '<button class="icon-btn" id="insClose" title="Close (Esc)">' + H.icon("close") + "</button></div></div>";
    h += '<div class="ins-body">';
    // title + priority checkbox
    h += '<div class="ed-titlerow"><button class="pcheck' + (done ? " done" : "") + (H.readOnly ? " static" : "") + '" id="edComplete" style="--pc:' + H.T.priColor(lvl) + ";--pcfill:" + pcfill + '">' + H.icon("check") + "</button>" +
      '<input id="edTitle" class="ed-title" type="text" placeholder="Task title" value="' + H.esc(d.title) + '"' + ro + "></div>";
    // notes
    h += '<label class="fld"><span>Notes</span><textarea id="edNote" rows="3" placeholder="Notes — Markdown supported"' + ro + ">" + H.esc(d.note) + "</textarea></label>";
    // SCHEDULE (core)
    h += '<label class="fld chk"><input id="edAllDay" type="checkbox"' + (d.isAllDay ? " checked" : "") + ro + "> All-day</label>";
    h += dateTimeRow("Due", H.toDateInput(d.dueDate), H.toTimeInput(d.dueDate) || "09:00", "edDue", d.isAllDay);
    h += dateTimeRow("Starts", H.toDateInput(d.startDate), H.toTimeInput(d.startDate) || "09:00", "edStart", true);
    h += dateTimeRow("Deadline", H.toDateInput(d.deadlineDate), "", "edDead", true);
    // PRIORITY (core) — 4-level + advanced imp/urg
    const levels = [["HIGH", "High", "var(--p-high)"], ["MED", "Medium", "var(--p-med)"], ["LOW", "Low", "var(--p-low)"], ["NONE", "None", "var(--p-none)"]];
    h += '<div class="fld"><span>Priority</span><div class="pri-pick">' + levels.map((l) => '<button type="button" class="pri-opt' + (lvl === l[0] ? " on" : "") + '" data-lvl="' + l[0] + '" style="--pc:' + l[2] + '"' + ro + ">" + l[1] + "</button>").join("") + "</div>" +
      '<div class="fld-row" style="margin-top:8px"><label class="fld"><span>Importance</span>' + stepper("edImp", d.importance) + "</label><label class=\"fld\"><span>Urgency</span>" + stepper("edUrg", d.urgency) + "</label></div></div>";
    // LIST + FLAG (core + more)
    h += '<div class="fld-row"><label class="fld"><span>List</span><select id="edList"' + ro + ">" + H.state.lists.map((l) => '<option value="' + H.esc(l.id) + '"' + (d.listId === l.id ? " selected" : "") + ">" + H.esc(l.name) + "</option>").join("") + "</select></label>" +
      '<label class="fld"><span>Flag</span><select id="edFlag"' + ro + '><option value="">None</option>' + H.state.flags.map((f) => '<option value="' + H.esc(f.id) + '"' + (d.flagId === f.id ? " selected" : "") + ">" + H.esc(f.name) + "</option>").join("") + "</select></label></div>";
    // REPEAT
    const r = parseRrule(d.rrule);
    const freqs = [["NONE", "Does not repeat"], ["DAILY", "Daily"], ["WEEKDAYS", "Every weekday"], ["WEEKLY", "Weekly"], ["MONTHLY", "Monthly"], ["YEARLY", "Yearly"]];
    let repeatBody = '<label class="fld"><span>Repeat</span><select id="edFreq"' + ro + ">" + freqs.map((f) => '<option value="' + f[0] + '"' + (r.freq === f[0] ? " selected" : "") + ">" + f[1] + "</option>").join("") + "</select></label>";
    if (r.freq !== "NONE") {
      repeatBody += '<label class="fld"><span>Every</span><input id="edInt" type="number" min="1" max="99" value="' + (r.interval || 1) + '"' + ro + "></label>";
      if (r.freq === "WEEKLY") { const dn = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]; repeatBody += '<div class="fld"><span>On</span><div class="wd-pick">' + dn.map((n, i) => '<button type="button" class="wd' + (r.byDays.includes(i + 1) ? " on" : "") + '" data-wd="' + (i + 1) + '"' + ro + ">" + n + "</button>").join("") + "</div></div>"; }
    }
    h += sectionDetails("Recurrence", repeatBody, r.freq !== "NONE");
    // TAGS & CONTEXTS
    if (showField("tags", 1)) {
      const tagChips = H.state.tags.map((g) => '<button type="button" class="tc-chip' + (d.tagIds.includes(g.id) ? " on" : "") + '" data-tag="' + H.esc(g.id) + '"' + ro + ">#" + H.esc(g.name) + "</button>").join("");
      const ctxChips = H.state.contexts.map((c) => '<button type="button" class="tc-chip' + (d.contextIds.includes(c.id) ? " on" : "") + '" data-ctx="' + H.esc(c.id) + '"' + ro + ">@" + H.esc(c.name) + "</button>").join("");
      h += sectionDetails("Tags & contexts", '<div class="tc-group">' + (tagChips || '<span class="muted-sm">No tags</span>') + "</div><div class=\"tc-group\">" + (ctxChips || '<span class="muted-sm">No contexts</span>') + "</div>", d.tagIds.length || d.contextIds.length);
    }
    // ENERGY
    if (showField("energy", 1)) { const en = [["", "Any"], ["1", "Low"], ["2", "Medium"], ["3", "High"]]; h += '<label class="fld"><span>Energy</span><select id="edEnergy"' + ro + ">" + en.map((e) => '<option value="' + e[0] + '"' + (String(d.energy || "") === e[0] ? " selected" : "") + ">" + e[1] + "</option>").join("") + "</select></label>"; }
    // SUBTASKS
    if (!isNew && showField("subtasks", 1)) {
      const kids = H.state.tasks.filter((x) => x.parentId === d.id && !x.trashed).sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
      const items = kids.map((k) => '<div class="ed-sub" data-subid="' + H.esc(k.id) + '"><button class="pcheck sm' + (k.completed ? " done" : "") + '" data-subtoggle="' + H.esc(k.id) + '" style="--pc:' + H.T.priColor(H.T.priLevel(k)) + '">' + H.icon("check") + '</button><span class="ed-sub-title" data-subopen="' + H.esc(k.id) + '">' + H.esc(k.title) + "</span></div>").join("");
      h += sectionDetails("Subtasks", items + (H.readOnly ? "" : '<form class="ed-add" id="edSubAdd"><input placeholder="Add subtask…"><button class="btn ghost">Add</button></form>'), kids.length > 0);
    }
    // CHECKLIST
    if (showField("checklist", 0)) {
      const items = ed.checklist.map((c) => '<div class="ed-sub"><label class="fld chk"><input type="checkbox" data-clset="' + H.esc(c.id) + '"' + (c.checked ? " checked" : "") + (H.readOnly ? " disabled" : "") + "> " + H.esc(c.text) + "</label>" + (H.readOnly ? "" : '<button class="icon-btn sm" data-cldel="' + H.esc(c.id) + '">' + H.icon("close") + "</button>") + "</div>").join("");
      h += sectionDetails("Checklist", items + (H.readOnly || isNew ? "" : '<form class="ed-add" id="edClAdd"><input placeholder="Add checklist item…"><button class="btn ghost">Add</button></form>'), ed.checklist.length > 0);
    }
    // REMINDERS
    if (!isNew && showField("reminders", 1)) {
      const items = ed.reminders.map((rm) => '<div class="ed-sub"><span class="ed-sub-title">' + H.icon("notifications") + H.esc(reminderLabel(rm)) + "</span>" + (H.readOnly ? "" : '<button class="icon-btn sm" data-rmdel="' + H.esc(rm.id) + '">' + H.icon("close") + "</button>") + "</div>").join("");
      let addR = "";
      if (!H.readOnly) {
        const offs = [["0", "When due"], ["10", "10 min before"], ["30", "30 min before"], ["60", "1 hour before"], ["1440", "1 day before"]];
        addR = '<form class="ed-add" id="edRmAdd"><select>' + offs.map((o) => '<option value="' + o[0] + '">' + o[1] + "</option>").join("") + '</select><button class="btn ghost">Add</button></form>';
      }
      h += sectionDetails("Reminders", items + addR, ed.reminders.length > 0);
    }
    // BLOCKED / WAITING
    if (!isNew && showField("blocked", 1)) {
      const depItems = ed.deps.map((dp) => { const bt = H.state.tasks.find((x) => x.id === dp.dependsOnTaskId); return '<div class="ed-sub"><span class="ed-sub-title">' + H.icon("block") + H.esc(bt ? bt.title : dp.dependsOnTaskId) + "</span>" + (H.readOnly ? "" : '<button class="icon-btn sm" data-depdel="' + H.esc(dp.dependsOnTaskId) + '">' + H.icon("close") + "</button>") + "</div>"; }).join("");
      const others = H.state.tasks.filter((x) => x.id !== d.id && !x.trashed && !x.completed && !ed.deps.some((dp) => dp.dependsOnTaskId === x.id));
      const addDep = (H.readOnly || !others.length) ? "" : '<form class="ed-add" id="edDepAdd"><select><option value="">Add a blocker…</option>' + others.slice(0, 200).map((o) => '<option value="' + H.esc(o.id) + '">' + H.esc(o.title) + "</option>").join("") + '</select><button class="btn ghost">Add</button></form>';
      const waitRow = '<label class="fld"><span>Waiting on (people)</span><input id="edWaiting" type="text" value="' + H.esc(d.waitingForWho) + '" placeholder="Who you\'re waiting on"' + ro + "></label>";
      h += sectionDetails("Blocked & waiting", depItems + addDep + waitRow, ed.deps.length > 0 || d.waitingForWho);
    }
    // ADVANCED
    if (showField("advanced", 1)) {
      let adv = '<label class="fld chk"><input id="edGoal" type="checkbox"' + (d.isGoal ? " checked" : "") + ro + "> Mark as goal</label>" +
        '<label class="fld chk"><input id="edProject" type="checkbox"' + (d.isProject ? " checked" : "") + ro + "> Mark as project</label>" +
        '<label class="fld chk"><input id="edSomeday" type="checkbox"' + (d.someday ? " checked" : "") + ro + "> Someday / Maybe</label>" +
        '<label class="fld chk"><input id="edInOrder" type="checkbox"' + (d.completeInOrder ? " checked" : "") + ro + "> Complete subtasks in order</label>" +
        '<div class="fld-row"><label class="fld"><span>Estimate (min)</span><input id="edEst" type="number" min="0" value="' + (d.estimateMin == null ? "" : d.estimateMin) + '"' + ro + "></label>" +
        '<label class="fld"><span>Review every (days)</span><input id="edReview" type="number" min="0" value="' + (d.reviewEveryDays == null ? "" : d.reviewEveryDays) + '"' + ro + "></label></div>";
      h += sectionDetails("Estimate, goal, project, review", adv, false);
    }
    // REFLECTION (completed)
    if (!isNew && done && showField("reflection", 1)) {
      const moods = [1, 2, 3, 4, 5].map((m) => '<button type="button" class="mood' + (d.mood === m ? " on" : "") + '" data-mood="' + m + '"' + ro + ">" + ["😞", "😕", "😐", "🙂", "😄"][m - 1] + "</button>").join("");
      const refl = '<label class="fld chk"><input id="edWin" type="checkbox"' + (d.winFlag ? " checked" : "") + ro + "> Mark this a win</label>" +
        '<div class="fld"><span>Mood</span><div class="mood-pick">' + moods + "</div></div>" +
        '<label class="fld"><span>Outcome / impact</span><textarea id="edOutcome" rows="2"' + ro + ">" + H.esc(d.outcomeNote || "") + "</textarea></label>" +
        '<label class="fld"><span>What I learned</span><textarea id="edLearned" rows="2"' + ro + ">" + H.esc(d.learnedNote || "") + "</textarea></label>";
      h += sectionDetails("Reflection", refl, true);
    }
    // more toggle
    h += '<button type="button" class="btn ghost block" id="edMore">' + (ed.more ? "Fewer fields" : "More fields") + "</button>";
    // actions
    if (!H.readOnly) {
      h += '<div class="ins-actions">' + (isNew ? "" : '<button class="btn danger" id="edDelete">' + H.icon("delete") + "Delete</button>") +
        (isNew ? "" : '<button class="btn ghost" id="edAbandon">' + (t && t.abandoned ? "Undo" : "Won't do") + "</button>") +
        '<span class="spacer"></span><button class="btn primary" id="edSave">Save</button></div>';
    }
    h += "</div>";
    return h;
  }
  function stepper(id, val) { return '<div class="stepper" data-stepper="' + id + '"><button type="button" data-step="-1">−</button><span id="' + id + '">' + val + '</span><button type="button" data-step="1">+</button></div>'; }
  function reminderLabel(rm) {
    if (rm.type === "absolute") return rm.atTime ? H.fmtDateTime(rm.atTime) : "At a time";
    if (rm.type === "location") return "At " + (rm.placeName || "a place");
    const off = rm.offsetMin || 0; const base = rm.type === "relativeToStart" ? "start" : rm.type === "relativeToDeadline" ? "deadline" : "due";
    if (off === 0) return "When " + base; if (off % 1440 === 0) return (off / 1440) + " day(s) before " + base; if (off % 60 === 0) return (off / 60) + " hour(s) before " + base; return off + " min before " + base;
  }

  // ---- wiring ------------------------------------------------------------
  function wireEditor(ins) {
    const g = (id) => ins.querySelector("#" + id);
    const close = g("insClose"); if (close) close.onclick = H.closeInspector;
    const d = ed.draft;
    // header star/pin (apply immediately via dedicated ops so they don't depend on Save)
    const st = g("edStar"); if (st) st.onclick = async () => { d.star = !d.star; if (d.id) { try { await H.api.mutate("tasks", "star", { id: d.id, star: d.star }); await H.reloadDomain("tasks"); } catch (e) { H.toast(e.message); } } H.renderInspector(); };
    const pin = g("edPin"); if (pin) pin.onclick = () => { d.pinned = !d.pinned; H.renderInspector(); };
    if (H.readOnly) return;
    // bind simple inputs
    bind(g("edTitle"), "input", (e) => d.title = e.target.value);
    bind(g("edNote"), "input", (e) => d.note = e.target.value);
    bind(g("edWaiting"), "input", (e) => d.waitingForWho = e.target.value);
    bind(g("edList"), "change", (e) => d.listId = e.target.value);
    bind(g("edFlag"), "change", (e) => d.flagId = e.target.value || null);
    bind(g("edEnergy"), "change", (e) => d.energy = e.target.value ? Number(e.target.value) : null);
    bind(g("edAllDay"), "change", (e) => { d.isAllDay = e.target.checked; H.renderInspector(); });
    bind(g("edGoal"), "change", (e) => d.isGoal = e.target.checked);
    bind(g("edProject"), "change", (e) => d.isProject = e.target.checked);
    bind(g("edSomeday"), "change", (e) => d.someday = e.target.checked);
    bind(g("edInOrder"), "change", (e) => d.completeInOrder = e.target.checked);
    bind(g("edWin"), "change", (e) => d.winFlag = e.target.checked);
    bind(g("edOutcome"), "input", (e) => d.outcomeNote = e.target.value);
    bind(g("edLearned"), "input", (e) => d.learnedNote = e.target.value);
    bind(g("edEst"), "input", (e) => d.estimateMin = e.target.value ? Number(e.target.value) : null);
    bind(g("edReview"), "input", (e) => d.reviewEveryDays = e.target.value ? Number(e.target.value) : null);
    // dates
    const syncDue = () => { const dt = g("edDueDate").value; d.dueDate = dt ? (d.isAllDay ? H.fromDateInput(dt) : H.fromDateTimeInput(dt, g("edDueTime") ? g("edDueTime").value : "09:00")) : null; };
    bindAll([g("edDueDate"), g("edDueTime")], "change", syncDue);
    bindAll([g("edStartDate"), g("edStartTime")], "change", () => { const dt = g("edStartDate").value; d.startDate = dt ? H.fromDateTimeInput(dt, g("edStartTime") ? g("edStartTime").value : "09:00") : null; });
    bind(g("edDeadDate"), "change", (e) => { d.deadlineDate = e.target.value ? H.fromDateInput(e.target.value) : null; });
    // priority
    ins.querySelectorAll(".pri-opt").forEach((b) => b.onclick = () => { const m = { HIGH: [5, 5], MED: [4, 4], LOW: [3, 3], NONE: [2, 2] }[b.dataset.lvl]; d.importance = m[0]; d.urgency = m[1]; H.renderInspector(); });
    ins.querySelectorAll(".stepper").forEach((sp) => sp.querySelectorAll("[data-step]").forEach((btn) => btn.onclick = () => {
      const key = sp.dataset.stepper === "edImp" ? "importance" : "urgency"; d[key] = Math.min(5, Math.max(1, d[key] + Number(btn.dataset.step))); H.renderInspector();
    }));
    // recurrence
    bind(g("edFreq"), "change", (e) => { const r = parseRrule(d.rrule); r.freq = e.target.value; if (r.freq === "NONE") d.rrule = null; else d.rrule = buildRrule(r); H.renderInspector(); });
    bind(g("edInt"), "change", (e) => { const r = parseRrule(d.rrule); r.interval = Math.max(1, Number(e.target.value) || 1); d.rrule = buildRrule(r); });
    ins.querySelectorAll(".wd").forEach((b) => b.onclick = () => { const r = parseRrule(d.rrule); const wd = Number(b.dataset.wd); const i = r.byDays.indexOf(wd); if (i < 0) r.byDays.push(wd); else r.byDays.splice(i, 1); d.rrule = buildRrule(r); H.renderInspector(); });
    // tags / contexts (local)
    ins.querySelectorAll("[data-tag]").forEach((b) => b.onclick = () => { const id = b.dataset.tag; const i = d.tagIds.indexOf(id); if (i < 0) d.tagIds.push(id); else d.tagIds.splice(i, 1); b.classList.toggle("on"); });
    ins.querySelectorAll("[data-ctx]").forEach((b) => b.onclick = () => { const id = b.dataset.ctx; const i = d.contextIds.indexOf(id); if (i < 0) d.contextIds.push(id); else d.contextIds.splice(i, 1); b.classList.toggle("on"); });
    // mood
    ins.querySelectorAll(".mood").forEach((b) => b.onclick = () => { d.mood = Number(b.dataset.mood); H.renderInspector(); });
    // complete (toggle)
    const comp = g("edComplete"); if (comp && d.id) comp.onclick = async () => { const t = H.state.tasks.find((x) => x.id === d.id); const want = !(t && t.completed); try { await H.api.mutate("tasks", "complete", { id: d.id, completed: want }); await H.reloadDomain("tasks"); H.renderInspector(); } catch (e) { H.toast(e.message); } };
    // more
    const more = g("edMore"); if (more) more.onclick = () => { ed.more = !ed.more; H.renderInspector(); };
    // side tables
    wireSideTables(ins);
    // save / delete / abandon
    const save = g("edSave"); if (save) save.onclick = doSave;
    const del = g("edDelete"); if (del) del.onclick = async () => { if (!confirm("Move this task to Trash?")) return; try { await H.api.mutate("tasks", "delete", { id: d.id }); H.closeInspector(); await H.reloadDomain("tasks"); H.render(); } catch (e) { H.toast(e.message); } };
    const ab = g("edAbandon"); if (ab) ab.onclick = async () => { const t = H.state.tasks.find((x) => x.id === d.id); try { await H.api.mutate("tasks", "abandon", { id: d.id, abandoned: !(t && t.abandoned) }); H.closeInspector(); await H.reloadDomain("tasks"); H.render(); } catch (e) { H.toast(e.message); } };
  }
  function bind(elm, ev, fn) { if (elm) elm.addEventListener(ev, fn); }
  function bindAll(list, ev, fn) { list.forEach((e) => { if (e) e.addEventListener(ev, fn); }); }
  function wireSideTables(ins) {
    const d = ed.draft;
    const subAdd = ins.querySelector("#edSubAdd"); if (subAdd) subAdd.onsubmit = async (e) => { e.preventDefault(); const inp = subAdd.querySelector("input"); const v = inp.value.trim(); if (!v) return; try { await H.api.mutate("tasks", "subtask", { id: d.id, title: v }); inp.value = ""; await H.reloadDomain("tasks"); H.renderInspector(); } catch (err) { H.toast(err.message); } };
    ins.querySelectorAll("[data-subtoggle]").forEach((b) => b.onclick = async () => { const k = H.state.tasks.find((x) => x.id === b.dataset.subtoggle); try { await H.api.mutate("tasks", "complete", { id: b.dataset.subtoggle, completed: !(k && k.completed) }); await H.reloadDomain("tasks"); H.renderInspector(); } catch (e) { H.toast(e.message); } });
    ins.querySelectorAll("[data-subopen]").forEach((b) => b.onclick = () => H.openInspector("tasks", b.dataset.subopen));
    const clAdd = ins.querySelector("#edClAdd"); if (clAdd) clAdd.onsubmit = async (e) => { e.preventDefault(); const inp = clAdd.querySelector("input"); const v = inp.value.trim(); if (!v) return; try { await H.api.mutate("tasks", "checklistAdd", { id: d.id, text: v }); inp.value = ""; await loadSub(d.id); } catch (err) { H.toast(err.message); } };
    ins.querySelectorAll("[data-clset]").forEach((b) => b.onchange = async () => { try { await H.api.mutate("tasks", "checklistSet", { id: b.dataset.clset, taskId: d.id, checked: b.checked }); } catch (e) { H.toast(e.message); } });
    ins.querySelectorAll("[data-cldel]").forEach((b) => b.onclick = async () => { try { await H.api.mutate("tasks", "checklistDelete", { id: b.dataset.cldel }); await loadSub(d.id); } catch (e) { H.toast(e.message); } });
    const rmAdd = ins.querySelector("#edRmAdd"); if (rmAdd) rmAdd.onsubmit = async (e) => { e.preventDefault(); const off = Number(rmAdd.querySelector("select").value); try { await H.api.mutate("tasks", "reminderAdd", { id: "", taskId: d.id, type: "relativeToDue", offsetMin: off }); await loadSub(d.id); } catch (err) { H.toast(err.message); } };
    ins.querySelectorAll("[data-rmdel]").forEach((b) => b.onclick = async () => { try { await H.api.mutate("tasks", "reminderDelete", { id: b.dataset.rmdel }); await loadSub(d.id); } catch (e) { H.toast(e.message); } });
    const depAdd = ins.querySelector("#edDepAdd"); if (depAdd) depAdd.onsubmit = async (e) => { e.preventDefault(); const v = depAdd.querySelector("select").value; if (!v) return; try { await H.api.mutate("tasks", "dependencyAdd", { taskId: d.id, dependsOnTaskId: v }); await H.reloadDomain("tasks"); ed.deps = (H.state.dependencies || []).filter((x) => x.taskId === d.id); H.renderInspector(); } catch (err) { H.toast(err.message); } };
    ins.querySelectorAll("[data-depdel]").forEach((b) => b.onclick = async () => { try { await H.api.mutate("tasks", "dependencyRemove", { taskId: d.id, dependsOnTaskId: b.dataset.depdel }); await H.reloadDomain("tasks"); ed.deps = (H.state.dependencies || []).filter((x) => x.taskId === d.id); H.renderInspector(); } catch (e) { H.toast(e.message); } });
  }
  async function doSave() {
    const d = ed.draft;
    if (!d.title.trim()) { H.toast("Title can't be empty"); return; }
    try {
      const res = await H.api.mutate("tasks", "upsert", d);
      const id = (res && res.id) ? res.id : d.id;
      if (id) { await H.api.mutate("tasks", "setTags", { id, tagIds: d.tagIds }).catch(() => {}); await H.api.mutate("tasks", "setContexts", { id, contextIds: d.contextIds }).catch(() => {}); }
      await H.reloadDomain("tasks");
      ed.id = null; // force fresh draft next open
      if (id) { H.ui.sel = { domain: "tasks", id }; } else H.ui.sel = null;
      H.render(); H.toast("Saved");
    } catch (e) { H.toast(e.message); }
  }

  H.inspectors.tasks = function (ins, id) {
    if (ed.id !== id) { ed.id = id; ed.more = false; if (id === "__new__") { ed.draft = newDraft(); ed.checklist = []; ed.reminders = []; ed.deps = []; } else { const t = H.state.tasks.find((x) => x.id === id); if (!t) { H.closeInspector(); return; } ed.draft = draftFrom(t); ed.checklist = []; ed.reminders = []; ed.deps = (H.state.dependencies || []).filter((x) => x.taskId === id); loadSub(id); } }
    ins.innerHTML = renderEditor();
    wireEditor(ins);
  };
})();
