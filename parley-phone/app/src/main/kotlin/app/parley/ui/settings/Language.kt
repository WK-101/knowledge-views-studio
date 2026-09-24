package app.parley.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.ui.AppLocale
import java.util.Locale

/**
 * Settings › Appearance › Language (L1). Android 13+ opens the system's per-app language screen (the list comes from
 * the generated locale config); if a device doesn't offer that screen, and on Android 10–12, a small in-app
 * picker sets it through [AppLocale].
 */
@Composable
fun LanguageRow() {
    val context = LocalContext.current
    var picker by remember { mutableStateOf(false) }
    val current = AppLocale.current(context)
    LinkRow(
        stringResource(R.string.lang_title),
        current?.let(::displayName) ?: stringResource(R.string.lang_system),
        Icons.Rounded.Language,
        external = Build.VERSION.SDK_INT >= 33,
    ) {
        if (Build.VERSION.SDK_INT < 33 || !openSystemSettings(context)) picker = true
    }
    if (picker) LanguagePickerDialog(current) { picker = false }
}

private fun openSystemSettings(context: Context): Boolean = runCatching {
    context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}.isSuccess

/** "Deutsch", "العربية", "Português (Brasil)": each language in its own words. */
private fun displayName(locale: Locale): String = locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun LanguagePickerDialog(current: Locale?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val options = listOf<Pair<String?, String>>(null to stringResource(R.string.lang_system)) +
        AppLocale.supported.map { it to displayName(Locale.forLanguageTag(it)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lang_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (tag, label) ->
                    val selected = if (tag == null) current == null else current != null && Locale.forLanguageTag(tag).let {
                        it.language == current.language && (it.country.isEmpty() || it.country == current.country)
                    }
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { RadioButton(selected, null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            onDismiss()
                            context.activity()?.let { AppLocale.set(it, tag) }
                        },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.main_cancel)) } },
    )
}
