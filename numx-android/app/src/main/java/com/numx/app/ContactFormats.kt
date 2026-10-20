package com.numx.app

/**
 * Pure formatting helpers shared by the exporter and unit tests.
 * The CSV shape uses the common contact-export column names; vCard 3.0 is
 * accepted by contacts applications on Android and other platforms.
 */
object ContactFormats {
    const val CSV_HEADER = "Name,Given Name,Family Name,Phone 1 - Type,Phone 1 - Value\n"

    fun csvRow(number: String): String {
        val name = csv(ContactIdentity.displayName(number))
        val phone = csv(number)
        return "$name,$name,,Mobile,$phone\n"
    }

    fun vCard(number: String): String {
        val name = ContactIdentity.displayName(number)
        return buildString {
            append("BEGIN:VCARD\r\n")
            append("VERSION:3.0\r\n")
            append("FN:").append(vCardText(name)).append("\r\n")
            append("N:;").append(vCardText(name)).append(";;;\r\n")
            append("TEL;TYPE=CELL:").append(vCardText(number)).append("\r\n")
            append("END:VCARD\r\n")
        }
    }

    fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun vCardText(value: String): String =
        value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
}
