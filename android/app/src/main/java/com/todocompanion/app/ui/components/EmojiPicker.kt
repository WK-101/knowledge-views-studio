package com.todocompanion.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A comprehensive emoji picker: category tabs + a scrollable grid of hundreds of emoji, plus a
 * free-type field for anything not listed. Used for folder/list icons. Entirely offline — the
 * glyphs come from the system font, nothing is fetched.
 */
@Composable
fun EmojiGridPicker(current: String?, onPick: (String?) -> Unit) {
    var cat by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        // Category tabs.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EMOJI_CATEGORIES.forEachIndexed { i, c ->
                val sel = i == cat
                Box(
                    Modifier.clip(RoundedCornerShape(9.dp))
                        .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { cat = i }.padding(horizontal = 8.dp, vertical = 6.dp),
                ) { Text(c.tab, style = MaterialTheme.typography.titleMedium) }
            }
        }
        Spacer(Modifier.height(6.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 220.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (current == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                    .clickable { typed = ""; onPick(null) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Block, "No icon", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(EMOJI_CATEGORIES[cat].emojis) { e ->
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (current == e) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { typed = e; onPick(e) }, contentAlignment = Alignment.Center) {
                    Text(e, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        com.todocompanion.app.ui.components.AppTextField(
            value = typed,
            onValueChange = { v -> typed = v; onPick(v.trim().ifBlank { null }) },
            singleLine = true,
            label = { Text("Or type any emoji") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private class EmojiCategory(val tab: String, val emojis: List<String>)

private val EMOJI_CATEGORIES = listOf(
    EmojiCategory("⭐", listOf("⭐","🎯","🔥","✅","❤️","💡","📌","🚀","🏆","💎","🎁","🔑","💰","📈","🧠","💪","👍","🙌","✨","⚡","🌟","💯","📝","🗒️","🔔","⏰","📅","🗓️","🕐","♻️")),
    EmojiCategory("📁", listOf("📁","📂","🗂️","📥","📤","📦","🗃️","🗄️","📇","📋","📎","🖇️","✂️","📐","📏","🔖","🏷️","📒","📓","📔","📕","📗","📘","📙","📚","📖","🔍","🔎","🖊️","🖋️","✏️","📝","🖌️","🖍️")),
    EmojiCategory("💼", listOf("💼","🏢","🏦","🏛️","🏠","🏡","🏫","🏬","🏭","🏥","💻","🖥️","⌨️","🖱️","🖨️","📱","☎️","📞","📠","📧","📨","💵","💴","💶","💷","🪙","💳","🧾","📊","📈","📉","🗳️","⚖️")),
    EmojiCategory("😀", listOf("😀","😃","😄","😁","😆","😅","😂","🙂","🙃","😉","😊","😍","🥰","😘","😎","🤩","🤔","🤨","😐","😴","😪","😷","🤒","🤕","🥳","😇","🤗","🤯","😱","😭","😤","😡","🥺","😌")),
    EmojiCategory("🐾", listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🐦","🐤","🦆","🦉","🦄","🐝","🦋","🐌","🐞","🐢","🐍","🐙","🐳","🐬","🐟","🌱","🌳","🌲","🌵","🌸","🌻","🌹","🍀")),
    EmojiCategory("🍔", listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🥑","🥦","🥕","🌽","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🍜","🍝","🍣","🍱","🍩","🍪","🎂","🍰","☕","🍵","🍺","🍷")),
    EmojiCategory("⚽", listOf("⚽","🏀","🏈","⚾","🎾","🏐","🏉","🎱","🏓","🏸","🥅","🏒","🏑","🥍","🏏","🥊","🥋","⛳","⛸️","🎿","🛷","🏂","🏋️","🤸","🤾","🏃","🚴","🧗","🏊","🎮","🎲","🎧","🎸","🎹","🎺","🎨","🎬","🎤","🎯","🎳")),
    EmojiCategory("✈️", listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🚚","🚛","🚜","🛵","🏍️","🚲","🛴","✈️","🚀","🛸","🚁","⛵","🚤","🛳️","⚓","🚂","🚆","🚊","🗺️","🧭","🏝️","🏖️","⛰️","🏔️","🌋","🗽","🗼","🏰","⛩️","🎡")),
    EmojiCategory("🩺", listOf("🩺","💊","💉","🦷","🧬","🔬","🧪","🌡️","🩹","🧻","🚽","🚿","🛁","🧼","🧴","🛒","🧹","🧺","🔨","🪚","🔧","🪛","🔩","⚙️","🧰","🪝","🔗","⛓️","🔒","🔓","🔐","🗝️","🛠️","⚗️")),
    EmojiCategory("❤️", listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓")),
)
