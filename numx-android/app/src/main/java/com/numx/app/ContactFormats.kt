package com.numx.app

/**
 * One row of a contact export. Keeping the exact metadata in one immutable
 * object makes the CSV/vCard writers, the contacts provider and the tests all
 * agree on the same ten columns and on the neutral N2/E1 ownership notes.
 */
data class ExportRecord(
    val name: String,
    val phoneValue: String,
    val phoneType: String,
    val vCardType: String,
    val iso2: String,
    val callingCode: String,
    val e164: String,
    val lineType: String,
    val note: String,
    val vCardLabel: String? = null,
)

/**
 * Pure formatting helpers shared by the exporter and unit tests.
 *
 * CSV uses the common contact-export column names plus the NumX metadata
 * columns (`Country ISO`, `Calling Code`, `E.164`, `Line Type`, `Note`).
 * vCard 3.0 is accepted by contacts applications on Android and other
 * platforms; the ownership note is still carried in NOTE so a restored app can
 * recognise its own contacts.
 */
object ContactFormats {
    const val CSV_HEADER =
        "Name,Given Name,Family Name,Phone 1 - Type,Phone 1 - Value," +
            "Country ISO,Calling Code,E.164,Line Type,Note\n"

    fun csvRow(record: ExportRecord): String =
        StringBuilder(160).also { appendCsvRow(it, record) }.toString()

    fun vCard(record: ExportRecord): String =
        StringBuilder(240).also { appendVCard(it, record) }.toString()

    fun appendCsvRow(target: StringBuilder, record: ExportRecord) {
        appendCsvValue(target, record.name)
        target.append(',')
        appendCsvValue(target, record.name)
        target.append(",,")
        appendCsvValue(target, record.phoneType)
        target.append(',')
        appendCsvValue(target, record.phoneValue)
        target.append(',')
        appendCsvValue(target, record.iso2)
        target.append(',')
        appendCsvValue(target, record.callingCode)
        target.append(',')
        appendCsvValue(target, record.e164)
        target.append(',')
        appendCsvValue(target, record.lineType)
        target.append(',')
        appendCsvValue(target, record.note)
        target.append('\n')
    }

    fun appendVCard(target: StringBuilder, record: ExportRecord) {
        target.append("BEGIN:VCARD\r\n")
        target.append("VERSION:3.0\r\n")
        target.append("FN:")
        appendVCardText(target, record.name)
        target.append("\r\n")
        target.append("N:;")
        appendVCardText(target, record.name)
        target.append(";;;\r\n")
        target.append("TEL;TYPE=")
        target.append(record.vCardType)
        if (!record.vCardLabel.isNullOrBlank()) {
            target.append(";X-ABLabel=")
            appendVCardText(target, record.vCardLabel)
        }
        target.append(':')
        appendVCardText(target, record.phoneValue)
        target.append("\r\n")
        target.append("NOTE:")
        appendVCardText(target, record.note)
        target.append("\r\n")
        target.append("END:VCARD\r\n")
    }

    fun csv(value: String): String =
        StringBuilder(value.length + 2).also { appendCsvValue(it, value) }.toString()

    private fun appendCsvValue(target: StringBuilder, value: String) {
        target.append('"')
        var start = 0
        for (index in value.indices) {
            if (value[index] == '"') {
                target.append(value, start, index + 1)
                target.append('"')
                start = index + 1
            }
        }
        target.append(value, start, value.length)
        target.append('"')
    }

    private fun appendVCardText(target: StringBuilder, value: String) {
        for (ch in value) {
            when (ch) {
                '\\' -> target.append("\\\\")
                '\n' -> target.append("\\n")
                '\r' -> Unit
                ',' -> target.append("\\,")
                ';' -> target.append("\\;")
                else -> target.append(ch)
            }
        }
    }
}
