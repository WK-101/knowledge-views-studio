package app.parley.common.record

/**
 * What kind of contacts account a raw contact's ACCOUNT_TYPE names, and whether Parley may write new contacts to it.
 *
 * - **Local**: the phone-only account. AOSP uses a null type; Samsung (One UI) and Xiaomi (MIUI) before Android 15,
 *   and a few other OEMs, fill in their own type instead (see contacts-android's AccountExtensions and
 *   "about-local-contacts"). Those raws never sync and are always writable.
 * - **SIM**: SIM-card storage exposed as an account (`vnd.sec.contact.sim`, `com.android.contacts.usim`…). Tiny,
 *   lossy (one name, one number) and managed by the SIM screens only, so never a save, import or move target.
 * - **Messenger**: WhatsApp, Signal, Telegram… raws. Owned by those apps' sync adapters: read-only for us.
 * - Anything else is writable only when its contacts sync adapter reports `supportsUploading()`.
 */
object AccountKinds {
    /** OEM phone-only account types (Samsung, Xiaomi, Huawei, Sony, HTC, older AOSP forks). */
    val OEM_LOCAL_TYPES: Set<String> = setOf(
        "vnd.sec.contact.phone",
        "com.android.contacts.default",
        "com.android.huawei.phone",
        "com.sonyericsson.localcontacts",
        "com.htc.android.pcsc",
        "com.android.localphone",
        "com.local.contacts",
    )

    /** Known SIM account types; any other type mentioning "sim" is treated as SIM storage too. */
    val SIM_TYPES: Set<String> = setOf(
        "vnd.sec.contact.sim", "vnd.sec.contact.sim2", "com.android.contacts.sim", "com.android.contacts.usim",
    )

    fun isLocalType(type: String?): Boolean = type == null || type in OEM_LOCAL_TYPES

    fun isSimType(type: String?): Boolean = type != null && (type in SIM_TYPES || type.lowercase().contains("sim"))

    fun isMessengerType(type: String?): Boolean = Messengers.isMessengerAccount(type)

    /**
     * Whether a raw contact in an account of [type] can be written by Parley: local accounts always; SIM and
     * messenger accounts never; others when their sync adapter uploads ([uploadingTypes] from
     * `ContentResolver.getSyncAdapterTypes()` filtered on the contacts authority and `supportsUploading()`).
     * [platformLocalType] is Android 15's `RawContacts.getLocalAccountType()`, when there is one.
     */
    fun isWritable(type: String?, uploadingTypes: Set<String>, platformLocalType: String? = null): Boolean = when {
        isLocalType(type) || (platformLocalType != null && type == platformLocalType) -> true
        isSimType(type) || isMessengerType(type) -> false
        else -> type in uploadingTypes
    }

    /**
     * Accounts offered as save, import, move and restore targets: the writable ones among [candidates], the device
     * account first, without duplicates. Falls back to [device] when nothing else qualifies, so there is always
     * somewhere to save.
     */
    fun <A> writableTargets(
        candidates: List<A>,
        typeOf: (A) -> String?,
        uploadingTypes: Set<String>,
        device: A,
        platformLocalType: String? = null,
    ): List<A> {
        val out = LinkedHashSet<A>()
        out += device
        candidates.filter { isWritable(typeOf(it), uploadingTypes, platformLocalType) }.forEach { out += it }
        return out.toList()
    }
}
