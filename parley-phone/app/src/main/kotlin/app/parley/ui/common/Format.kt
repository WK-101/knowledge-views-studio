package app.parley.ui.common

import android.content.Context
import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import app.parley.R
import java.util.Calendar

object Format {
    fun number(raw: String, countryIso: String): String {
        if (raw.isBlank()) return raw
        return PhoneNumberUtils.formatNumber(raw, countryIso) ?: raw
    }

    fun phoneType(res: Resources, type: Int, label: String?): String = Phone.getTypeLabel(res, type, label).toString()

    fun emailType(res: Resources, type: Int, label: String?): String = Email.getTypeLabel(res, type, label).toString()

    fun time(context: Context, millis: Long): String = DateFormat.getTimeFormat(context).format(millis)

    /** "14:05", "Yesterday", "Mon", "12 Mar" */
    fun shortWhen(context: Context, millis: Long): String = when {
        DateUtils.isToday(millis) -> time(context, millis)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.main_yesterday)
        System.currentTimeMillis() - millis < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    fun dayHeader(context: Context, millis: Long): String = when {
        DateUtils.isToday(millis) -> context.getString(R.string.main_today)
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> context.getString(R.string.main_yesterday)
        else -> {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { timeInMillis = millis }
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)) DateUtils.FORMAT_NO_YEAR else 0
            DateUtils.formatDateTime(context, millis, flags)
        }
    }

    fun fullDate(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)

    /** "45s", "3m 5s", "1h 2m" in the user's language (ICU narrow units, so no resources are needed). */
    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val fmt = android.icu.text.MeasureFormat.getInstance(java.util.Locale.getDefault(), android.icu.text.MeasureFormat.FormatWidth.NARROW)
        fun m(n: Long, u: android.icu.util.MeasureUnit) = android.icu.util.Measure(n, u)
        return when {
            sec < 60 -> fmt.formatMeasures(m(sec, android.icu.util.MeasureUnit.SECOND))
            sec < 3600 -> fmt.formatMeasures(m(sec / 60, android.icu.util.MeasureUnit.MINUTE), m(sec % 60, android.icu.util.MeasureUnit.SECOND))
            else -> fmt.formatMeasures(m(sec / 3600, android.icu.util.MeasureUnit.HOUR), m((sec % 3600) / 60, android.icu.util.MeasureUnit.MINUTE))
        }
    }
}
