package app.parley.ui.calls

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.PhoneCallback
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.R
import app.parley.common.circle.GoodTime
import app.parley.common.circle.Occasion
import app.parley.common.calls.NextUp
import app.parley.common.ux.Tips
import app.parley.telecom.ui.RemindTimes
import app.parley.ui.Destination
import app.parley.ui.ParleyShapes
import app.parley.ui.Routes
import app.parley.ui.Spacing
import app.parley.ui.activityViewModel
import app.parley.ui.common.CoachMark
import app.parley.ui.home.RecentsViewModel
import app.parley.ui.people.PeopleRoutes
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Next up, the quiet strip at the top of Recents: at most three "now" lines ([NextUp]), each with its one action. Calls
 * owed now ("3 to call", with the names) open the To call list; a birthday or anniversary today and a Circle member in
 * their good hours open the contact and offer Call; a Chapter ending this week opens its label. Folded, it keeps the
 * first line only; it never shows a badge, and nothing at all when nothing is due.
 */
@Composable
fun NextUpStrip(vm: AppViewModel, open: (Destination) -> Unit) {
    val recents: RecentsViewModel = activityViewModel()
    val model = recents.toCall
    val items by recents.nextUp.items.collectAsStateWithLifecycle()
    val folded by model.folded.collectAsStateWithLifecycle()
    LaunchedEffect(model) { model.followCalls() }
    if (items.isEmpty()) return
    val label = stringResource(R.string.next_up_label)
    Column {
        CoachMark(Tips.TO_CALL, stringResource(R.string.to_call_tip))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = ParleyShapes.card,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs).animateContentSize()
                .semantics { paneTitle = label },
        ) {
            Column {
                (if (folded) items.take(1) else items).forEachIndexed { i, item ->
                    NextUpRow(vm, item, open, detail = !folded) {
                        // The fold sits on the first line, when folding hides something.
                        if (i == 0 && (items.size > 1 || hasDetail(item))) {
                            IconButton({ model.setFolded(!folded) }) {
                                Icon(
                                    if (folded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                                    stringResource(if (folded) R.string.to_call_unfold else R.string.action_fold),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hasDetail(item: NextUpItem): Boolean = item is NextUpItem.Owed || item is NextUpItem.GoodHours

@Composable
private fun NextUpRow(vm: AppViewModel, item: NextUpItem, open: (Destination) -> Unit, detail: Boolean, fold: @Composable () -> Unit) {
    val context = LocalContext.current
    when (item) {
        is NextUpItem.Owed -> {
            val names = item.rows.joinToString(", ") { titleOf(it) }
            val promised = item.promisedAt?.let { stringResource(R.string.to_call_promised_at, RemindTimes.whenText(context, it)) }
            Line(
                Icons.Rounded.PhoneCallback, pluralStringResource(R.plurals.to_call_strip, item.rows.size, item.rows.size),
                listOfNotNull(names.ifEmpty { null }, promised).takeIf { detail }.orEmpty(),
                onClickLabel = stringResource(R.string.label_to_call), onClick = { open(ToCallRoutes.List) }, trailing = fold,
            )
        }
        is NextUpItem.Later -> Line(
            Icons.Rounded.PhoneCallback, pluralStringResource(R.plurals.to_call_strip_later, item.count, item.count), emptyList(),
            onClickLabel = stringResource(R.string.label_to_call), onClick = { open(ToCallRoutes.List) }, trailing = fold,
        )
        is NextUpItem.Dated -> Line(
            Icons.Rounded.Cake,
            stringResource(if (item.occasion == Occasion.BIRTHDAY) R.string.next_up_birthday else R.string.next_up_anniversary, item.person.name),
            emptyList(), onClickLabel = null, onClick = { open(Routes.contact(item.person.contactId)) },
            trailing = { CallButton(vm, item.person); fold() },
        )
        is NextUpItem.GoodHours -> Line(
            Icons.Rounded.Schedule, stringResource(R.string.next_up_good_time, item.person.name),
            listOf(stringResource(R.string.circle_good_time, hours(context, item.window))).takeIf { detail }.orEmpty(),
            onClickLabel = null, onClick = { open(Routes.contact(item.person.contactId)) },
            trailing = { CallButton(vm, item.person); fold() },
        )
        is NextUpItem.ChapterEnds -> Line(
            Icons.Rounded.HourglassBottom, chapterText(context, item.title, item.lastDay), emptyList(),
            onClickLabel = null, onClick = { open(PeopleRoutes.Label(item.title)) }, trailing = fold,
        )
    }
}

@Composable
private fun CallButton(vm: AppViewModel, person: Person) {
    val number = person.number ?: return
    IconButton({ vm.requestCall(number, person.name) }) {
        Icon(Icons.Rounded.Call, stringResource(R.string.action_call_named, person.name), tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Line(
    icon: ImageVector, title: String, details: List<String>, onClickLabel: String?, onClick: () -> Unit, trailing: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = Spacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f).padding(vertical = Spacing.s)) {
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            details.forEach {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
        Spacer(Modifier.width(Spacing.xs))
    }
}

/** "6–9 pm", the hours they're usually free, in their time. */
private fun hours(context: Context, w: GoodTime.Window): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val hour = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "j"), locale)
    val day = LocalDate.now()
    return context.getString(R.string.circle_good_time_range, day.atTime(w.startHour, 0).format(hour), day.atTime(w.endOfDay, 0).format(hour))
}

/** "Exams ends today", "… tomorrow", "… on Friday". */
private fun chapterText(context: Context, title: String, lastDay: LocalDate): String {
    val today = LocalDate.now(ZoneId.systemDefault())
    return when (lastDay) {
        today -> context.getString(R.string.next_up_chapter_today, title)
        today.plusDays(1) -> context.getString(R.string.next_up_chapter_tomorrow, title)
        else -> context.getString(
            R.string.next_up_chapter_on, title,
            lastDay.dayOfWeek.getDisplayName(TextStyle.FULL, context.resources.configuration.locales[0] ?: Locale.getDefault()),
        )
    }
}
