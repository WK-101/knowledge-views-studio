package com.wkhan.hexis.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wkhan.hexis.App
import com.wkhan.hexis.MainActivity
import com.wkhan.hexis.ui.AppViewModel
import com.wkhan.hexis.ui.AppViewModelFactory
import com.wkhan.hexis.ui.screens.SearchScreen
import com.wkhan.hexis.ui.theme.AppTheme

/**
 * "Search everything without opening the whole app" popup — the Quick-bar widget's Search button. A
 * bottom sheet that sits directly above the keyboard (with a tap-away gap at the top for the notch),
 * hosting the same whole-app [SearchScreen] (tasks, notes, habits, events, occasions, lists…) behind an
 * autofocused field. Tapping a result jumps the app to that item; otherwise nothing leaves this
 * lightweight window. Fully offline; reads the local DB only.
 */
class QuickSearchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext as App

        fun openApp(action: String?) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
            })
            finish()
        }

        setContent {
            val settings by androidx.compose.runtime.produceState(initialValue = com.wkhan.hexis.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app))
                var query by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }

                // Scrim: the top gap (above the sheet) taps away.
                Box(
                    Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding()
                            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                    ) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            OutlinedTextField(
                                value = query, onValueChange = { query = it },
                                placeholder = { Text("Search everything") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                trailingIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Close") } },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                            SearchScreen(
                                vm = vm, onOpenTask = { openApp("open_task:$it") }, query = query,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                onOpenHabit = { openApp("open_habits") },
                                onOpenEvent = { openApp("open_calendar") },
                                onOpenOccasion = { openApp("open_countdowns") },
                                onOpenNote = { openApp("open_note:$it") },
                                onOpenActivity = { openApp("open_time") },
                                onOpenNotebook = { openApp(null) },
                                onOpenListFolder = { _, _ -> openApp(null) },
                                onOpenGoal = { openApp("open_goals") },
                                onOpenRoutine = { openApp(null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
