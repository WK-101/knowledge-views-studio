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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cairn.reader.domain.review.Grade

@Composable
fun ReviewScreen(
    padding: PaddingValues,
    onOpenDrawer: () -> Unit = {},
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    // Load a fresh session of due cards each time the Review pane is opened.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.face != null) "Review · ${state.reviewed + state.remaining - state.reviewed} left".let { "Review" } else "Review",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, contentDescription = "Open navigation") } },
            )
        },
    ) { inner ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.face == null -> AllDone(state.reviewed, Modifier.padding(inner))
            else -> {
                val face = state.face!!
                Column(Modifier.fillMaxSize().padding(inner)) {
                    val total = state.reviewed + state.remaining
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else state.reviewed.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = scheme.primary, trackColor = scheme.surfaceContainerHighest,
                    )
                    Text(
                        "${state.remaining} due · ${state.reviewed} reviewed",
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center,
                    )

                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Colour dot from the highlight's colour, as a small anchor to the original.
                        Box(Modifier.size(10.dp).background(Color(face.card.color), RoundedCornerShape(5.dp)))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            face.prompt,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Medium, color = scheme.onSurface, textAlign = TextAlign.Center,
                            lineHeight = MaterialTheme.typography.headlineSmall.lineHeight,
                        )
                        if (state.revealed) {
                            Spacer(Modifier.height(20.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(18.dp)) {
                                    if (face.isCloze) {
                                        Text("Answer", style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(6.dp))
                                    }
                                    Text("“${face.card.quote.trim()}”", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                                    face.card.note?.takeIf { it.isNotBlank() }?.let {
                                        Spacer(Modifier.height(10.dp))
                                        Text(it.trim(), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        (face.card.articleSite ?: "").ifBlank { face.card.articleTitle },
                                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    // Controls: reveal, then the four SM-2 grades with their next-interval previews.
                    if (!state.revealed) {
                        Button(
                            onClick = { viewModel.reveal() },
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                        ) { Text("Show answer") }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            GradeButton("Again", face.intervals[Grade.AGAIN], scheme.errorContainer, scheme.onErrorContainer, Modifier.weight(1f)) { viewModel.grade(Grade.AGAIN) }
                            GradeButton("Hard", face.intervals[Grade.HARD], scheme.surfaceContainerHighest, scheme.onSurface, Modifier.weight(1f)) { viewModel.grade(Grade.HARD) }
                            GradeButton("Good", face.intervals[Grade.GOOD], scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.weight(1f)) { viewModel.grade(Grade.GOOD) }
                            GradeButton("Easy", face.intervals[Grade.EASY], scheme.primaryContainer, scheme.onPrimaryContainer, Modifier.weight(1f)) { viewModel.grade(Grade.EASY) }
                        }
                    }
                }
            }
        }
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
private fun AllDone(reviewed: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (reviewed > 0) Icons.Outlined.CheckCircle else Icons.Outlined.School, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(if (reviewed > 0) "Review complete" else "Nothing due", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                if (reviewed > 0) "You reviewed $reviewed highlight${if (reviewed == 1) "" else "s"}. Come back tomorrow to keep them fresh."
                else "Highlight passages while you read, and they'll resurface here for spaced-repetition recall — the proven way to remember what you read.",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}
