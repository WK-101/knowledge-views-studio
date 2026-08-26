package com.todocompanion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run tour (F1) — a calm, skippable walkthrough that actually shows off the breadth: the three
 * modules, the Do-Next engine, one-line capture and the command palette, habits, time statistics, the
 * cross-module reasoning that's ours alone, and the offline promise. Each page names concrete features.
 */
@Composable
fun Onboarding(onDone: () -> Unit) {
    data class Page(val emoji: String, val title: String, val body: String, val bullets: List<String>)
    val pages = listOf(
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
        Page("⧗", "See where your time goes",
            "One tap starts a timer. A full Statistics screen shows a donut, Day/Week/Month/Year ranges and per-activity drill-downs — and time links to tasks & habits.",
            listOf("Donut + ranked breakdowns + trends", "Link an activity to a task or a habit", "On-device automations & a live timer")),
        Page("🧭", "It reasons across all three",
            "Only a unified, on-device store can do this: keystone habits, honest capacity, unified goals, momentum, weekly recaps and a private annual review.",
            listOf("Momentum across tasks, habits & time", "Any-period recap & “year in review”", "Cross-module goals & honest forecasting")),
        Page("🔒", "Yours, and only yours",
            "Fully offline — no account, no cloud, no ads, and no internet or location permission at all. Back up or sync through a folder you choose, whenever you like.",
            listOf("0 network · 0 location permissions", "Lossless JSON export — your data stays portable", "Folder backup & account-free sync")),
    )
    var i by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip") }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = i,
                    transitionSpec = {
                        if (targetState > initialState)
                            (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                        else (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                    },
                    label = "page",
                ) { idx ->
                    val p = pages[idx]
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.size(104.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text(p.emoji, style = MaterialTheme.typography.displaySmall)
                        }
                        Spacer(Modifier.height(22.dp))
                        Text(p.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
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
                    }
                }
            }
            Row(Modifier.padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                pages.indices.forEach { idx ->
                    Box(Modifier.size(if (idx == i) 9.dp else 6.dp).background(
                        if (idx == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (i > 0) TextButton(onClick = { i-- }) { Text("Back") }
                Button(onClick = { if (i < pages.lastIndex) i++ else onDone() },
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 14.dp)) {
                    Text(if (i < pages.lastIndex) "Next" else "Get started")
                }
            }
        }
    }
}
