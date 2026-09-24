package app.parley.ui.contact

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
import app.parley.common.EventDate
import app.parley.ui.PhotoCache
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "12 March 1990 · 35 years · in 6 days" for birthdays, shorter for other dates. */
fun describeEvent(raw: String, birthday: Boolean, today: LocalDate = LocalDate.now()): String {
    val e = EventDate.parse(raw) ?: return raw
    val y = e.year
    val shown = if (y != null) {
        LocalDate.of(y, e.month, e.day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
    } else {
        java.time.MonthDay.of(e.month, e.day).format(DateTimeFormatter.ofPattern("d MMMM"))
    }
    val days = e.daysUntil(today)
    val whenText = when (days) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $days days"
    }
    val age = if (birthday) e.age(today)?.let { "$it years" } else e.age(today)?.let { "$it years ago" }
    return listOfNotNull(shown, age, whenText).joinToString(" · ")
}

private val linkRegex = Regex(
    "(https?://[^\\s]+|www\\.[^\\s]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|\\+?[0-9][0-9 ()-]{6,}[0-9])",
)

/** Text with tappable web links, e-mail addresses and phone numbers (no extra library). */
@Composable
fun LinkifiedText(text: String, modifier: Modifier = Modifier) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
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
                pushLink(LinkAnnotation.Url(url, linkStyle))
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

/** Full-screen photo with pinch-zoom and pan; tap to close. */
@Composable
fun PhotoViewer(uri: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) { value = PhotoCache.load(context, uri, 1440)?.asImageBitmap() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }, onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) },
            contentAlignment = Alignment.Center,
        ) {
            image?.let {
                Image(
                    it, "Contact photo", contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().transformable(state).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            }
        }
    }
}
