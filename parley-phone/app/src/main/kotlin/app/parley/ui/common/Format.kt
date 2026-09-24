package app.parley.ui.common

import android.content.Context
import android.content.res.Resources
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
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
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        System.currentTimeMillis() - millis < 6 * DateUtils.DAY_IN_MILLIS ->
            DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
        else -> DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    fun dayHeader(context: Context, millis: Long): String = when {
        DateUtils.isToday(millis) -> "Today"
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
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

    fun duration(sec: Long): String = when {
        sec <= 0 -> ""
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
    }
}
