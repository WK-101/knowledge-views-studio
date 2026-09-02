package com.todocompanion.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * First-run tour (F1) — a calm, skippable walkthrough that actually shows off the breadth: the three
 * modules, the Do-Next engine, one-line capture and the command palette, habits, time statistics, the
 * cross-module reasoning that's ours alone, and the offline promise. Each page names concrete features.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    data class Page(val emoji: String, val title: String, val body: String, val bullets: List<String>, val brand: Boolean = false)
    val pages = listOf(
        // R68 — the brand story: where the name and the mark come from, and why they were chosen.
        Page("✦", "Meet Kairo",
            "The name is Greek — kairos, the opportune moment to act, as opposed to chronos, mere clock-time. It fits an app that has grown well past a to-do list: tasks, a calendar, habits, time tracking, occasions and a private life-systems engine — all to help you do the right thing at the right time.",
            listOf(
                "🏛️  Kairos — the ancient word for the perfect moment",
                "✦  The icon is an aperture opening onto a guiding star: the opening is the moment, the star is what it reveals",
                "🎯  Name and mark chosen to say one thing — act at the right time"),
            brand = true),
        Page("🌱", "Three tools, one calm app",
            "Tasks, habits and time tracking live together in one private place — turn on only what you need, add the rest later.",
            listOf("✓  To-dos with dates, priority & subtasks", "↻  Habits with streaks & strength", "⧗  Time tracking with rich statistics")),
        Page("⚡", "It decides what's next",
            "Set importance, urgency and dates — the Do-Next list computes the single most worthwhile thing to do right now. No manual re-sorting.",
            listOf("Filter by time available & energy", "Eisenhower matrix & board views", "Deadlines, workload forecast & auto-schedule")),
        Page("⌨️", "Capture in one line",
            "Type “pay rent tomorrow 5pm !! #home” and the date, priority and tag are parsed for you. Or open the ✨ command palette to do anything.",
            listOf("Natural-language dates, priority, tags", "“track deep work” · “go to habits” · “hours on Reading this week”", "Voice capture with the mic")),
        Page("🔥", "Habits that actually stick",
            "A strength score values consistency over brittle streaks. Flexible schedules, numeric goals, habit stacking and one-tap starter routines.",
            listOf("Forgiving streaks & streak-freezes", "× per week / month, or every N days", "Identity, stacking & starter gallery")),
        Page("🌟", "Not just a tracker — a builder",
            "It actively helps you form good habits and break bad ones, using proven behaviour-change science — all on-device, no coaching subscription.",
            listOf("Intention plans + WOOP obstacle & coping", "66-day automaticity meter · never-miss-twice + freezes", "Quit dashboard, urge surfing & competing-response swaps", "Two-minute ramp-up, celebrations & guided journeys")),
        Page("🧭", "A private life-systems engine",
            "Anchor habits to your values, keep yourself accountable without any account, and let years of your own data reveal what actually works for you.",
            listOf("Values → systems → habits, calm mode", "Commitment contracts + a local referee, self-forfeits", "On-device correlation engine: “meditate → mood +1.2”", "Weekly & annual integrity review · a permanent identity ledger")),
        Page("⧗", "See where your time goes",
            "One tap starts a timer. A full Statistics screen shows a donut, Day/Week/Month/Year ranges and per-activity drill-downs — and time links to tasks & habits.",
            listOf("Donut + ranked breakdowns + trends", "Link an activity to a task or a habit", "On-device automations & a live timer")),
        Page("🧭", "It reasons across all three",
            "Only a unified, on-device store can do this: keystone habits, honest capacity, unified goals, momentum, weekly recaps and a private annual review.",
            listOf("Momentum across tasks, habits & time", "Any-period recap & “year in review”", "Cross-module goals & honest forecasting")),
        // R68 — new since the last tour: the calendar moat, occasions, the record, the life-systems
        // gallery, and the home-screen surface. Each names screens you can actually open.
        Page("📅", "A calendar that plans your day",
            "A full calendar lives inside Kairo — events with recurrence and alerts, protected time-blocks, and a planner that fits your tasks into the gaps of your day. No Google account, no sync.",
            listOf(
                "Time-blocking with durations & focus-protected blocks",
                "“When am I free?” availability + an auto-schedule planner",
                "Holiday packs, moon phases & event templates — all offline",
                "Import & export .ics; a dual-timezone day ruler")),
        Page("🎂", "The people & dates that matter",
            "Birthdays, anniversaries and memorials — with age, zodiac and the next occurrence — plus a gentle keep-in-touch guardian so a friendship never quietly lapses.",
            listOf(
                "Countdowns to any date (and a home-screen widget)",
                "Keep-in-touch cadence + an “on this day” almanac",
                "Import birthdays straight from a .vcf contact card",
                "Share a occasion card; attach photos & files")),
        Page("🏆", "A record of everything you finish",
            "Every completed task becomes an achievement you can look back on — a living record with a heatmap, milestones and skills — and each day ends with a one-glance review.",
            listOf(
                "The Record: trophy case, “on this day”, a brag / résumé doc",
                "Day Review — an end-of-day digest of tasks, habits, time & mood",
                "Impact map, milestone ledger & pattern insights",
                "Wrapped — your private year in review")),
        Page("🧰", "A workshop of life-systems tools",
            "Beyond habits, Kairo carries a gallery of on-device, science-backed tools for building a life on purpose. Open the Life Systems hub and pick one when you need it.",
            listOf(
                "Grounding library — 5-4-3-2-1 & box breathing for hard moments",
                "Temptation bundling & if-then plans, fired at the right cue",
                "Rank your values (a card-sort) & self-escrow commitments",
                "Fresh-start windows, a causal graph & your own correlations")),
        Page("🧩", "Home-screen widgets & one-tap capture",
            "Put Kairo on your home screen — a shelf of widgets for every module, plus a tiny add-task button that pops a capture panel without ever opening the app. Keep areas of life apart with workspaces.",
            listOf(
                "14 widgets: Do-Next, Habits, Agenda, Matrix, Time, The Record, Momentum…",
                "A 1×1 Quick-add button → a popup task panel, straight from home",
                "Long-press the icon: Quick add · Today · Do-Next · Focus",
                "Workspaces keep Work and Personal fully separate")),
        Page("🔒", "Yours, and only yours",
            "Fully offline — no account, no cloud, no ads, and no internet or location permission at all. Back up or sync through a folder you choose, whenever you like.",
            listOf("0 network · 0 location permissions", "Lossless JSON export — your data stays portable", "Folder backup & account-free sync")),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val onLastPage = pagerState.currentPage == pages.lastIndex
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // Skip is always available in the corner — it doubles as the dismiss on the final page.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip") }
            }
            // Swipe between pages — no Next/Back buttons. Each page is laid out so the icon and title
            // sit in a FIXED top zone (identical vertical position on every page, so the mark never
            // drifts as text length changes); the variable body + bullets scroll independently below.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.Top,
            ) { idx ->
                val p = pages[idx]
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    // Fixed-height header: the icon is vertically centred inside a constant-height box, so
                    // its centre lands at the same Y on every page regardless of what follows.
                    Box(Modifier.fillMaxWidth().height(172.dp), contentAlignment = Alignment.Center) {
                        if (p.brand) {
                            // Render the Kairo mark (The Reveal) on its gradient tile by DRAWING it with a
                            // Compose Canvas — no resource is loaded at all, so this page can never crash on a
                            // drawable parse. (The R68 startup crash was painterResource() being handed the
                            // @mipmap/ic_launcher <adaptive-icon>, which it cannot parse; this removes the whole
                            // risk class from the first-run path.)
                            Box(
                                Modifier.size(120.dp).clip(RoundedCornerShape(28.dp))
                                    .background(androidx.compose.ui.graphics.Brush.linearGradient(
                                        listOf(androidx.compose.ui.graphics.Color(0xFF2B2050), androidx.compose.ui.graphics.Color(0xFF3C2668)))),
                                contentAlignment = Alignment.Center,
                            ) {
                                KairoMark(Modifier.size(120.dp))
                            }
                        } else {
                            Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text(p.emoji, style = MaterialTheme.typography.displaySmall)
                            }
                        }
                    }
                    Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    // Everything below the title scrolls, so a long page never pushes the header around.
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
                        Spacer(Modifier.height(18.dp))
                        // Concrete feature bullets — the "so many rich features", named.
                        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            p.bullets.forEach { b ->
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                        Spacer(Modifier.width(12.dp))
                                        Text(b, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            // Page indicator — reflects the pager; tap a dot to jump to that page.
            Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                pages.indices.forEach { idx ->
                    val active = idx == pagerState.currentPage
                    Box(
                        Modifier.size(if (active) 9.dp else 6.dp)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { scope.launch { pagerState.animateScrollToPage(idx) } },
                    )
                }
            }
            // Only a finish CTA, and only on the last page — reserve its height so the signature below
            // never shifts as you swipe. Everything else is driven by swiping, not buttons.
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(52.dp), contentAlignment = Alignment.Center) {
                if (onLastPage) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                        Text("Get started")
                    }
                }
            }
            // The same maker's mark that closes the sidebar & Settings, so the tour signs off in kind.
            com.todocompanion.app.ui.components.AppSignature()
        }
    }
}

/**
 * The Kairo mark ("The Reveal") drawn as a 3D box with the guiding star in front — rendered purely with
 * Canvas primitives so the first-run tour never depends on inflating a drawable resource. Mirrors the
 * launcher icon's foreground (108-unit design space, scaled to the given size).
 */
@Composable
private fun KairoMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 108f
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
        fun path(vararg pts: Pair<Float, Float>) = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].first * s, pts[0].second * s)
            for (i in 1 until pts.size) lineTo(pts[i].first * s, pts[i].second * s)
            close()
        }
        // Three cube faces (top lightest → sides step darker).
        drawPath(path(54f to 19f, 84.3f to 36.5f, 54f to 54f, 23.7f to 36.5f), androidx.compose.ui.graphics.Color(0xFF8C7BC6))
        drawPath(path(84.3f to 36.5f, 84.3f to 71.5f, 54f to 89f, 54f to 54f), androidx.compose.ui.graphics.Color(0xFF5E5099))
        drawPath(path(23.7f to 36.5f, 54f to 54f, 54f to 89f, 23.7f to 71.5f), androidx.compose.ui.graphics.Color(0xFF473A74))
        // Cube outline + the three inner edges (drawn before the star, so they vanish behind it).
        val edge = androidx.compose.ui.graphics.Color(0xFFC6B8EE)
        drawPath(
            path(54f to 19f, 84.3f to 36.5f, 84.3f to 71.5f, 54f to 89f, 23.7f to 71.5f, 23.7f to 36.5f),
            edge, style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 2.4f * s,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        drawLine(edge, o(54f, 54f), o(84.3f, 36.5f), strokeWidth = 2.4f * s, cap = cap)
        drawLine(edge, o(54f, 54f), o(23.7f, 36.5f), strokeWidth = 2.4f * s, cap = cap)
        drawLine(edge, o(54f, 54f), o(54f, 89f), strokeWidth = 2.4f * s, cap = cap)
        // The guiding star, in front.
        val star = androidx.compose.ui.graphics.Path().apply {
            moveTo(54f * s, 31f * s)
            quadraticBezierTo(57.2f * s, 50.8f * s, 77f * s, 54f * s)
            quadraticBezierTo(57.2f * s, 57.2f * s, 54f * s, 77f * s)
            quadraticBezierTo(50.8f * s, 57.2f * s, 31f * s, 54f * s)
            quadraticBezierTo(50.8f * s, 50.8f * s, 54f * s, 31f * s)
            close()
        }
        drawPath(star, androidx.compose.ui.graphics.Color(0xFFF5B01E))
    }
}
