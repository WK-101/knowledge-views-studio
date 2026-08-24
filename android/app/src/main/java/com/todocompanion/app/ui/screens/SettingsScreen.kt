package com.todocompanion.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.todocompanion.app.domain.FirstView
import com.todocompanion.app.domain.ThemeMode
import com.todocompanion.app.ui.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsState()
    val context = LocalContext.current

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) vm.exportTo(uri) { ok ->
            Toast.makeText(context, if (ok) "Exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFrom(uri) { ok ->
            Toast.makeText(context, if (ok) "Imported" else "Import failed", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        SectionLabel("First view")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = settings.firstView == FirstView.MATRIX,
                onClick = { vm.saveSettings(settings.copy(firstView = FirstView.MATRIX)) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("Matrix") }
            SegmentedButton(
                selected = settings.firstView == FirstView.CALENDAR,
                onClick = { vm.saveSettings(settings.copy(firstView = FirstView.CALENDAR)) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("Calendar") }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Theme")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = settings.themeMode == mode,
                    onClick = { vm.saveSettings(settings.copy(themeMode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }

        Spacer(Modifier.height(12.dp))
        ToggleRow(
            title = "Dynamic color (Material You)",
            checked = settings.dynamicColor,
            onCheckedChange = { vm.saveSettings(settings.copy(dynamicColor = it)) },
        )
        ToggleRow(
            title = "Advanced priority (importance + urgency)",
            subtitle = "Show two 1–5 dials and the computed Do-Next ranking",
            checked = settings.advancedPriority,
            onCheckedChange = { vm.saveSettings(settings.copy(advancedPriority = it)) },
        )

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        SectionLabel("Backup")
        ActionRow("Export all data (JSON)") { exportLauncher.launch("todo-companion-backup.json") }
        ActionRow("Import / restore (JSON)") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }
        Text(
            "Complete, lossless local backup. No account, no cloud, no network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "ToDo Companion — offline & private by construction (no network permission).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(title: String, onClick: () -> Unit) {
    Text(
        title,
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
