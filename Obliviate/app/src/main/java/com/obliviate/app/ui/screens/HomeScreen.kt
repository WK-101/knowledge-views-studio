package com.obliviate.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obliviate.app.core.clean.JunkCleaner
import com.obliviate.app.core.formatBytes
import com.obliviate.app.ui.components.InfoBanner
import com.obliviate.app.ui.components.ObliviateCard
import com.obliviate.app.ui.components.StatLine
import com.obliviate.app.ui.components.StorageGauge

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val shared = remember { JunkCleaner.sharedStat() }
    val internal = remember { JunkCleaner.internalStat() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Obliviate", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Overwrite the ghosts of deleted files.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        ObliviateCard {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StorageGauge(
                    fraction = shared.usedFraction,
                    centerLabel = formatBytes(shared.freeBytes),
                    subLabel = "free of ${formatBytes(shared.totalBytes)}",
                )
            }
            Spacer(Modifier.height(16.dp))
            StatLine("Shared storage free", formatBytes(shared.freeBytes))
            StatLine("Shared storage used", formatBytes(shared.usedBytes))
            StatLine("Internal (/data) free", formatBytes(internal.freeBytes))
        }

        Spacer(Modifier.height(20.dp))
        Text("Tools", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))

        ActionCard(
            icon = Icons.Rounded.DeleteSweep,
            tint = MaterialTheme.colorScheme.primary,
            title = "Wipe free space",
            subtitle = "Overwrite unallocated space so deleted files can't be recovered.",
            onClick = { onNavigate("wipe") },
        )
        Spacer(Modifier.height(12.dp))
        ActionCard(
            icon = Icons.Rounded.Shield,
            tint = MaterialTheme.colorScheme.secondary,
            title = "Shred files",
            subtitle = "Pick specific files, overwrite them, then delete.",
            onClick = { onNavigate("shred") },
        )
        Spacer(Modifier.height(12.dp))
        ActionCard(
            icon = Icons.Rounded.CleaningServices,
            tint = MaterialTheme.colorScheme.tertiary,
            title = "Clean junk",
            subtitle = "Clear caches and temp files, and see where space goes.",
            onClick = { onNavigate("clean") },
        )

        Spacer(Modifier.height(20.dp))
        InfoBanner(
            icon = Icons.Rounded.Info,
            text = "Honest note: on phone flash storage, overwriting free space defeats ordinary " +
                "recovery but can't guarantee every trace is gone. To dispose of a device, do a " +
                "factory reset (cryptographic erase). Tap the About tab for the full story.",
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ObliviateCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tint.copy(alpha = 0.15f),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = tint)
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
