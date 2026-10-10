package app.parley.common.testing

import app.parley.common.CallEntry
import app.parley.common.CallType
import app.parley.common.ContactSummary
import app.parley.common.PhoneEntry

/**
 * Test data shared by every module's tests (core/common's test fixtures), so a field added to a model changes one
 * builder instead of a copy in each test. Arguments come in the model's own order, each with a plain default.
 */
fun testCall(
    id: Long = 0,
    number: String = "+15550100",
    cachedName: String? = null,
    type: CallType = CallType.INCOMING,
    date: Long = 0,
    durationSec: Long = 0,
    accountId: String? = null,
    isNew: Boolean = false,
    presentationHidden: Boolean = false,
    video: Boolean = false,
) = CallEntry(id, number, cachedName, type, date, durationSec, accountId, isNew, presentationHidden, video)

/** A contact in the list with [numbers] as mobile numbers. */
fun testContact(id: Long, name: String, key: String = "k$id", vararg numbers: String) =
    ContactSummary(id, key, name, null, false, numbers.map { PhoneEntry(it, MOBILE, null) })

private const val MOBILE = 2
