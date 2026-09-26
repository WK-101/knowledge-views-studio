package com.wkhan.hexis.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.wkhan.hexis.App
import com.wkhan.hexis.data.AppRepository

/**
 * A2 (Phase 3, decomposition) — the single composition root that wires [AppViewModel]'s dependencies.
 *
 * Before this, production obtained the VM through Compose's reflective default `AndroidViewModel` factory,
 * which invoked a secondary `AppViewModel(app)` constructor that reached back into the [App] service
 * locator (`(app as App).repository`) from *inside* the ViewModel. That coupling — the 6.6k-line god-VM
 * knowing how to find its own repository — is exactly what blocks carving per-feature ViewModels out of it:
 * a sub-VM can't be constructed with a test double while the class hard-wires its own dependency lookup.
 *
 * Moving the lookup here means the VM only ever *receives* its collaborators. This factory is the one place
 * future collaborators (injected controllers, sub-VM providers) get supplied — one composition root instead
 * of scattered `App` casts. Production calls [AppViewModelFactory(app)]; the [repo] param defaults to the
 * locator's repository but is overridable, so a test can wire an isolated in-memory-backed repository
 * through the exact same production path (see AppViewModelFactoryTest) without booting the real database.
 */
class AppViewModelFactory(
    private val app: App,
    private val repo: AppRepository = app.repository,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AppViewModel::class.java)) {
            "AppViewModelFactory creates AppViewModel, not ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(app, repo) as T
    }
}
