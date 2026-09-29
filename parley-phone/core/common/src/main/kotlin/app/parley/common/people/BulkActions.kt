package app.parley.common.people

/**
 * The Contacts tab's actions on several selected contacts, device and private mixed (docs/CONTACT_MODEL.md). Most work
 * the same on both. The few that hand the contacts to another app or put them where other apps can read them act on
 * the device contacts only, and say how many private ones they left out.
 */
enum class BulkAction(
    /** Would copy a private contact out of Parley: done for device contacts only. */
    val deviceOnly: Boolean = false,
) {
    STAR, ADD_TO_LABEL, MESSAGE_ALL, DELETE, MAKE_PRIVATE, MAKE_VISIBLE, MAKE_TEMPORARY,

    /** Sends your own card to each of them: like messaging them, nothing of theirs leaves Parley. */
    INTRODUCE,

    /** A vCard file for another app. */
    SHARE(deviceOnly = true),

    /** A vCard file in a folder other apps can open. */
    EXPORT(deviceOnly = true),

    /** Any app can read the clipboard (keyboards, clipboard managers). */
    COPY_AS_TEXT(deviceOnly = true),

    /** Joins them into one address-book contact. */
    MERGE(deviceOnly = true),
}

object BulkActions {
    /** Which of the selected ids [action] acts on, and how many private ones it leaves out. */
    data class Targets(val ids: List<Long>, val skippedPrivate: Int)

    fun isPrivate(id: Long): Boolean = ContactRef.ofNavId(id) is ContactRef.Private

    fun targets(action: BulkAction, selected: Collection<Long>): Targets = when {
        action.deviceOnly -> selected.partition { !isPrivate(it) }.let { (device, private) -> Targets(device, private.size) }
        // Only a device contact can be made private, and only a private one visible.
        action == BulkAction.MAKE_PRIVATE -> selected.partition { !isPrivate(it) }.let { (device, _) -> Targets(device, 0) }
        action == BulkAction.MAKE_VISIBLE -> Targets(selected.filter { isPrivate(it) }, 0)
        else -> Targets(selected.toList(), 0)
    }

    /** Whether [action] has anything to act on in [selected] (merge needs two device contacts). */
    fun available(action: BulkAction, selected: Collection<Long>): Boolean {
        val t = targets(action, selected)
        return if (action == BulkAction.MERGE) t.ids.size >= 2 else t.ids.isNotEmpty()
    }
}
