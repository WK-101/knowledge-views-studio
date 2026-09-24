package app.parley.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.parley.R
import app.parley.common.StartTab

/** Name of a home tab, as shown in the bar, the header and Settings. */
val StartTab.label: String
    @Composable get() = stringResource(labelRes)

/** The string resource of [label], for code outside composition. */
val StartTab.labelRes: Int
    get() = when (this) {
        StartTab.FAVORITES -> R.string.tab_favorites
        StartTab.RECENTS -> R.string.tab_recents
        StartTab.CONTACTS -> R.string.tab_contacts
        StartTab.KEYPAD -> R.string.tab_keypad
    }

val StartTab.icon: ImageVector
    get() = when (this) {
        StartTab.FAVORITES -> Icons.Rounded.Star
        StartTab.RECENTS -> Icons.Rounded.AccessTime
        StartTab.CONTACTS -> Icons.Rounded.People
        StartTab.KEYPAD -> Icons.Rounded.Dialpad
    }
