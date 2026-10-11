package app.parley.ui.contact

import app.parley.ui.qr.WebAddressSheet
import android.content.res.Resources
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.EventDate
import app.parley.ui.PhotoCache
import app.parley.ui.common.ExportableImage
import app.parley.ui.common.ImageViewerBar
import app.parley.ui.common.rememberImageActions
import java.time.LocalDate
import java.time.MonthDay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "12 March 1990 · 35 years · in 6 days" for birthdays, shorter for other dates. With [res] the words are in the
 * user's language; without, English. The parts are always joined with " · ".
 */
fun describeEvent(raw: String, birthday: Boolean, today: LocalDate = LocalDate.now(), res: Resources? = null): String {
    val e = EventDate.parse(raw) ?: return raw
    val y = e.year
    val shown = if (y != null) {
        LocalDate.of(y, e.month, e.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    } else {
        MonthDay.of(e.month, e.day).format(DateTimeFormatter.ofPattern("d MMMM"))
    }
    val days = e.daysUntil(today)
    val whenText = when {
        res != null -> when (days) {
            0L -> res.getString(R.string.life_today)
            1L -> res.getString(R.string.life_tomorrow)
            else -> res.getQuantityString(R.plurals.event_in_days, days.toInt(), days.toInt())
        }
        days == 0L -> "today"
        days == 1L -> "tomorrow"
        else -> "in $days days"
    }
    val age = e.age(today)?.let { a ->
        when {
            res != null -> res.getQuantityString(if (birthday) R.plurals.event_years else R.plurals.event_years_ago, a, a)
            birthday -> "$a years"
            else -> "$a years ago"
        }
    }
    return listOfNotNull(shown, age, whenText).joinToString(" · ")
}

private val linkRegex = Regex(
    "(https?://[^\\s]+|www\\.[^\\s]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\+?[0-9][0-9 ()-]{6,}[0-9])",
)

/**
 * Text with tappable web links, e-mail addresses and phone numbers (no extra library). Notes often come from
 * imported vCards or scanned codes, so a web link opens the same checked "web address" sheet as a scanned one
 * (owning domain, look-alike and shortener warnings) instead of the browser directly.
 */
@Composable
fun LinkifiedText(text: String, modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    var checking by remember { mutableStateOf<String?>(null) }
    checking?.let { WebAddressSheet(it) { checking = null } }
    val annotated = remember(text, linkStyle) {
        buildAnnotatedString {
            var last = 0
            for (m in linkRegex.findAll(text)) {
                append(text.substring(last, m.range.first))
                val v = m.value.trimEnd('.', ',', ')', ';')
                val url = when {
                    v.contains('@') && !v.startsWith("http") -> "mailto:$v"
                    v.startsWith("http") -> v
                    v.startsWith("www.") -> "https://$v"
                    else -> "tel:" + v.filter { it.isDigit() || it == '+' }
                }
                if (url.startsWith("http")) {
                    pushLink(LinkAnnotation.Clickable(url, linkStyle) { checking = url })
                } else {
                    pushLink(LinkAnnotation.Url(url, linkStyle))
                }
                append(v)
                pop()
                append(m.value.substring(v.length))
                last = m.range.last + 1
            }
            append(text.substring(last))
        }
    }
    Text(annotated, modifier)
}

/**
 * Full-screen picture with pinch-zoom and pan; tap to close. With [export], Save and Share at the bottom
 * ([ImageViewerBar]): a contact photo without a kept original, or the call-screen picture.
 */
@Composable
fun PhotoViewer(vm: AppViewModel, uri: String, export: ExportableImage?, description: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val actions = rememberImageActions(vm, export)
    val image by produceState<ImageBitmap?>(null, uri) { value = PhotoCache.load(context, uri, 1440)?.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onTap = { onDismiss() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                },
                contentAlignment = Alignment.Center,
            ) {
                image?.let {
                    Image(
                        it, description, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().transformable(state).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    )
                }
            }
            // Beside the picture, not in it: a tap on the bar's note or veil doesn't close the viewer.
            ImageViewerBar(actions, export, Modifier.align(Alignment.BottomCenter))
        }
    }
}
