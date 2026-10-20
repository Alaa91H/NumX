package com.numx.app

/**
 * Stable, neutral markers stored with every generated contact.
 *
 * The visible name is a plain number with an "N-" prefix, while a note carries
 * a machine-readable ownership tag. Both are independent of the app name,
 * package name and any company/country name, so contacts can be recovered
 * after uninstalling and reinstalling the app.
 */
object ContactIdentity {
    const val NAME_PREFIX = "N-"
    const val NOTE_PREFIX = "REF=N1;N="

    fun displayName(number: String): String = NAME_PREFIX + number

    fun note(number: String): String = NOTE_PREFIX + number

    /** Parses a normalized local number from an ownership note, or returns null. */
    fun numberFromNote(note: String?): String? {
        if (note == null || !note.startsWith(NOTE_PREFIX)) return null
        return SyrianNumbers.normalize(note.substring(NOTE_PREFIX.length).trim())
    }
}
