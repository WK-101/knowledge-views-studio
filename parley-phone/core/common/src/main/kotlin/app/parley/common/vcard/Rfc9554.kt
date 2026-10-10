package app.parley.common.vcard

import com.github.mangstadt.vinnie.io.VObjectPropertyValues
import ezvcard.VCard
import ezvcard.VCardDataType
import ezvcard.VCardVersion
import ezvcard.io.ParseContext
import ezvcard.io.scribe.AddressScribe
import ezvcard.io.scribe.StructuredNameScribe
import ezvcard.io.scribe.VCardPropertyScribe
import ezvcard.io.text.WriteContext
import ezvcard.parameter.VCardParameters
import ezvcard.property.Address
import ezvcard.property.StructuredName
import ezvcard.property.VCardProperty

/**
 * RFC 9554 gave vCard's N two more components (secondary surname, generation) and ADR eleven (room … direction).
 * ez-vcard 0.12 reads and writes only RFC 6350's, so these scribes keep the rest: when reading, the extra components
 * ride on the property as the in-memory parameter [PARTS] (one value per component, in order); when writing, a
 * property that carries [PARTS] gets them appended, and the parameter itself is never written. Every reader and
 * writer of Parley's vCards registers them ([register]).
 */
object Rfc9554 {
    /** Not an X-PARLEY- parameter: those are read back as data columns. */
    const val PARTS = "X-RFC9554-PARTS"
    const val NAME_PARTS = 2
    const val ADDRESS_PARTS = 11

    private const val NAME_BASE = 5
    private const val ADDRESS_BASE = 7

    /** The extra components [p] carries (empty when none). */
    fun parts(p: VCardProperty): List<String> = p.parameters.get(PARTS).orEmpty()

    /** Sets [p]'s extra components; all-empty clears them. */
    fun setParts(p: VCardProperty, parts: List<String>) {
        p.parameters.removeAll(PARTS)
        if (parts.any { it.isNotEmpty() }) parts.forEach { p.parameters.put(PARTS, it) }
    }

    val scribes: List<VCardPropertyScribe<out VCardProperty>> get() = listOf(NameScribe(), AdrScribe())

    /**
     * Puts the components [from] until [count] of a structured value (each list joined back with commas) on
     * [parameters]: ez-vcard gives a parsed property the parameters it read, replacing any the scribe set.
     */
    internal fun readParts(value: String, from: Int, count: Int, parameters: VCardParameters) {
        val all = VObjectPropertyValues.parseStructured(value)
        val parts = (from until from + count).map { all.getOrNull(it).orEmpty().joinToString(",") }
        parameters.removeAll(PARTS)
        if (parts.any { it.isNotEmpty() }) parts.forEach { parameters.put(PARTS, it) }
    }

    private fun lists(parts: List<String>): List<List<String>> = parts.map { listOf(it) }

    @Suppress("FunctionNaming") // ez-vcard's hooks are named so.
    private class NameScribe : StructuredNameScribe() {
        override fun _parseText(value: String, dataType: VCardDataType?, parameters: VCardParameters, context: ParseContext): StructuredName =
            super._parseText(value, dataType, parameters, context).also { readParts(value, NAME_BASE, NAME_PARTS, parameters) }

        override fun _writeText(property: StructuredName, context: WriteContext): String {
            val parts = parts(property)
            if (parts.isEmpty()) return super._writeText(property, context)
            val base = listOf(
                listOfNotNull(property.family), listOfNotNull(property.given), property.additionalNames, property.prefixes, property.suffixes,
            )
            return VObjectPropertyValues.writeStructured(base + lists(parts), true)
        }

        override fun _prepareParameters(property: StructuredName, copy: VCardParameters, version: VCardVersion, vcard: VCard) {
            super._prepareParameters(property, copy, version, vcard)
            copy.removeAll(PARTS)
        }
    }

    @Suppress("FunctionNaming") // ez-vcard's scribe methods start with an underscore.
    private class AdrScribe : AddressScribe() {
        override fun _parseText(value: String, dataType: VCardDataType?, parameters: VCardParameters, context: ParseContext): Address =
            super._parseText(value, dataType, parameters, context).also { readParts(value, ADDRESS_BASE, ADDRESS_PARTS, parameters) }

        override fun _writeText(property: Address, context: WriteContext): String {
            val parts = parts(property)
            if (parts.isEmpty()) return super._writeText(property, context)
            val base = listOf(
                property.poBoxes, property.extendedAddresses, property.streetAddresses, property.localities, property.regions,
                property.postalCodes, property.countries,
            )
            return VObjectPropertyValues.writeStructured(base + lists(parts), true)
        }

        override fun _prepareParameters(property: Address, copy: VCardParameters, version: VCardVersion, vcard: VCard) {
            super._prepareParameters(property, copy, version, vcard)
            copy.removeAll(PARTS)
        }
    }
}
