package com.numx.app

/**
 * Pure formatting helpers shared by the exporter and unit tests.
 * The CSV shape uses the common contact-export column names; vCard 3.0 is
 * accepted by contacts applications on Android and other platforms.
 *
 * The append* variants let the exporter reuse one large buffer for millions of
 * rows instead of allocating a new String for every number.
 */
object ContactFormats {
    const val CSV_HEADER = "Name,Given Name,Family Name,Phone 1 - Type,Phone 1 - Value\n"

    fun csvRow(number: String): String =
        StringBuilder(64).also { appendCsvRow(it, number) }.toString()

    fun vCard(number: String): String =
        StringBuilder(112).also { appendVCard(it, number) }.toString()

    fun appendCsvRow(target: StringBuilder, number: String) {
        val name = ContactIdentity.displayName(number)
        appendCsvValue(target, name)
        target.append(',')
        appendCsvValue(target, name)
        target.append(",,")
        target.append("Mobile,")
        appendCsvValue(target, number)
        target.append('\n')
    }

    fun appendVCard(target: StringBuilder, number: String) {
        val name = ContactIdentity.displayName(number)
        target.append("BEGIN:VCARD\r\n")
        target.append("VERSION:3.0\r\n")
        target.append("FN:")
        appendVCardText(target, name)
        target.append("\r\n")
        target.append("N:;")
        appendVCardText(target, name)
        target.append(";;;\r\n")
        target.append("TEL;TYPE=CELL:")
        appendVCardText(target, number)
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
                ',' -> target.append("\\,")
                ';' -> target.append("\\;")
                else -> target.append(ch)
            }
        }
    }
}
