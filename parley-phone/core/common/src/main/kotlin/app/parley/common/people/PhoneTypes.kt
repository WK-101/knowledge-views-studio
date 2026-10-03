package app.parley.common.people

/**
 * The phone types the editor's type selector offers, as Android's Phone.TYPE_* codes: the six people use most first,
 * then Android's other fourteen behind "More types". Codes are duplicated here (they never change) so the order is
 * testable without Android.
 */
object PhoneTypes {
    const val HOME = 1
    const val MOBILE = 2
    const val WORK = 3
    const val FAX_WORK = 4
    const val FAX_HOME = 5
    const val PAGER = 6
    const val OTHER = 7
    const val CALLBACK = 8
    const val CAR = 9
    const val COMPANY_MAIN = 10
    const val ISDN = 11
    const val MAIN = 12
    const val OTHER_FAX = 13
    const val RADIO = 14
    const val TELEX = 15
    const val TTY_TDD = 16
    const val WORK_MOBILE = 17
    const val WORK_PAGER = 18
    const val ASSISTANT = 19
    const val MMS = 20

    /** Shown first in the menu. */
    val common: List<Int> = listOf(MOBILE, HOME, WORK, MAIN, FAX_WORK, OTHER)

    /** Behind "More types", the likelier ones first. */
    val more: List<Int> = listOf(
        WORK_MOBILE, PAGER, WORK_PAGER, ASSISTANT, COMPANY_MAIN, CALLBACK, CAR, FAX_HOME, OTHER_FAX, ISDN, RADIO, TELEX, TTY_TDD, MMS,
    )

    /** Every Android phone type, once. */
    val all: List<Int> = common + more
}
