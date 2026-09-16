@file:OptIn(ExperimentalMaterial3Api::class)

package com.cairn.reader.ui.review

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.R
import com.cairn.reader.domain.review.Grade
import com.cairn.reader.domain.review.intervalLabel
import com.cairn.reader.ui.components.EmptyState
import com.cairn.reader.ui.theme.Dimens
import kotlin.math.roundToInt

@Composable
fun ReviewScreen(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    // Load a fresh session of due cards each time the Review pane is opened.
    LaunchedEffect(Unit) { viewModel.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.review), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Outlined.Menu, contentDescription = stringResource(R.string.open_navigation))
                    }
                },
                actions = {
                    // Always show which scheduler is grading, so the interval previews are legible.
                    if (!state.loading) SchedulerBadge(state.advanced, state.retention, scheme)
                },
            )
        },
    ) { inner ->
        // This pane nests its own Scaffold inside the app shell, whose bottom NavigationBar the shell
        // reports through [padding]. The child Scaffold's own insets only cover its top bar, so the
        // bottom must be taken from the shell — otherwise the grade controls render *behind* the
        // opaque NavigationBar and can't be tapped (the whole surface then looks inert).
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(top = inner.calculateTopPadding(), bottom = padding.calculateBottomPadding())

        when {
            state.loading -> Box(contentModifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.face == null -> AllDone(state, contentModifier, scheme)
            else -> ReviewCardBody(state, contentModifier, scheme, viewModel)
        }
    }
}

@Composable
private fun SchedulerBadge(advanced: Boolean, retention: Float, scheme: androidx.compose.material3.ColorScheme) {
    val label = if (advanced) {
        "${stringResource(R.string.review_scheduler_advanced)} · ${(retention * 100).roundToInt()}%"
    } else {
        stringResource(R.string.review_scheduler_basic)
    }
    Surface(
        color = if (advanced) scheme.primaryContainer else scheme.surfaceContainerHighest,
        contentColor = if (advanced) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier.padding(end = Dimens.md),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (advanced) Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ReviewCardBody(
    state: ReviewUiState,
    modifier: Modifier,
    scheme: androidx.compose.material3.ColorScheme,
    viewModel: ReviewViewModel,
) {
    val face = state.face ?: return
    val total = state.reviewed + state.remaining
    val position = (state.reviewed + 1).coerceAtMost(total)

    Column(modifier) {
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else state.reviewed.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = scheme.primary, trackColor = scheme.surfaceContainerHighest,
        )
        Text(
            stringResource(R.string.review_progress, position, total),
            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = Dimens.sm), textAlign = TextAlign.Center,
        )

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Dimens.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Colour dot from the highlight's colour, as a small anchor to the original.
            Box(Modifier.size(10.dp).background(Color(face.card.color), RoundedCornerShape(5.dp)))
            Spacer(Modifier.height(Dimens.lg))

            val prompt = face.clozePrompt
                ?: face.card.articleSite?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.recall_prompt_from, it) }
                ?: stringResource(R.string.recall_prompt)
            Text(
                prompt,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium, color = scheme.onSurface, textAlign = TextAlign.Center,
                lineHeight = MaterialTheme.typography.headlineSmall.lineHeight,
            )

            if (state.revealed) {
                Spacer(Modifier.height(Dimens.xl))
                Card(
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        if (face.isCloze) {
                            Text(
                                stringResource(R.string.answer),
                                style = MaterialTheme.typography.labelSmall, color = scheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("“${face.card.quote.trim()}”", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                        face.card.note?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(Dimens.sm))
                            Text(it.trim(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(Dimens.sm))
                        Text(
                            (face.card.articleSite ?: "").ifBlank { face.card.articleTitle },
                            style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        )
                        // Advanced scheduler: show this card's current memory model so the reviewer
                        // understands why the intervals look the way they do.
                        if (state.advanced) {
                            Spacer(Modifier.height(Dimens.md))
                            MemoryStrength(face.card.srStability, face.card.srDifficulty, scheme)
                        }
                    }
                }
            }
        }

        // Controls: reveal, then the four grades with their next-interval previews.
        if (!state.revealed) {
            Button(
                onClick = { viewModel.reveal() },
                modifier = Modifier.fillMaxWidth().padding(Dimens.xl),
            ) { Text(stringResource(R.string.show_answer)) }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.md, vertical = Dimens.lg),
                horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
            ) {
                GradeButton(stringResource(R.string.review_grade_again), face.intervals[Grade.AGAIN], scheme.errorContainer, scheme.onErrorContainer, Modifier.weight(1f)) { viewModel.grade(Grade.AGAIN) }
                GradeButton(stringResource(R.string.review_grade_hard), face.intervals[Grade.HARD], scheme.surfaceContainerHighest, scheme.onSurface, Modifier.weight(1f)) { viewModel.grade(Grade.HARD) }
                GradeButton(stringResource(R.string.review_grade_good), face.intervals[Grade.GOOD], scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.weight(1f)) { viewModel.grade(Grade.GOOD) }
                GradeButton(stringResource(R.string.review_grade_easy), face.intervals[Grade.EASY], scheme.primaryContainer, scheme.onPrimaryContainer, Modifier.weight(1f)) { viewModel.grade(Grade.EASY) }
            }
        }
    }
}

@Composable
private fun MemoryStrength(stability: Double, difficulty: Double, scheme: androidx.compose.material3.ColorScheme) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
        val text = if (stability <= 0.0 || difficulty <= 0.0) {
            stringResource(R.string.review_memory_new)
        } else {
            val diffPct = (difficulty / 10.0 * 100).roundToInt()
            stringResource(R.string.review_memory_detail, intervalLabel(stability.roundToInt()), diffPct)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun GradeButton(label: String, interval: String?, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = bg, contentColor = fg),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            interval?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun AllDone(state: ReviewUiState, modifier: Modifier, scheme: androidx.compose.material3.ColorScheme) {
    if (state.reviewed > 0) {
        Column(
            modifier.verticalScroll(rememberScrollState()).padding(Dimens.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(Dimens.lg))
            Text(
                stringResource(R.string.review_complete),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.sm))
            Text(
                pluralStringResource(R.plurals.review_complete_count, state.reviewed, state.reviewed),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Dimens.xl))
            SessionSummary(state, scheme)
        }
    } else {
        EmptyState(
            title = stringResource(R.string.nothing_due),
            body = stringResource(R.string.nothing_due_body),
            modifier = modifier,
            icon = Icons.Outlined.School,
        )
    }
}

@Composable
private fun SessionSummary(state: ReviewUiState, scheme: androidx.compose.material3.ColorScheme) {
    Card(
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(Dimens.lg)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SummaryStat(state.reviewed.toString(), stringResource(R.string.review_summary_reviewed), scheme)
                SummaryStat("${(state.accuracy * 100).roundToInt()}%", stringResource(R.string.review_summary_accuracy), scheme)
            }
            Spacer(Modifier.height(Dimens.lg))
            // Per-grade breakdown as four coloured chips, mirroring the grade-button palette.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.sm)) {
                GradeTally(stringResource(R.string.review_grade_again), state.again, scheme.errorContainer, scheme.onErrorContainer, Modifier.weight(1f))
                GradeTally(stringResource(R.string.review_grade_hard), state.hard, scheme.surfaceContainerHighest, scheme.onSurface, Modifier.weight(1f))
                GradeTally(stringResource(R.string.review_grade_good), state.good, scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.weight(1f))
                GradeTally(stringResource(R.string.review_grade_easy), state.easy, scheme.primaryContainer, scheme.onPrimaryContainer, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryStat(value: String, label: String, scheme: androidx.compose.material3.ColorScheme) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun GradeTally(label: String, count: Int, bg: Color, fg: Color, modifier: Modifier) {
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(10.dp), modifier = modifier) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
