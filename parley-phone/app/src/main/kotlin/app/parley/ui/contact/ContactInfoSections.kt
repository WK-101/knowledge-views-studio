package app.parley.ui.contact

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.parley.AppViewModel
import app.parley.NavEvent
import app.parley.R
import app.parley.common.MessengerApp
import app.parley.common.PhoneIdentity
import app.parley.common.ReachGroup
import app.parley.common.ReachGroups
import app.parley.common.StartTab
import app.parley.common.people.ContactSection
import app.parley.common.people.SocialProfiles
import app.parley.common.ux.Tips
import app.parley.data.DataItem
import app.parley.data.PhoneEnv
import app.parley.ui.Bidi
import app.parley.ui.common.CoachMark
import app.parley.ui.common.Format
import app.parley.ui.common.Intents

/**
 * The "Contact info" family: numbers (each with the apps that reach it), emails, addresses, then what's left of
 * "Message or call on…" (typed-in handles, apps on numbers not saved here) and the profiles.
 */
@Composable
internal fun ContactInfoSections(sections: PageSections, ctx: ContactPageContext) {
    val context = LocalContext.current
    val region = PhoneEnv.countryIso(context)
    val sameLine: (String, String) -> Boolean = { a, b -> PhoneIdentity.same(a, b, region) }
    // Messenger rows grouped per app and number (Reach via apps).
    val reachGroups = remember(ctx.ui.messengers) { ctx.reach.groups(region) }
    PhoneSection(sections, ctx, reachGroups, sameLine)
    EmailSection(sections, ctx)
    AddressSection(sections, ctx)
    MessengerSection(sections, ctx, reachGroups, sameLine)
    // Profiles (Instagram, LinkedIn…): website rows that name a service, each opened in its app or the browser.
    val d = ctx.d
    val profiles = remember(d.websites) {
        d.websites.mapNotNull { w -> SocialProfiles.fromWebsite(w.value, w.type, w.label)?.takeIf { it.handle.isNotBlank() } }
    }
    if (profiles.isNotEmpty()) {
        val summary = profiles.map { it.service.label }.distinct().joinToString(", ")
        sections.addRows(ContactSection.PROFILES, sectionTitle(LocalResources.current, ContactSection.PROFILES), summary) { profileRows(profiles) }
    }
}

@Composable
private fun PhoneSection(sections: PageSections, ctx: ContactPageContext, reachGroups: List<ReachGroup>, sameLine: (String, String) -> Boolean) {
    val d = ctx.d
    if (d.phones.isEmpty()) return
    val vm = ctx.vm
    val resources = LocalResources.current
    val context = LocalContext.current
    val sims by vm.sims.collectAsStateWithLifecycle()
    val advice by ctx.page.numberAdvice.collectAsStateWithLifecycle()
    val sep = stringResource(R.string.main_separator)
    val prefs = ctx.prefs
    val summary = if (d.phones.size == 1) {
        Bidi.ltr(Format.number(d.phones[0].value, vm.countryIso))
    } else {
        resources.getQuantityString(R.plurals.contact_page_count_numbers, d.phones.size, d.phones.size)
    }
    sections.addRows(ContactSection.PHONES, sectionTitle(resources, ContactSection.PHONES), summary) {
        d.phones.forEachIndexed { i, p ->
            item {
                val pinned = ctx.ui.simPrefs.firstOrNull { PhoneIdentity.matchesStored(it.matchKey, p.value, vm.countryIso) }?.phoneAccountId
                val apps = remember(reachGroups, p.value) { ReachGroups.appNamesFor(reachGroups, p.value, sameLine) }
                val label = listOfNotNull(
                    Format.phoneType(resources, p.type, p.label).ifBlank { null },
                    resources.getString(R.string.contact_page_default).takeIf { p.isPrimary && d.phones.size > 1 },
                    pinned?.let { id -> sims.firstOrNull { it.id == id }?.label?.let { resources.getString(R.string.detail_always_sim, it) } },
                    apps.takeIf { it.isNotEmpty() }?.joinToString(resources.getString(R.string.contact_page_list_separator)),
                    // A quiet word only: what to do about it is in the Contact health check.
                    resources.getString(R.string.number_seems_out_of_service).takeIf { p.value in advice.dead },
                ).joinToString(sep)
                PhoneRow(
                    vm, p, first = i == 0, label = label,
                    canDefault = d.phones.size > 1 && p.id != null,
                    multiSim = sims.size > 1,
                    hasApps = apps.isNotEmpty(),
                    // Its icon on the Message button only while it's there to open (else a text is sent).
                    usualApp = remember(prefs.message) {
                        prefs.message?.let(MessengerApp::forPackage)?.takeIf { it.packageName in ContactMessaging.installed(context) }
                    },
                    onCall = { ctx.callPeek(p.value, d.displayName) },
                    onMessage = { ctx.messageNumber(p.value) },
                    onMessageOn = { ctx.show(ContactDialog.MessageOn(p.value)) },
                    onSim = { ctx.show(ContactDialog.SimFor(p.value)) },
                    onDefault = { on -> ctx.page.setDefault(p, Phone.CONTENT_ITEM_TYPE, on) },
                )
            }
        }
    }
}

@Composable
private fun EmailSection(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    if (d.emails.isEmpty()) return
    val resources = LocalResources.current
    val context = LocalContext.current
    val sep = stringResource(R.string.main_separator)
    val summary = if (d.emails.size == 1) d.emails[0].value else resources.getQuantityString(R.plurals.contact_page_count_emails, d.emails.size, d.emails.size)
    sections.addRows(ContactSection.EMAILS, sectionTitle(resources, ContactSection.EMAILS), summary) {
        d.emails.forEachIndexed { i, e ->
            item {
                val label = listOfNotNull(
                    Format.emailType(resources, e.type, e.label).ifBlank { null },
                    resources.getString(R.string.contact_page_default).takeIf { e.isPrimary && d.emails.size > 1 },
                ).joinToString(sep)
                GroupDataRow(
                    Icons.Rounded.Email, i == 0, e.value, label, onClick = { Intents.email(context, e.value) },
                    menu = if (d.emails.size > 1 && e.id != null) {
                        { close -> DefaultMenuItem(e.isPrimary) { on -> close(); ctx.page.setDefault(e, Email.CONTENT_ITEM_TYPE, on) } }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
private fun AddressSection(sections: PageSections, ctx: ContactPageContext) {
    val d = ctx.d
    if (d.addresses.isEmpty()) return
    val resources = LocalResources.current
    // An address's map link opens from the address itself (and isn't listed again as a website).
    val mapLinks = remember(d.addresses, d.websites) { AddressMapLinks.matches(d) }
    val summary = if (d.addresses.size == 1) {
        d.addresses[0].formatted.lines().joinToString(", ") { it.trim() }
    } else {
        resources.getQuantityString(R.plurals.contact_page_count_addresses, d.addresses.size, d.addresses.size)
    }
    sections.addRows(ContactSection.ADDRESSES, sectionTitle(resources, ContactSection.ADDRESSES), summary) {
        d.addresses.forEachIndexed { i, a ->
            val link = mapLinks[i]?.let { d.websites.getOrNull(it)?.value }
            item { AddressDetailRow(a, i == 0, StructuredPostal.getTypeLabel(resources, a.type, a.label).toString(), link) }
        }
    }
}

@Composable
private fun MessengerSection(
    sections: PageSections,
    ctx: ContactPageContext,
    reachGroups: List<ReachGroup>,
    sameLine: (String, String) -> Boolean,
) {
    val d = ctx.d
    val resources = LocalResources.current
    val context = LocalContext.current
    // Apps on a saved number show on that number's row; only the others get rows of their own here.
    val looseApps = remember(reachGroups, d.phones) { ReachGroups.notOnNumbers(reachGroups, d.phones.map { it.value }, sameLine) }
    val handles = d.handles.filter { it.value.isNotBlank() }
    if (handles.isEmpty() && looseApps.isEmpty()) return
    val apps = looseApps.map { it.appLabel }.distinct()
    val summary = if (apps.isNotEmpty()) {
        apps.joinToString(", ")
    } else {
        resources.getQuantityString(R.plurals.contact_page_count_items, handles.size, handles.size)
    }
    val prefs = ctx.prefs
    sections.addRows(
        ContactSection.MESSENGERS, sectionTitle(resources, ContactSection.MESSENGERS), summary,
        after = { CoachMark(Tips.REACH_USUAL, stringResource(R.string.reach_reach_hint), enabled = looseApps.isNotEmpty()) },
    ) {
        // Handles typed into the contact (Matrix, Threema, Signal username…).
        handleRows(handles, Icons.Rounded.Forum, onWeb = { ctx.show(ContactDialog.WebLink(it)) })
        // Each remaining app's Message / Voice / Video for this person (long-press: make it the usual way).
        reachViaAppsRows(
            looseApps, prefs, showNumbers = true,
            onOpen = { row -> ctx.reach.action(row)?.let { m -> ContactMessaging.startRow(context, ctx.reach, m)?.let { ctx.vm.toast(it) } } },
            onToggleUsual = { row -> ctx.page.setMessengerPrefs(prefs.toggleUsual(row)) },
        )
    }
}

@Composable
private fun DefaultMenuItem(isDefault: Boolean, onSet: (Boolean) -> Unit) {
    DropdownMenuItem(
        { Text(stringResource(if (isDefault) R.string.detail_remove_default else R.string.detail_set_default)) },
        // The icon shows the current state, like the star on the number itself.
        leadingIcon = { Icon(if (isDefault) Icons.Rounded.Star else Icons.Rounded.StarOutline, null) },
        onClick = { onSet(!isDefault) },
    )
}

/**
 * One number: tap calls. Trailing, each doing one thing: "Message or call on…" (the apps grid, only when apps reach
 * this number; their names are in the supporting line) opens the sheet of every way to reach it, and Message sends a
 * message to this number straight away: with the contact's usual chat app ([usualApp], whose icon it then shows), or
 * as a text. Long-press: copy, default, message or call on…, edit before calling, SIM.
 *
 * Google Contacts shows only a message icon on a number; Parley keeps the second button only where apps reach the
 * number, because that's where the sheet has more to offer than a text (calls and chats in those apps).
 */
@Composable
private fun PhoneRow(
    vm: AppViewModel,
    p: DataItem,
    first: Boolean,
    label: String,
    canDefault: Boolean,
    multiSim: Boolean,
    hasApps: Boolean,
    usualApp: MessengerApp?,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onMessageOn: () -> Unit,
    onSim: () -> Unit,
    onDefault: (Boolean) -> Unit,
) {
    val shown = Bidi.ltr(Format.number(p.value, vm.countryIso))
    GroupDataRow(
        Icons.Rounded.Call, first, p.value, label, onClick = onCall,
        headline = { Text(shown) },
        trailing = {
            if (hasApps) IconButton(onMessageOn) { Icon(Icons.Rounded.Apps, stringResource(R.string.detail_reach_number, shown)) }
            IconButton(onMessage) {
                if (usualApp != null) {
                    UsualAppIcon(usualApp, stringResource(R.string.detail_message_number_on, shown, usualApp.label))
                } else {
                    Icon(Icons.AutoMirrored.Rounded.Chat, stringResource(R.string.detail_text_number, shown))
                }
            }
        },
        menu = { close ->
            if (canDefault) DefaultMenuItem(p.isPrimary) { on -> close(); onDefault(on) }
            DropdownMenuItem(
                { Text(stringResource(R.string.reach_message_or_call_on)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Message, null) },
                onClick = { close(); onMessageOn() },
            )
            DropdownMenuItem({ Text(stringResource(R.string.detail_edit_before_call)) }, leadingIcon = { Icon(Icons.Rounded.Dialpad, null) }, onClick = {
                close(); vm.navigate(NavEvent.Tab(StartTab.KEYPAD, dial = p.value))
            })
            if (multiSim) {
                DropdownMenuItem(
                    { Text(stringResource(R.string.detail_choose_sim)) },
                    leadingIcon = { Icon(Icons.Rounded.SimCard, null) },
                    onClick = { close(); onSim() },
                )
            }
        },
    )
}

/** The usual chat app's own icon on a number's Message button (the chat bubble when it can't be read). */
@Composable
private fun UsualAppIcon(app: MessengerApp, description: String) {
    val context = LocalContext.current
    val bmp = remember(app.packageName) {
        runCatching { context.packageManager.getApplicationIcon(app.packageName).toBitmap(72, 72).asImageBitmap() }.getOrNull()
    }
    if (bmp != null) Image(bmp, description, Modifier.size(24.dp)) else Icon(Icons.AutoMirrored.Rounded.Chat, description)
}
