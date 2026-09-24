package app.parley.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.ui.Avatar
import app.parley.ui.EmptyState
import app.parley.ui.Routes

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesTab(vm: AppViewModel, open: (String) -> Unit) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val frequents by vm.frequents.collectAsStateWithLifecycle()
    if (favorites.isEmpty() && frequents.isEmpty()) {
        EmptyState(Icons.Rounded.StarOutline, "No favorites yet", "Star a contact to keep it here. Tap a favorite to call, long-press to open it.")
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(favorites, key = { "f" + it.id }) { c ->
            Tile(c.displayName, c.photoUri, onClick = {
                c.phones.firstOrNull()?.let { p -> vm.requestCall(p.number, c.displayName) } ?: open(Routes.contact(c.id))
            }, onLong = { open(Routes.contact(c.id)) })
        }
        if (frequents.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Frequent", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp))
            }
            items(frequents, key = { "q" + it.key }) { g ->
                Tile(g.title, g.contact?.photoUri, onClick = { vm.requestCall(g.number, g.contact?.displayName) }, onLong = {
                    g.contact?.let { open(Routes.contact(it.id)) } ?: open(Routes.history(g.number))
                })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(name: String, photo: String?, onClick: () -> Unit, onLong: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).combinedClickable(onClick = onClick, onLongClick = onLong, onClickLabel = "Call", onLongClickLabel = "Open contact")
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(name, photo, 72.dp)
        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}
