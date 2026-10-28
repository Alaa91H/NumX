package com.numx.app

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import androidx.annotation.RequiresApi
import com.google.i18n.phonenumbers.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * One contact row to create. The phone value is intentionally separate from
 * the visible name: N2 contacts store an E.164 value and the neutral visible
 * name `N-<digits>`, while an emergency contact stores the short code and a
 * descriptive Arabic service name.
 */
data class ContactDraft(
    val value: String,
    val displayName: String,
    val note: String,
    val phoneType: Int,
    val phoneLabel: String? = null,
)

data class InsertedContact(
    val draft: ContactDraft,
    val rawContactId: Long,
)

data class ContactInsertResult(
    val inserted: List<InsertedContact>,
    val failed: Int,
)

class ContactsManager(private val context: Context) {
    private val phoneUtil: PhoneNumberUtil = PhoneNumberUtil.getInstance()

    /**
     * Returns the device's current default destination for new contacts.
     * Before Android 16 contacts were local by default. On Android 16+ the
     * provider exposes the user's chosen default, which may be a cloud account.
     */
    fun storageInfo(): ContactStorageInfo {
        if (Build.VERSION.SDK_INT < 36) return ContactStorageInfo.LOCAL
        return NewContactsApi.storageInfo(context)
    }

    /**
     * Inserts contacts using ContentProvider batches. [account] is null for
     * local/default storage. When the user explicitly accepts cloud storage,
     * the account is passed explicitly so the provider does not reject a
     * null/local account.
     */
    suspend fun insertDrafts(
        drafts: List<ContactDraft>,
        account: ContactAccount? = null,
    ): ContactInsertResult = withContext(Dispatchers.IO) {
        insertChunk(drafts, account)
    }

    private fun insertChunk(
        drafts: List<ContactDraft>,
        account: ContactAccount?,
    ): ContactInsertResult {
        if (drafts.isEmpty()) return ContactInsertResult(emptyList(), 0)
        return try {
            applyInsertBatch(drafts, account)
        } catch (e: ContactStorageException) {
            throw e
        } catch (e: Exception) {
            // Android 16+ rejects local rows while the default is cloud. Do not
            // turn that policy error into thousands of useless single-row
            // attempts.
            if (isStoragePolicyError(e)) {
                throw ContactStorageException(
                    "تعذر حفظ جهات الاتصال لأن الحساب الافتراضي سحابي. " +
                        "غيّر إعداد جهات الاتصال إلى «تخزين الجهاز» أو اسمح بالحفظ السحابي.",
                    e,
                )
            }
            // A single bad row must not abort a million-number job. Retrying in
            // smaller batches also works around provider transaction-size limits.
            if (drafts.size > 100) {
                merge(drafts.chunked(100).map { insertChunk(it, account) })
            } else if (drafts.size > 1) {
                merge(drafts.map { insertChunk(listOf(it), account) })
            } else {
                ContactInsertResult(emptyList(), 1)
            }
        }
    }

    private fun isStoragePolicyError(e: Throwable): Boolean {
        val message = e.message ?: return false
        return e is IllegalArgumentException &&
            message.contains("default account is set to cloud", ignoreCase = true)
    }

    private fun merge(results: List<ContactInsertResult>): ContactInsertResult {
        val inserted = ArrayList<InsertedContact>()
        var failed = 0
        for (result in results) {
            inserted.addAll(result.inserted)
            failed += result.failed
        }
        return ContactInsertResult(inserted, failed)
    }

    private fun applyInsertBatch(
        drafts: List<ContactDraft>,
        account: ContactAccount?,
    ): ContactInsertResult {
        val opsPerContact = 4
        val ops = ArrayList<ContentProviderOperation>(drafts.size * opsPerContact)
        for (draft in drafts) {
            val rawIndex = ops.size
            // Always provide a non-null ContentValues map. Explicit null
            // account columns mean local storage; cloud/SIM defaults are passed
            // as real values after the user grants consent.
            val rawContact = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name)
            ops.add(rawContact.build())
            val phone = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, draft.value)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, draft.phoneType)
            if (!draft.phoneLabel.isNullOrBlank()) {
                phone.withValue(ContactsContract.CommonDataKinds.Phone.LABEL, draft.phoneLabel)
            }
            ops.add(phone.build())
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, draft.displayName)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, draft.note)
                    .build()
            )
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val inserted = ArrayList<InsertedContact>(drafts.size)
        for (i in drafts.indices) {
            val rawResult = results.getOrNull(i * opsPerContact)?.uri
            if (rawResult != null) {
                inserted.add(InsertedContact(drafts[i], ContentUris.parseId(rawResult)))
            }
        }
        return ContactInsertResult(inserted, drafts.size - inserted.size)
    }

    /**
     * Streams every phone number already saved on the device, reduced to the
     * keys used for duplicate detection. Both the raw digit form and the
     * canonical E.164/NSN forms are emitted, so a number saved locally
     * (`09...`), internationally (`+963...`) or as a bare NSN is recognised.
     */
    fun savedPhoneNumbers(country: Country? = null): Sequence<String> = sequence {
        val defaultRegion = country?.iso2
            ?: Locale.getDefault().country?.takeIf { it.length == 2 }
        val resolver = context.contentResolver
        val cursor = resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            null,
            null,
            null,
        ) ?: throw IllegalStateException("تعذر قراءة جهات الاتصال")
        try {
            while (cursor.moveToNext()) {
                val raw = cursor.getString(0) ?: continue
                for (key in canonicalPhoneKeys(raw, country, defaultRegion)) {
                    yield(key)
                }
            }
        } finally {
            cursor.close()
        }
    }

    /**
     * The keys are deliberately redundant. False positives only skip a
     * generated number that is already present in some format; false negatives
     * would create a duplicate contact, which is the behaviour we must prevent.
     */
    private fun canonicalPhoneKeys(
        raw: String,
        country: Country?,
        defaultRegion: String?,
    ): Set<String> {
        val keys = LinkedHashSet<String>(6)
        val digits = nationalDigits(raw)
        if (digits.isEmpty()) return keys
        keys.add(digits)
        if (digits.startsWith("00")) keys.add(digits.substring(2))
        val parsed = runCatching { phoneUtil.parse(raw, defaultRegion) }.getOrNull()
        if (parsed != null && runCatching { phoneUtil.isValidNumber(parsed) }.getOrDefault(false)) {
            val cc = parsed.countryCode.toString()
            val nsn = parsed.nationalNumber.toString()
            keys.add(cc + nsn)
            keys.add(nsn)
            runCatching {
                phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                    .removePrefix("+")
            }.getOrNull()?.let { keys.add(it) }
        }
        if (country != null) {
            if (country.nationalPrefix == "0" && digits.startsWith("0")) {
                keys.add(digits.substring(1))
            }
            if (digits.startsWith(country.callingCode) &&
                digits.length > country.callingCode.length
            ) {
                keys.add(digits.removePrefix(country.callingCode))
            }
        }
        return keys
    }

    /**
     * Streams raw contacts that carry one of the neutral ownership notes
     * (`REF=N1`, `REF=N2` or `REF=E1`). The cursor is consumed by the database
     * recovery routine, so a large history does not need to be held in memory.
     */
    fun ownedContacts(): Sequence<OwnedContact> = sequence {
        val resolver = context.contentResolver
        val projection = arrayOf(
            ContactsContract.Data.RAW_CONTACT_ID,
            ContactsContract.Data.DATA1,
        )
        val selection =
            "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.Data.DATA1} LIKE ?"
        val args = arrayOf(
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            "REF=%",
        )
        val cursor = resolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.Data.RAW_CONTACT_ID} ASC",
        ) ?: throw IllegalStateException("تعذر قراءة جهات الاتصال")
        try {
            while (cursor.moveToNext()) {
                val rawContactId = cursor.getLong(0)
                val tag = ContactIdentity.parseNote(cursor.getString(1)) ?: continue
                if (rawContactId > 0) yield(OwnedContact(rawContactId, tag))
            }
        } finally {
            cursor.close()
        }
    }

    /**
     * Deletes raw contacts by ID. Raw-contact deletion removes the phone/name
     * rows and the aggregated contact when no other raw contact remains.
     */
    suspend fun deleteRawContacts(ids: List<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val placeholders = ids.joinToString(",") { "?" }
        val args = ids.map { it.toString() }.toTypedArray()
        try {
            context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts._ID} IN ($placeholders)",
                args,
            )
        } catch (e: Exception) {
            // Fallback for providers that reject a large IN clause.
            var deleted = 0
            for (id in ids) {
                deleted += context.contentResolver.delete(
                    ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id),
                    null,
                    null,
                )
            }
            deleted
        }
    }
}

@RequiresApi(36)
private object NewContactsApi {
    fun storageInfo(context: Context): ContactStorageInfo {
        return try {
            val state = ContactsContract.RawContacts.DefaultAccount
                .getDefaultAccountForNewContacts(context.contentResolver)
            val account = state.account
            when (state.state) {
                ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
                    .DEFAULT_ACCOUNT_STATE_LOCAL -> ContactStorageInfo.LOCAL
                ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
                    .DEFAULT_ACCOUNT_STATE_NOT_SET ->
                    ContactStorageInfo(ContactStorageKind.NOT_SET)
                ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
                    .DEFAULT_ACCOUNT_STATE_CLOUD ->
                    ContactStorageInfo(
                        ContactStorageKind.CLOUD,
                        account?.name,
                        account?.type,
                    )
                ContactsContract.RawContacts.DefaultAccount.DefaultAccountAndState
                    .DEFAULT_ACCOUNT_STATE_SIM ->
                    ContactStorageInfo(
                        ContactStorageKind.SIM,
                        account?.name,
                        account?.type,
                    )
                else -> ContactStorageInfo.UNKNOWN
            }
        } catch (e: Exception) {
            ContactStorageInfo.UNKNOWN
        }
    }
}
