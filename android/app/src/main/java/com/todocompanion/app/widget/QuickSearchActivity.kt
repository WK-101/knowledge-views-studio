package com.todocompanion.app.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.todocompanion.app.App
import com.todocompanion.app.MainActivity
import com.todocompanion.app.ui.AppViewModel
import com.todocompanion.app.ui.AppViewModelFactory
import com.todocompanion.app.ui.screens.SearchScreen
import com.todocompanion.app.ui.theme.AppTheme

/**
 * "Search everything without opening the whole app" popup — the Quick-bar widget's Search button. Hosts
 * the same whole-app [SearchScreen] (tasks, notes, habits, events, occasions, lists…) behind an
 * autofocused search field. Tapping a result jumps the app to that item; otherwise nothing leaves this
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
            val settings by androidx.compose.runtime.produceState(initialValue = com.todocompanion.app.domain.AppSettings()) {
                value = app.repository.settingsSnapshot()
            }
            AppTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor, accentArgb = settings.accentArgb) {
                val vm: AppViewModel = viewModel(factory = AppViewModelFactory(app))
                var query by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }

                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            placeholder = { Text("Search everything") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Filled.Search, null) },
                            trailingIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Close") } },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
