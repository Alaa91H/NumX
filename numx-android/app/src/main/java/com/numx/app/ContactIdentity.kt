package com.numx.app

import java.util.zip.CRC32

/**
 * Neutral ownership markers stored in contact notes.
 *
 * N1 is the original Syrian mobile marker (`REF=N1;N=09...`). N2 is the global
 * marker (`REF=N2;E=+...;ISO=..;T=..;P=..;G=..;C=..`). E1 marks an explicitly
 * added emergency contact. Nothing in a marker contains the app name, a
 * company name or a phone-number subscriber name.
 */
object ContactIdentity {
    const val NAME_PREFIX = "N-"
    const val NOTE_PREFIX_N1 = "REF=N1;N="
    const val NOTE_PREFIX_N2 = "REF=N2;"
    const val NOTE_PREFIX_E1 = "REF=E1;"

    private val n2NamePattern = Regex("^N-[1-9][0-9]{5,14}$")
    private val e164Pattern = Regex("^\\+[1-9][0-9]{5,14}$")
    private val isoPattern = Regex("^[A-Z]{2}$")

    /** Legacy N1 visible name: `N-09XXXXXXXX`. */
    fun displayNameLocal(localNumber: String): String = NAME_PREFIX + localNumber

    /** Global N2 visible name: `N-<E.164 digits>`, with no `+` and no words. */
    fun displayNameE164(e164: String): String {
        val digits = e164.filter { it.isDigit() }
        return NAME_PREFIX + digits
    }

    fun isValidN2Name(value: String?): Boolean = value != null && n2NamePattern.matches(value)

    fun noteN1(localNumber: String): String = NOTE_PREFIX_N1 + localNumber

    fun noteN2(
        e164: String,
        iso2: String,
        lineType: ActualLineType,
        planVersion: Int,
        batch: String,
    ): String {
        require(e164Pattern.matches(e164)) { "Invalid E.164 number" }
        require(isoPattern.matches(iso2)) { "Invalid ISO code" }
        val crc = n2Crc(e164, iso2, lineType.code, planVersion, batch)
        return "$NOTE_PREFIX_N2" +
            "E=$e164;ISO=$iso2;T=${lineType.code};P=$planVersion;G=$batch;C=$crc"
    }

    fun noteE1(
        iso2: String,
        number: String,
        category: String,
        label: String,
    ): String {
        val safeLabel = label.replace(';', ' ').replace('\n', ' ').trim()
        val crc = e1Crc(iso2, number, category, safeLabel)
        return "$NOTE_PREFIX_E1" +
            "ISO=$iso2;N=$number;S=$category;L=$safeLabel;C=$crc"
    }

    fun parseNote(note: String?): OwnershipTag? {
        val value = note?.trim() ?: return null
        return when {
            value.startsWith(NOTE_PREFIX_N1) -> {
                val number = SyrianNumbers.normalize(value.removePrefix(NOTE_PREFIX_N1).trim())
                    ?: return null
                OwnershipTag.N1(number)
            }
            value.startsWith(NOTE_PREFIX_N2) -> parseN2(value)
            value.startsWith(NOTE_PREFIX_E1) -> parseE1(value)
            else -> null
        }
    }

    /** Legacy helper retained for N1 callers. */
    fun numberFromNote(note: String?): String? {
        val tag = parseNote(note) as? OwnershipTag.N1 ?: return null
        return tag.localNumber
    }

    fun e164FromNote(note: String?): String? = when (val tag = parseNote(note)) {
        is OwnershipTag.N2 -> tag.e164
        is OwnershipTag.N1 -> SyrianNumbers.toE164(tag.localNumber)
        is OwnershipTag.E1 -> tag.number
        null -> null
    }

    private fun parseN2(note: String): OwnershipTag.N2? {
        val fields = parseFields(note.substring(NOTE_PREFIX_N2.length))
        val e164 = fields["E"] ?: return null
        val iso = fields["ISO"] ?: return null
        val typeCode = fields["T"] ?: return null
        val version = fields["P"]?.toIntOrNull() ?: return null
        val batch = fields["G"] ?: return null
        val crc = fields["C"] ?: return null
        if (!e164Pattern.matches(e164)) return null
        if (!isoPattern.matches(iso)) return null
        if (version < 0 || batch.isBlank() || batch.length > 48) return null
        val type = ActualLineType.entries.firstOrNull { it.code == typeCode } ?: return null
        if (!crc.equals(n2Crc(e164, iso, typeCode, version, batch), ignoreCase = true)) return null
        return OwnershipTag.N2(e164, iso, type, version, batch)
    }

    private fun parseE1(note: String): OwnershipTag.E1? {
        val fields = parseFields(note.substring(NOTE_PREFIX_E1.length))
        val iso = fields["ISO"] ?: return null
        val number = fields["N"] ?: return null
        val category = fields["S"] ?: "GENERAL"
        val label = fields["L"].orEmpty()
        val crc = fields["C"] ?: return null
        if (!isoPattern.matches(iso)) return null
        if (number.isEmpty() || number.length > 8 || !number.all { it.isDigit() }) return null
        if (!category.matches(Regex("^[A-Z_]{2,24}$"))) return null
        if (!crc.equals(e1Crc(iso, number, category, label), ignoreCase = true)) return null
        return OwnershipTag.E1(iso, number, category, label)
    }

    private fun parseFields(value: String): Map<String, String> {
        val result = HashMap<String, String>()
        for (part in value.split(';')) {
            if (part.isBlank()) continue
            val separator = part.indexOf('=')
            if (separator <= 0) return emptyMap()
            val key = part.substring(0, separator).trim()
            val field = part.substring(separator + 1).trim()
            if (key.isEmpty() || result.containsKey(key)) return emptyMap()
            result[key] = field
        }
        return result
    }

    private fun n2Crc(
        e164: String,
        iso2: String,
        type: String,
        planVersion: Int,
        batch: String,
    ): String {
        val crc = CRC32()
        crc.update("N2|$e164|$iso2|$type|$planVersion|$batch".toByteArray(Charsets.UTF_8))
        return crc.value.toString(16).padStart(8, '0')
    }

    private fun e1Crc(
        iso2: String,
        number: String,
        category: String,
        label: String,
    ): String {
        val crc = CRC32()
        crc.update("E1|$iso2|$number|$category|$label".toByteArray(Charsets.UTF_8))
        return crc.value.toString(16).padStart(8, '0')
    }
}

sealed class OwnershipTag {
    data class N1(val localNumber: String) : OwnershipTag()
    data class N2(
        val e164: String,
        val iso2: String,
        val lineType: ActualLineType,
        val planVersion: Int,
        val batch: String,
    ) : OwnershipTag()
    data class E1(
        val iso2: String,
        val number: String,
        val category: String,
        val label: String,
    ) : OwnershipTag()
}
