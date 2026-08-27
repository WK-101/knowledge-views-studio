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
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    // When searching by keyword ("dog", "money", "health"…) we show cross-category matches; an empty query
    // shows the selected category. A typed emoji is accepted directly (search that IS an emoji picks it).
    val searchResults = remember(q) { if (q.isBlank()) emptyList() else searchEmoji(q) }
    val showing = if (q.isBlank()) EMOJI_CATEGORIES[cat].emojis else searchResults
    Column(Modifier.fillMaxWidth()) {
        // Search across every category by keyword; also accepts a pasted/typed emoji as the pick.
        com.todocompanion.app.ui.components.AppTextField(
            value = query,
            onValueChange = { v ->
                query = v
                // If what they typed is itself an emoji (not a keyword we index), take it as the choice.
                val t = v.trim()
                if (t.isNotEmpty() && searchEmoji(t.lowercase()).isEmpty() && t.none { it.isLetterOrDigit() }) onPick(t)
            },
            singleLine = true,
            label = { Text("Search emoji (e.g. dog, money, health) or type one") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        // Category tabs (hidden while searching).
        if (q.isBlank()) {
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
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(40.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp, max = 240.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (q.isBlank()) item {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (current == null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f))
                    .clickable { query = ""; onPick(null) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Block, "No icon", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(showing) { e ->
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (current == e) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onPick(e) }, contentAlignment = Alignment.Center) {
                    Text(e, style = MaterialTheme.typography.titleLarge)
                }
            }
            if (showing.isEmpty()) item { Text("No match — type the emoji itself", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Keyword → emoji search over a curated index (plus each emoji's category keywords), so typing a concept
 *  finds a glyph even though emoji carry no searchable text of their own. Substring match on keywords. */
private fun searchEmoji(q: String): List<String> {
    if (q.isBlank()) return emptyList()
    val out = LinkedHashSet<String>()
    EMOJI_KEYWORDS.forEach { (kw, emojis) -> if (kw.contains(q)) out.addAll(emojis) }
    return out.toList()
}

private class EmojiCategory(val tab: String, val emojis: List<String>)

private val EMOJI_CATEGORIES = listOf(
    EmojiCategory("⭐", listOf("⭐","🌟","✨","💫","🎯","🏹","🔥","✅","☑️","✔️","❤️","💡","📌","📍","🚀","🏆","🥇","🎖️","💎","🎁","🔑","💰","📈","🧠","💪","👍","🙌","👏","🤝","✊","🙏","✍️","💯","🆕","🆗","‼️","⁉️","❓","❗","⚡","🌈","🎉","🎊","🔝","🔜","♻️")),
    EmojiCategory("📁", listOf("📁","📂","🗂️","📥","📤","📦","🗃️","🗄️","📇","📋","📌","📎","🖇️","✂️","📐","📏","🔖","🏷️","📒","📓","📔","📕","📗","📘","📙","📚","📖","📃","📄","📑","🗞️","📰","🔍","🔎","🖊️","🖋️","✏️","📝","🖌️","🖍️","🧮","📅","🗓️","📆")),
    EmojiCategory("💼", listOf("💼","👔","🏢","🏦","🏛️","🏠","🏡","🏫","🏬","🏭","🏥","🏨","🏪","💻","🖥️","⌨️","🖱️","🖨️","📱","☎️","📞","📠","📧","📨","✉️","📬","💵","💴","💶","💷","🪙","💳","🧾","💹","📊","📈","📉","🗳️","⚖️","🖇️","📇","🗒️","🔐","🪪","📛")),
    EmojiCategory("😀", listOf("😀","😃","😄","😁","😆","😅","😂","🤣","🙂","🙃","😉","😊","😍","🥰","😘","😗","😙","😚","😋","😛","😜","🤪","😝","🤑","🤗","🤭","🤫","🤔","🤨","😐","😑","😶","😏","😒","🙄","😬","😮‍💨","😌","😔","😪","😴","😷","🤒","🤕","🤢","🤮","🥵","🥶","😵","🤯","🤠","🥳","😎","🤓","🧐","😕","😟","🙁","😮","😯","😲","😳","🥺","😦","😧","😨","😰","😥","😢","😭","😱","😖","😣","😞","😓","😩","😫","🥱","😤","😡","😠","🤬","😈","👿","💀","💩","🤡","👻","👽","🤖","😇")),
    EmojiCategory("🐾", listOf("🐶","🐕","🐩","🐺","🦊","🦝","🐱","🐈","🦁","🐯","🐅","🐆","🐴","🐎","🦄","🦓","🦌","🐮","🐂","🐷","🐗","🐭","🐹","🐰","🐇","🐿️","🦔","🦇","🐻","🐨","🐼","🦥","🦦","🐒","🦍","🐔","🐓","🐣","🐤","🐥","🐦","🐧","🕊️","🦆","🦅","🦉","🦚","🦜","🐝","🐛","🦋","🐌","🐞","🐜","🕷️","🐢","🐍","🦎","🐙","🦑","🦐","🦀","🐠","🐟","🐬","🐳","🐋","🦈","🐊","🐘","🦏","🐫","🦒","🐾")),
    EmojiCategory("🍔", listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🍑","🥭","🍍","🥥","🥝","🍅","🍆","🥑","🥦","🥬","🥒","🌶️","🌽","🥕","🧄","🧅","🥔","🍠","🥐","🥯","🍞","🥖","🧀","🥚","🍳","🥞","🧇","🥓","🍔","🍟","🍕","🌭","🥪","🌮","🌯","🥙","🧆","🥘","🍲","🍜","🍝","🍣","🍱","🍛","🍚","🍙","🍤","🥟","🍢","🍡","🍧","🍨","🍦","🥧","🧁","🍰","🎂","🍮","🍭","🍬","🍫","🍩","🍪","🌰","🥜","🍯","🥛","☕","🍵","🧃","🥤","🧋","🍺","🍻","🍷","🍸","🍹","🥂","🥃","🍾")),
    EmojiCategory("⚽", listOf("⚽","🏀","🏈","⚾","🥎","🎾","🏐","🏉","🥏","🎱","🪀","🏓","🏸","🥅","🏒","🏑","🥍","🏏","🥊","🥋","⛳","⛸️","🎣","🤿","🎽","🎿","🛷","🥌","🏂","🏋️","🤸","🤺","🤾","⛹️","🤼","🏇","🧘","🏃","🚶","🧗","🏄","🏊","🚣","🚴","🚵","🎮","🕹️","🎲","🧩","🎯","🎳","🎰","🎨","🎭","🩰","🎬","🎤","🎧","🎼","🎹","🥁","🎷","🎺","🎸","🪕","🎻")),
    EmojiCategory("✈️", listOf("🚗","🚕","🚙","🚌","🚎","🏎️","🚓","🚑","🚒","🚐","🛻","🚚","🚛","🚜","🛵","🏍️","🛺","🚲","🛴","🛹","🚏","🛣️","🛤️","⛽","🚦","🚥","✈️","🛫","🛬","🛩️","💺","🚀","🛸","🚁","🪂","⛵","🚤","🛥️","🛳️","⚓","🚢","🚂","🚆","🚇","🚊","🚉","🗺️","🧭","🏔️","⛰️","🌋","🏕️","🏝️","🏖️","🏜️","🏞️","🏟️","🗽","🗼","🏰","🏯","⛩️","🕌","⛪","🛕","🏛️","🎡","🎢","🎠","⛲","🌁","🌃","🌆","🌇","🌉")),
    EmojiCategory("🩺", listOf("🩺","💊","💉","🩸","🦷","🧬","🔬","🔭","🧫","🧪","🌡️","🩹","🩼","🦽","🦯","🧻","🚽","🚿","🛁","🧼","🧴","🪥","🧽","🧹","🧺","🧷","🪒","🛒","🔨","🪓","⛏️","⚒️","🛠️","🗡️","🔩","⚙️","🪝","🧰","🧲","🪜","🔧","🪛","🔗","⛓️","🪑","🚪","🛋️","🛏️","🖼️","🪞","🕯️","💡","🔦","🔌","🔋","🧯","🛢️","🔒","🔓","🔐","🗝️","🧸","🎈","🎀","🪄","🔮","📿","🧿")),
    EmojiCategory("❤️", listOf("❤️","🩷","🧡","💛","💚","💙","🩵","💜","🖤","🤍","🤎","💔","❣️","💕","💞","💓","💗","💖","💘","💝","💟","♥️","☮️","✝️","☪️","🕉️","☸️","✡️","🔯","🕎","☯️","☦️","🛐","⛎","♈","♉","♊","♋","♌","♍","♎","♏","♐","♑","♒","♓")),
    EmojiCategory("☀️", listOf("☀️","🌤️","⛅","🌥️","☁️","🌦️","🌧️","⛈️","🌩️","🌨️","❄️","☃️","⛄","🌬️","💨","🌪️","🌫️","🌊","💧","💦","☔","🌈","🌂","⚡","🔥","🌙","🌛","🌜","🌚","🌝","🌞","⭐","🌟","💫","✨","☄️","🪐","🌍","🌎","🌏","🌡️","🌅","🌄","🌇","🌆")),
    EmojiCategory("🔣", listOf("✅","❌","⭕","🚫","❎","✔️","☑️","➕","➖","➗","✖️","🟰","♾️","❓","❔","❗","❕","‼️","⁉️","💲","💱","©️","®️","™️","🔴","🟠","🟡","🟢","🔵","🟣","⚫","⚪","🟤","🔺","🔻","🔸","🔹","🔶","🔷","⬆️","↗️","➡️","↘️","⬇️","↙️","⬅️","↖️","↕️","↔️","🔁","🔄","🔃","▶️","⏸️","⏹️","⏭️","🔟","#️⃣","*️⃣")),
)

/** Curated keyword → emoji index for search. Keys are lowercase concept words; a query substring-matches
 *  a key. Kept broad but compact — covers the common searches across tasks, habits and time activities. */
private val EMOJI_KEYWORDS: Map<String, List<String>> = mapOf(
    "star favourite important" to listOf("⭐","🌟","✨","💫"),
    "target goal aim focus" to listOf("🎯","🏹"),
    "fire streak hot" to listOf("🔥"),
    "check done complete tick" to listOf("✅","☑️","✔️"),
    "idea light bulb" to listOf("💡"),
    "pin marker" to listOf("📌","📍"),
    "rocket launch start ship" to listOf("🚀"),
    "trophy win award" to listOf("🏆","🥇","🎖️"),
    "gift present reward" to listOf("🎁"),
    "key" to listOf("🔑","🗝️"),
    "money cash finance budget dollar bank pay" to listOf("💰","💵","💴","💶","💷","🪙","💳","🧾","🏦"),
    "chart graph stats growth analytics" to listOf("📈","📉","📊","🗳️"),
    "brain mind think" to listOf("🧠","🤔"),
    "muscle strong gym workout exercise fitness" to listOf("💪","🏋️","🤸","🏃","🚴","🧗","🏊"),
    "run running jog" to listOf("🏃","👟"),
    "sleep bed rest night" to listOf("😴","😪","🛏️","🌙"),
    "note write writing journal diary" to listOf("📝","🗒️","📒","📓","📔","🖊️","✏️"),
    "book read reading study learn" to listOf("📚","📖","📕","📗","📘","📙"),
    "bell reminder alarm alert" to listOf("🔔","⏰","🔕"),
    "clock time timer" to listOf("⏰","🕐","⏱️","⌚","🕰️"),
    "calendar date schedule plan" to listOf("📅","🗓️","📆"),
    "folder file organize" to listOf("📁","📂","🗂️","🗃️","🗄️"),
    "work office job business briefcase" to listOf("💼","🏢","🏭","👔"),
    "home house" to listOf("🏠","🏡"),
    "school study class education" to listOf("🏫","🎓","📚"),
    "computer laptop code work pc" to listOf("💻","🖥️","⌨️","🖱️"),
    "phone call mobile" to listOf("📱","☎️","📞"),
    "email mail message" to listOf("📧","📨","✉️","📬"),
    "happy smile joy face" to listOf("😀","😃","😄","😁","🙂","😊","🥳"),
    "love heart" to listOf("❤️","🧡","💛","💚","💙","💜","🤍","💕","💖"),
    "sad cry down" to listOf("😭","😢","😞","🥺"),
    "angry mad" to listOf("😡","😤","😠"),
    "cool sunglasses" to listOf("😎"),
    "dog puppy pet" to listOf("🐶","🐕"),
    "cat kitten" to listOf("🐱","🐈"),
    "animal pet" to listOf("🐶","🐱","🐰","🦊","🐻","🐼"),
    "bird" to listOf("🐦","🐧","🐤","🦆","🦉"),
    "plant tree nature grow garden green" to listOf("🌱","🌳","🌲","🌵","🍀","🌿"),
    "flower bloom" to listOf("🌸","🌻","🌹","🌷"),
    "water drink hydrate" to listOf("💧","🚰","🥤","🧊"),
    "coffee tea drink" to listOf("☕","🍵"),
    "food eat meal" to listOf("🍔","🍕","🍜","🍱","🥪","🍣","🥗"),
    "fruit apple healthy" to listOf("🍎","🍏","🍌","🍓","🍊","🥑","🥕","🥦"),
    "cook cooking kitchen" to listOf("🍳","🔪","🍽️"),
    "sport ball game play" to listOf("⚽","🏀","🏈","⚾","🎾","🏐"),
    "music song play guitar" to listOf("🎵","🎶","🎸","🎹","🎧","🎤"),
    "game gaming controller" to listOf("🎮","🎲","🕹️"),
    "art paint draw creative" to listOf("🎨","🖌️","🖍️"),
    "movie film video camera" to listOf("🎬","📹","📽️","🎥"),
    "photo camera picture" to listOf("📷","📸"),
    "travel trip vacation plane fly" to listOf("✈️","🧳","🗺️","🏝️","🏖️"),
    "car drive commute" to listOf("🚗","🚙","🚕"),
    "bike cycle" to listOf("🚲","🚴","🛴"),
    "health medical doctor medicine pill" to listOf("🩺","💊","💉","🌡️","🩹"),
    "tooth teeth dental brush" to listOf("🦷","🪥"),
    "clean cleaning tidy chores" to listOf("🧹","🧺","🧼","🧽","🚿"),
    "shop shopping buy cart grocery" to listOf("🛒","🛍️"),
    "tool fix repair build diy" to listOf("🔨","🔧","🪛","🛠️","⚙️","🔩"),
    "lock secure private security" to listOf("🔒","🔐","🔓"),
    "meditate calm zen mindful yoga peace relax" to listOf("🧘","☮️","☯️","🕉️"),
    "sun sunny weather morning" to listOf("☀️","🌤️","🌅"),
    "moon night evening" to listOf("🌙","🌛","🌜"),
    "party celebrate birthday" to listOf("🥳","🎉","🎊","🎂"),
    "prayer religion faith" to listOf("🙏","✝️","☪️","🕉️","✡️","☸️"),
    "flag priority mark" to listOf("🚩","🏳️","🏁"),
    "warning caution alert" to listOf("⚠️","❗","🚨"),
    "recycle eco green sustainable" to listOf("♻️","🌍","🌱"),
    "sign language talk speak chat" to listOf("💬","🗨️","🗣️"),
    "hand wave hi hello" to listOf("👋","🙌","👍"),
    "weather sky cloud" to listOf("☀️","⛅","☁️","🌧️","⛈️","❄️","🌈","🌪️"),
    "rain umbrella wet" to listOf("🌧️","☔","🌂","💧"),
    "snow cold winter ice" to listOf("❄️","☃️","⛄","🧊"),
    "storm thunder lightning" to listOf("⛈️","🌩️","⚡"),
    "sunny hot summer" to listOf("☀️","🌞","🔥"),
    "space star planet galaxy" to listOf("🪐","🌌","☄️","🌠","🚀","🛸"),
    "earth world globe planet" to listOf("🌍","🌎","🌏"),
    "arrow direction next" to listOf("➡️","⬅️","⬆️","⬇️","↗️","↘️","🔁","🔄"),
    "cross x cancel wrong no" to listOf("❌","⛔","🚫","❎"),
    "plus add new" to listOf("➕","🆕"),
    "circle dot color" to listOf("🔴","🟠","🟡","🟢","🔵","🟣","⚫","⚪"),
    "number count math" to listOf("🔢","🧮","➕","➖","✖️","➗"),
    "question help ask" to listOf("❓","❔","🤔"),
    "shield protect defense guard" to listOf("🛡️","⚔️","🔰"),
    "trash delete bin remove waste" to listOf("🗑️","🚮","♻️"),
    "gift reward prize present" to listOf("🎁","🏆","🥇","🎖️"),
    "celebrate party confetti festival" to listOf("🎉","🎊","🥳","🎈","🎂"),
    "clean laundry wash dishes" to listOf("🧺","🧼","🧽","🧴","🚿","🛁"),
    "cook bake oven recipe chef" to listOf("🍳","🥘","🍲","👨‍🍳","🔪"),
    "baby kid child family" to listOf("👶","🧒","👨‍👩‍👧","🍼"),
    "pray meditate spiritual worship" to listOf("🙏","🧘","🕉️","☸️","🛐"),
    "gym lift weights strength" to listOf("🏋️","💪","🤸"),
    "walk hike steps outdoors" to listOf("🚶","🥾","🧗","🏔️"),
    "swim pool water sport" to listOf("🏊","🤿","🥽"),
    "yoga stretch flexibility calm" to listOf("🧘","🤸","☯️"),
    "code program dev software bug" to listOf("💻","⌨️","🐛","🖥️","👨‍💻"),
    "design creative art draw paint" to listOf("🎨","🖌️","✏️","🖍️","🖊️"),
    "write journal note pen" to listOf("✍️","📝","🖊️","📔","📓"),
    "shopping buy store market cart" to listOf("🛒","🛍️","🏪","🏬"),
    "medicine pill health doctor hospital" to listOf("💊","🩺","💉","🏥","🩹"),
    "sleep bed rest tired" to listOf("😴","🛏️","🌙","💤"),
    "eat food hungry meal restaurant" to listOf("🍽️","🍔","🍕","🍜","🥗"),
    "coffee caffeine morning brew" to listOf("☕","🫖","🍵"),
    "flag goal finish milestone" to listOf("🚩","🏁","🏳️","🎌"),
    "lock privacy secure password key" to listOf("🔒","🔐","🔑","🗝️","🛡️"),
    "wrench tool repair fix maintenance" to listOf("🔧","🔨","🛠️","🪛","⚙️"),
    "plant garden grow seed nature" to listOf("🌱","🪴","🌿","🌳","🌷"),
    "pet dog cat animal" to listOf("🐶","🐱","🐾","🐰","🐹"),
    "bird fly wing" to listOf("🐦","🕊️","🦅","🦉"),
    "fish sea ocean marine" to listOf("🐟","🐠","🐬","🦈","🌊"),
    "car drive road trip travel" to listOf("🚗","🚙","🛣️","🚦"),
    "flight plane airport travel" to listOf("✈️","🛫","🛬","🧳"),
    "train rail metro subway" to listOf("🚂","🚆","🚇","🚊"),
    "map location place navigate" to listOf("🗺️","🧭","📍","📌"),
    "building city urban tower" to listOf("🏢","🏬","🏙️","🌆"),
    "sun weather morning day" to listOf("☀️","🌅","🌞"),
    "cool awesome great fun" to listOf("😎","🤩","🔥","💯"),
    "danger warning hazard stop" to listOf("⚠️","🚨","⛔","☢️","☣️"),
    "time clock deadline hour" to listOf("⏰","⏱️","⏲️","🕐","⌛","⏳"),
)
