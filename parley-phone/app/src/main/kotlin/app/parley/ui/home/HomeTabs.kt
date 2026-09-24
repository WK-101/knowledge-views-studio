package app.parley.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.graphics.vector.ImageVector
import app.parley.common.StartTab

/** Name of a home tab, as shown in the bar, the header and Settings. */
val StartTab.label: String
    get() = when (this) {
        StartTab.FAVORITES -> "Favorites"
        StartTab.RECENTS -> "Recents"
        StartTab.CONTACTS -> "Contacts"
        StartTab.KEYPAD -> "Keypad"
    }

val StartTab.icon: ImageVector
    get() = when (this) {
        StartTab.FAVORITES -> Icons.Rounded.Star
        StartTab.RECENTS -> Icons.Rounded.AccessTime
        StartTab.CONTACTS -> Icons.Rounded.People
        StartTab.KEYPAD -> Icons.Rounded.Dialpad
    }
