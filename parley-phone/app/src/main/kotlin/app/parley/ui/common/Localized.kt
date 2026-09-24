package app.parley.ui.common

import android.content.res.Resources
import app.parley.R
import app.parley.common.vcard.ImportReport

/** [ImportReport.summary] in the user's language (core/common has no Android resources). */
fun ImportReport.localizedSummary(res: Resources): String = buildList {
    add(res.getString(R.string.import_summary_imported, imported, cardsParsed + cardsFailed))
    if (skippedDuplicates > 0) add(res.getQuantityString(R.plurals.import_summary_duplicates, skippedDuplicates, skippedDuplicates))
    if (cardsFailed > 0) add(res.getQuantityString(R.plurals.import_summary_failed, cardsFailed, cardsFailed))
    val unmapped = unmappedProperties.values.sum()
    if (unmapped > 0) add(res.getQuantityString(R.plurals.import_summary_unmapped, unmapped, unmapped))
}.joinToString(res.getString(R.string.main_separator))
