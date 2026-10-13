package app.parley.ui.home

import androidx.compose.material3.TopAppBar
import app.parley.ui.OverflowMenu
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import app.parley.R
import app.parley.common.catching
import app.parley.common.ux.Tips
import app.parley.ui.ParleyType
import app.parley.ui.common.CoachMarkAnchor
import app.parley.ui.ParleyTopBar
import app.parley.ui.BackButton
import app.parley.ui.ParleyShapes
import app.parley.ui.BackPreview
import app.parley.ui.Spacing
import app.parley.ui.ParleyMotion
import app.parley.ui.backPreview

/**
 * The header of every home tab: the tab's title, its own actions, a search icon that turns the bar into a search
 * field (back arrow closes it, the field gets the keyboard), and the shared "More options" menu.
 *
 * It is a [TopAppBar], so it leaves room for the status bar and display cutout, and its colour follows the
 * list's scroll position ([scrollBehavior]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeHeader(
    title: String,
    searching: Boolean,
    query: String,
    searchHint: String,
    onQuery: (String) -> Unit,
    onSearch: (Boolean) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    /** The back swipe that closes the search, which the field follows. */
    backPreview: BackPreview? = null,
    actions: @Composable RowScope.() -> Unit = {},
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val slide = ParleyMotion.spatial<IntOffset>()
    val fadeIn = fadeIn(ParleyMotion.effects())
    val fadeOut = fadeOut(ParleyMotion.fastEffects())
    AnimatedContent(
        searching,
        transitionSpec = {
            // The search comes in from the end side and leaves towards it: the right, or the left in Arabic and Urdu.
            if (targetState) (fadeIn + slideIntoContainer(SlideDirection.Start, slide) { it / 8 }) togetherWith fadeOut
            else fadeIn togetherWith (fadeOut + slideOutOfContainer(SlideDirection.End, slide) { it / 8 })
        },
        label = "home-header",
    ) { s ->
        if (s) {
            SearchBarHeader(query, searchHint, onQuery, onClose = { onSearch(false) }, scrollBehavior = scrollBehavior, backPreview = backPreview)
        } else {
            var menuOpen by rememberSaveable { mutableStateOf(false) }
            ParleyTopBar(
                title = { Text(title, style = ParleyType.homeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    // A one-time tip under the search icon.
                    CoachMarkAnchor(Tips.HEADER_SEARCH, stringResource(R.string.ux_tip_search)) {
                        IconButton({ onSearch(true) }) { Icon(Icons.Rounded.Search, searchHint) }
                    }
                    actions()
                    OverflowMenu(menuOpen, { menuOpen = it }) { close -> menu(close) }
                },
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBarHeader(
    query: String, hint: String, onQuery: (String) -> Unit, onClose: () -> Unit, scrollBehavior: TopAppBarScrollBehavior, backPreview: BackPreview?,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { catching { focus.requestFocus() } }
    ParleyTopBar(
        navigationIcon = { BackButton(onClose, stringResource(R.string.action_close_search)) },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    // Clearing an empty field closes the search, like the back arrow.
                    IconButton({ if (query.isEmpty()) onClose() else onQuery("") }) {
                        Icon(Icons.Rounded.Close, stringResource(if (query.isEmpty()) R.string.action_close_search else R.string.action_clear_search))
                    }
                },
                shape = ParleyShapes.pill,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.s)
                    .then(if (backPreview != null) Modifier.backPreview(backPreview, rounded = false) else Modifier)
                    .focusRequester(focus),
            )
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(),
    )
}
