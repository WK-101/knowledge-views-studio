package com.cairn.reader.ui.onboarding

import androidx.compose.ui.res.stringResource
import com.cairn.reader.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cairn.reader.ui.theme.ReadingSerif

/** First-run welcome: the three things Cairn is, and one button to begin. */
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CairnMark(size = 64.dp)
        Spacer(Modifier.height(22.dp))
        Text(stringResource(R.string.welcome_to_cairn),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = ReadingSerif),
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.one_quiet_private_home_for_everything),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(36.dp))

        Pillar(
            icon = Icons.Outlined.RssFeed,
            title = "Follow",
            body = "Subscribe to feeds and read full articles in a clean, native reader — no browser, no clutter.",
        )
        Spacer(Modifier.height(20.dp))
        Pillar(
            icon = Icons.AutoMirrored.Outlined.LibraryBooks,
            title = "Keep",
            body = "Save anything into a Raindrop-style library — collections, tags, highlights and notes.",
        )
        Spacer(Modifier.height(20.dp))
        Pillar(
            icon = Icons.Outlined.Lock,
            title = "Yours forever",
            body = "No account, no servers, no tracking — everything stays on your device. And nothing's locked in: export to Markdown, EPUB or a full backup any time. If Cairn ever vanished, your library wouldn't.",
        )

        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onGetStarted,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.get_started), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.we_ve_added_a_few_sample),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Pillar(icon: ImageVector, title: String, body: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.padding(top = 2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
    }
}

/** The stacked-stones Cairn mark. */
@Composable
private fun CairnMark(size: Dp) {
    val tint = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier.size(size),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(size * 0.06f, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(width = size * 0.30f, height = size * 0.15f).clip(CircleShape).background(tint.copy(alpha = 0.45f)))
        Box(Modifier.size(width = size * 0.52f, height = size * 0.17f).clip(CircleShape).background(tint.copy(alpha = 0.65f)))
        Box(Modifier.size(width = size * 0.74f, height = size * 0.19f).clip(CircleShape).background(tint.copy(alpha = 0.82f)))
        Box(Modifier.size(width = size * 0.95f, height = size * 0.21f).clip(CircleShape).background(tint))
    }
}
