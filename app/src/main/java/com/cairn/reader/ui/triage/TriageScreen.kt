@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.triage

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.data.db.ItemListRow
import kotlin.math.abs
import kotlin.math.roundToInt

private val BUDGETS = listOf(0 to "Any", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hr")

@Composable
fun TriageScreen(
    padding: PaddingValues,
    onOpenItem: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
    viewModel: TriageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.triage), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation)) } },
            )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            // Time-budget selector.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.i_have), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
                BUDGETS.forEach { (mins, label) ->
                    FilterChip(selected = budget == mins, onClick = { viewModel.setBudget(mins) }, label = { Text(label) })
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.deck.isEmpty() -> EmptyDeck(state.done)
                else -> Deck(
                    state = state,
                    onOpen = { id -> viewModel.opened(); onOpenItem(id) },
                    onSave = viewModel::save,
                    onDismiss = viewModel::dismiss,
                )
            }
        }
    }
}

@Composable
private fun EmptyDeck(done: Int) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.DoneAll, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(if (done > 0) "All caught up" else "Nothing to triage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (done > 0) "You triaged $done stories. Nicely done." else "Your unread stories will appear here as a deck to swipe through.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Deck(
    state: TriageUiState,
    onOpen: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            // Peek of the next card behind the top one.
            state.deck.getOrNull(1)?.let { next ->
                TriageCard(next, Modifier.graphicsLayer { scaleX = 0.94f; scaleY = 0.94f; translationY = 24.dp.toPx() }, scheme, onClick = {})
            }
            val top = state.deck.first()
            SwipeableCard(item = top, onOpen = { onOpen(top.id) }, onSave = onSave, onDismiss = onDismiss)
        }
        // Accessible controls that mirror the swipe gestures.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skip))
            }
            FilledTonalButton(onClick = onSave, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.save))
            }
        }
        Text(
            "${state.deck.size} left · ${state.done} done",
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun SwipeableCard(item: ItemListRow, onOpen: () -> Unit, onSave: () -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var dismissed by remember(item.id) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = offsetX, label = "cardOffset")

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = animatedOffset
                rotationZ = (animatedOffset / 60f).coerceIn(-8f, 8f)
            }
            .pointerInput(item.id) {
                val threshold = size.width * 0.32f
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            offsetX > threshold -> { dismissed = true; offsetX = size.width * 1.5f; onSave() }
                            offsetX < -threshold -> { dismissed = true; offsetX = -size.width * 1.5f; onDismiss() }
                            else -> offsetX = 0f
                        }
                    },
                    onHorizontalDrag = { _, dragAmount -> if (!dismissed) offsetX += dragAmount },
                )
            },
    ) {
        TriageCard(item, Modifier.fillMaxSize(), scheme, onClick = onOpen)
        // Directional hint overlays.
        if (offsetX > 24) HintBadge("SAVE", scheme.primary, Alignment.TopStart)
        if (offsetX < -24) HintBadge("SKIP", scheme.onSurfaceVariant, Alignment.TopEnd)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.HintBadge(text: String, color: androidx.compose.ui.graphics.Color, align: Alignment) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color,
        modifier = Modifier.align(align).padding(24.dp),
    )
}

@Composable
private fun TriageCard(item: ItemListRow, modifier: Modifier, scheme: androidx.compose.material3.ColorScheme, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                (item.sourceTitle ?: item.siteName ?: "").uppercase(),
                style = MaterialTheme.typography.labelMedium, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = scheme.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis)
            if (item.readingMinutes > 0) {
                Spacer(Modifier.height(8.dp))
                Text("${item.readingMinutes} min read", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            }
            item.excerpt?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.tap_to_read_swipe_to_triage), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}
