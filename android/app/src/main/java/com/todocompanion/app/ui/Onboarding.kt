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
 * First-run tour (F1) — a calm, skippable walkthrough that actually shows off the breadth: the four
 * modules (tasks, habits, time, notes), the Do-Next engine, one-line capture and the command palette,
 * habit-building, a linked note vault, time & focus, the cross-module reasoning that's ours alone,
 * home-screen widgets, and the offline promise. Each page names concrete features.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    data class Page(val emoji: String, val title: String, val body: String, val bullets: List<String>, val brand: Boolean = false)
    val pages = listOf(
        // R68 — the brand story: where the name and the mark come from, and why they were chosen.
        Page("✦", "Meet Hexis",
            "The name is Greek — hexis, Aristotle's word for a settled disposition won through practice: the habit that becomes character. He called excellence itself a hexis. It fits an app built to turn small daily acts — tasks, a calendar, habits, time, notes and a private life-systems engine — into the person you are becoming.",
            listOf(
                "🏛️  Hexis — Aristotle's word for the trained disposition, the habit made character",
                "✦  The mark is four chevrons — tasks, habits, time, notes — converging on one gold centre: a compass pointing to the next right thing",
                "🎯  Name and mark say one thing — small acts, repeated, become who you are"),
            brand = true),
        Page("🌱", "Four tools, one calm app",
            "Tasks, habits, time and notes live together in one private place — make any one your home base, turn off what you don't need, and add the rest whenever you're ready.",
            listOf("✓  To-dos with dates, priority & subtasks", "↻  Habits with streaks & a strength score", "⧗  Time tracking with rich statistics", "📝  Notes — a linked, offline knowledge base")),
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
            "One tap starts a timer — or a distraction-free Focus session. A full Statistics screen shows a donut, Day/Week/Month/Year ranges and per-activity drill-downs, and every minute links back to your tasks & habits.",
            listOf("Donut + ranked breakdowns + trends", "A Focus timer with a daily deep-work goal", "Link an activity to a task or a habit", "On-device automations & a live home-screen timer")),
        Page("📝", "Your notes, a second brain",
            "A full writing space that links up like a wiki: connect any two notes with [[wikilinks]], watch the web of ideas grow, and let old thinking resurface at the right moment. Everything stays plain text you own.",
            listOf(
                "Backlinks + a visual graph — a connected web of ideas",
                "Daily & periodic notes, templates, and an ink canvas",
                "Spaced-review cards resurface old notes so they stick",
                "Properties, tags & saved queries · mirror to Markdown files")),
        Page("🧭", "It reasons across everything",
            "Only a unified, on-device store can do this: keystone habits, honest capacity, 12-week goals, a single momentum score, weekly recaps and a private annual review — connections a folder of separate apps can never see.",
            listOf("Momentum across tasks, habits, time & focus", "12-week goals with key results & cycle pacing", "Any-period recap & a private “year in review”", "Cross-module correlations & honest forecasting")),
        // R68 — new since the last tour: the calendar moat, occasions, the record, the life-systems
        // gallery, and the home-screen surface. Each names screens you can actually open.
        Page("📅", "A calendar that plans your day",
            "A full calendar lives inside Hexis — events with recurrence and alerts, protected time-blocks, and a planner that fits your tasks into the gaps of your day. No Google account, no sync.",
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
                "Share an occasion card; attach photos & files")),
        Page("🏆", "A record of everything you finish",
            "Every completed task becomes an achievement you can look back on — a living record with a heatmap, milestones and skills — and each day ends with a one-glance review.",
            listOf(
                "The Record: trophy case, “on this day”, a brag / résumé doc",
                "Day Review — an end-of-day digest of tasks, habits, time & mood",
                "Impact map, milestone ledger & pattern insights",
                "Wrapped — your private year in review")),
        Page("🧰", "A workshop of life-systems tools",
            "Beyond habits, Hexis carries a gallery of on-device, science-backed tools for building a life on purpose. Open the Life Systems hub and pick one when you need it.",
            listOf(
                "Guided routines — run a morning or wind-down sequence step-by-step",
                "Grounding library — 5-4-3-2-1 & box breathing for hard moments",
                "Temptation bundling & if-then plans, fired at the right cue",
                "Rank your values (a card-sort) & self-escrow commitments",
                "Fresh-start windows, a causal graph & your own correlations")),
        Page("🧩", "Home-screen widgets & one-tap capture",
            "Put Hexis on your home screen — 22 widgets across every module, each themeable with its own size, opacity and light/dark, plus a tiny add-task button that pops a capture panel without ever opening the app.",
            listOf(
                "22 widgets: Do-Next, Agenda, Day, Matrix, Habits, Habit Insight, Goal Sprint, Focus, Time, Next Up, Routine Runner, Close the Day…",
                "A 1×1 Quick-add button → a popup task panel, straight from home",
                "A configurable Quick Actions island · long-press: Add · Today · Do-Next · Focus",
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
            // The finish CTA sits ABOVE the dots, and only on the last page — its height is reserved so
            // the dots + signature below never shift as you swipe. Everything else is driven by swiping.
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(52.dp), contentAlignment = Alignment.Center) {
                if (onLastPage) {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                        Text("Get started")
                    }
                }
            }
            // Page indicator — sits directly above the maker's mark (not floating mid-screen); tap a dot to jump.
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                pages.indices.forEach { idx ->
                    val active = idx == pagerState.currentPage
                    Box(
                        Modifier.size(if (active) 9.dp else 6.dp)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { scope.launch { pagerState.animateScrollToPage(idx) } },
                    )
                }
            }
            // The same maker's mark that closes the sidebar & Settings, so the tour signs off in kind.
            com.todocompanion.app.ui.components.AppSignature()
        }
    }
}

/**
 * The Hexis mark ("Four into one") — four chevrons (tasks · habits · time · notes) converging on a
 * single gold centre, a compass of the four modules pointing to one bearing. Rendered purely with
 * Canvas primitives so the first-run tour never depends on inflating a drawable resource. Mirrors the
 * launcher icon's foreground exactly (108-unit design space, scaled to the given size).
 */
@Composable
private fun KairoMark(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 108f
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
        val light = androidx.compose.ui.graphics.Color(0xFFECE7F6)
        val gold = androidx.compose.ui.graphics.Color(0xFFF5B01E)
        // Gold halo behind the core.
        drawCircle(gold.copy(alpha = 0.22f), radius = 12f * s, center = o(54f, 54f))
        // Four chevrons, each leaning inward toward the centre.
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 7.5f * s,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        fun chevron(ax: Float, ay: Float, apx: Float, apy: Float, bx: Float, by: Float) {
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(ax * s, ay * s); lineTo(apx * s, apy * s); lineTo(bx * s, by * s)
            }
            drawPath(p, light, style = stroke)
        }
        chevron(41f, 26f, 54f, 39f, 67f, 26f) // N
        chevron(82f, 41f, 69f, 54f, 82f, 67f) // E
        chevron(41f, 82f, 54f, 69f, 67f, 82f) // S
        chevron(26f, 41f, 39f, 54f, 26f, 67f) // W
        // The gold core — the one point the four resolve to.
        drawCircle(gold, radius = 6.5f * s, center = o(54f, 54f))
    }
}
