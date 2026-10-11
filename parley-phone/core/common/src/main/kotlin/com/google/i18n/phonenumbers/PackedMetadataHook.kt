package com.google.i18n.phonenumbers

import com.google.i18n.phonenumbers.internal.RegexBasedMatcher
import com.google.i18n.phonenumbers.metadata.init.MetadataParser
import com.google.i18n.phonenumbers.metadata.source.MultiFileModeFileNameProvider
import com.google.i18n.phonenumbers.metadata.source.RegionMetadataSourceImpl

/**
 * The two places libphonenumber reads its metadata through a fixed class-path loader, pointed at Parley's packed
 * copy ([app.parley.common.phone.PhoneData]). It sits in libphonenumber's package because both hooks are
 * package-level there: [PhoneNumberUtil.setInstance], so the library's own users of [PhoneNumberUtil.getInstance]
 * (as-you-type formatting, time zones, short numbers) read the same packed copy, and the [ShortNumberInfo]
 * constructor that takes a metadata source. A library update that changes either fails the build here.
 */
object PackedMetadataHook {
    private const val SHORT_NUMBERS = "/com/google/i18n/phonenumbers/data/ShortNumberMetadataProto"

    /** Makes [util] (built by the public [PhoneNumberUtil.createInstance]) the one [PhoneNumberUtil.getInstance] returns. */
    fun install(util: PhoneNumberUtil) = PhoneNumberUtil.setInstance(util)

    /** Short-number facts (emergency and service numbers) read through [loader]. */
    fun shortNumbers(loader: MetadataLoader): ShortNumberInfo = ShortNumberInfo(
        RegexBasedMatcher.create(),
        RegionMetadataSourceImpl(MultiFileModeFileNameProvider(SHORT_NUMBERS), loader, MetadataParser.newLenientParser()),
    )
}
