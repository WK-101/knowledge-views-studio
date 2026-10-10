// The page and its destination live together.
@file:Suppress("MatchingDeclarationName")

package app.parley.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.calls.MenuMemory
import app.parley.ui.Destination
import app.parley.ui.SegmentedGroup
import app.parley.ui.SettingsScaffold
import app.parley.ui.Spacing
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Settings › Calls pages of their own that aren't a feature's graph. */
object CallsRoutes {
    /** Menu memory's switch, a page of its own under Calls › Situations. */
    @Serializable data object PhoneMenus : Destination

    /** One of Calls' own pages ([CallsSubPage] by name); [focus] is a setting to scroll to and highlight (from search). */
    @Serializable data class Page(val page: String, val focus: String? = null) : Destination
}

/**
 * Settings › Calls › Phone menus: "Remember menu keys", on by default with the conservative guard
 * ([MenuMemory.secretStartOf]). Turning it off forgets every remembered path at once; saved shortcuts and the
 * per-number "Don't remember" choices stay.
 */
@Composable
internal fun PhoneMenusScreen(vm: AppViewModel, back: () -> Unit) {
    val cfg by vm.c.callExtras.config.collectAsStateWithLifecycle()
    SettingsScaffold(settingTitle("phone_menus"), back) {
        Text(
            stringResource(R.string.phone_menus_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.s),
        )
        SegmentedGroup {
            switchRow("menu_memory", cfg.rememberMenuKeys, Icons.Rounded.History) { on ->
                vm.c.callExtras.update { it.copy(rememberMenuKeys = on) }
                if (!on) vm.viewModelScope.launch { runCatching { vm.c.menus.update(MenuMemory::forgetPaths) } }
            }
        }
    }
}
