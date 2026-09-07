package com.obliviate.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.obliviate.app.ui.components.ObliviateCard

@Composable
fun AboutScreen() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "The honest truth about wiping storage",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "We'd rather tell you exactly what this does than sell you a false sense of security.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        Section(
            icon = Icons.Rounded.Shield,
            title = "What Obliviate does",
            body = "When you delete a file, Android only removes the pointer to it — the actual bytes " +
                "linger until something overwrites them, which is why \"undelete\" tools work. " +
                "Obliviate fills the free space with random data, forces it to the storage chip, then " +
                "releases it. That overwrites the leftovers of previously-deleted files, so ordinary " +
                "software recovery finds nothing.",
        )

        Section(
            icon = Icons.Rounded.Memory,
            title = "Why it's best-effort on a phone",
            body = "Phones use NAND flash with a controller that does wear-leveling: it deliberately " +
                "spreads writes across different physical cells and keeps hidden spare cells. So an " +
                "overwrite may not land on the exact cells that held your old data, and some remnants " +
                "can survive in areas no app can reach. This is a physical property of flash — every " +
                "free-space wiper on an unrooted phone has the same ceiling. Anyone promising " +
                "\"guaranteed, unrecoverable\" erasure of free space on flash is overstating it.",
        )

        Section(
            icon = Icons.Rounded.Lock,
            title = "What an app is allowed to touch",
            body = "Since Android 10/11, \"scoped storage\" limits apps to their own space and the media " +
                "you explicitly pick. No normal app can reach the raw storage chip, other apps' private " +
                "data, or system partitions — that needs root. Obliviate works within these limits and " +
                "never asks for more access than a feature needs.",
        )

        Section(
            icon = Icons.Rounded.VerifiedUser,
            title = "The genuinely secure way to erase a device",
            body = "Android encrypts your data by default. A factory reset performs a \"cryptographic " +
                "erase\": it destroys the encryption key, making everything mathematically unreadable " +
                "regardless of what's left in the flash cells. NIST SP 800-88 treats this key-destruction " +
                "as the reliable method for disposing of a device. Recommended routine: (1) run a " +
                "free-space wipe for hygiene, then (2) factory reset before selling or recycling. " +
                "The guided \"Prepare for disposal\" flow on the Home screen walks you through it.",
        )

        Spacer(Modifier.height(8.dp))
        ObliviateCard {
            Text("Methods & standards", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "• Random (1 pass) — recommended; defeats software recovery quickly.\n" +
                    "• Zero fill (1 pass) — fastest, least flash wear.\n" +
                    "• DoD 5220.22-M (3 passes) — historic standard for magnetic disks; on flash the " +
                    "extra passes add wear without meaningful benefit.\n\n" +
                    "Multiple passes were meaningful on old spinning hard drives. On modern flash, one " +
                    "pass plus the physics above is the practical reality.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Obliviate • v1.1.0 — a transparent, non-root storage hygiene tool.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, body: String) {
    ObliviateCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(14.dp))
}
