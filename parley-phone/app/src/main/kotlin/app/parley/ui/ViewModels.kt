package app.parley.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.parley.container
import app.parley.data.DataContainer
import app.parley.ui.contact.ContactDetailViewModel
import app.parley.ui.contact.EditorViewModel
import app.parley.ui.home.KeypadViewModel
import app.parley.ui.home.RecentsViewModel

/**
 * The feature view models, built from the app's [DataContainer] (no DI framework). Screen view models (editor,
 * contact page) live with their navigation entry; Recents and Keypad are shared by the home surfaces and settings,
 * so they live with the activity ([activityViewModel]).
 */
object ParleyViewModels {
    private fun CreationExtras.data(): DataContainer = checkNotNull(this[APPLICATION_KEY]) { "No application in creation extras" }.container

    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { EditorViewModel(data(), createSavedStateHandle()) }
        initializer { ContactDetailViewModel(data()) }
        initializer { RecentsViewModel(data()) }
        initializer { KeypadViewModel(data()) }
    }
}

/** A feature view model for the current screen (its navigation entry). */
@Composable
inline fun <reified T : ViewModel> screenViewModel(): T = viewModel(factory = ParleyViewModels.Factory)

/** A feature view model shared across the activity (home tabs, surfaces and the settings that steer them). */
@Composable
inline fun <reified T : ViewModel> activityViewModel(): T {
    val owner = checkNotNull(LocalActivity.current as? ViewModelStoreOwner) { "Not inside a ComponentActivity" }
    return viewModel(owner, factory = ParleyViewModels.Factory)
}
