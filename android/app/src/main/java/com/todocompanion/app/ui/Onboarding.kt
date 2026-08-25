package com.todocompanion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** First-run tour (F1): three cards that reveal the engine, capture, and the offline promise. */
@Composable
fun Onboarding(onDone: () -> Unit) {
    data class Page(val icon: ImageVector, val title: String, val body: String)
    val pages = listOf(
        Page(Icons.Filled.Bolt, "It decides what's next",
            "Set importance, urgency and dates — the Do-Next list computes the single most worthwhile task to do right now, MLO-style. No manual re-sorting."),
        Page(Icons.Filled.PlaylistAddCheck, "Capture in one line",
            "Type “pay rent tomorrow 5pm !! #home” into quick-add — the date, priority and tag are parsed for you. Add “!30m” for a reminder, or speak it with the mic."),
        Page(Icons.Filled.Lock, "Yours, and only yours",
            "Everything is offline — no account, no cloud, no ads, and no internet permission at all. Back up or sync through a folder you choose, whenever you like."),
    )
    var i by remember { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDone) { Text("Skip") }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(targetState = i, transitionSpec = { (fadeIn() togetherWith fadeOut()) }, label = "page") { idx ->
                    val p = pages[idx]
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(p.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
                        }
                        Spacer(Modifier.height(24.dp))
                        Text(p.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(p.body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                }
            }
            Row(Modifier.padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { idx ->
                    Box(Modifier.size(if (idx == i) 10.dp else 7.dp).background(
                        if (idx == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                }
            }
            Button(onClick = { if (i < pages.lastIndex) i++ else onDone() },
                modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 14.dp)) {
                Text(if (i < pages.lastIndex) "Next" else "Get started")
            }
        }
    }
}
