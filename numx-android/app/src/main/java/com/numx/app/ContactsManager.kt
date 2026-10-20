package com.numx.app

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.ContactsContract
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContactInsertResult(
    val inserted: List<Pair<String, Long>>,
    val failed: Int,
)

data class OwnedContact(
    val rawContactId: Long,
    val number: String,
)

class ContactsManager(private val context: Context) {

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
     * Inserts numbers as mobile contacts using ContentProvider batches.
     * Every row receives a neutral name and a note marker so it can be
     * recognized even if this app's database is lost.
     *
     * [account] is null for local/default storage. When the user explicitly
     * accepts cloud storage, the account is passed explicitly so the provider
     * does not reject a null/local account.
     */
    suspend fun insertNumbers(
        numbers: List<String>,
        account: ContactAccount? = null,
    ): ContactInsertResult = withContext(Dispatchers.IO) {
        insertChunk(numbers, account)
    }

    private fun insertChunk(
        numbers: List<String>,
        account: ContactAccount?,
    ): ContactInsertResult {
        if (numbers.isEmpty()) return ContactInsertResult(emptyList(), 0)
        return try {
            applyInsertBatch(numbers, account)
        } catch (e: ContactStorageException) {
            throw e
        } catch (e: Exception) {
            // Android 16+ rejects local rows while the default is cloud. Do not
            // turn that policy error into 250 useless single-row attempts.
            if (isStoragePolicyError(e)) {
                throw ContactStorageException(
                    "تعذر حفظ جهات الاتصال لأن الحساب الافتراضي سحابي. " +
                        "غيّر إعداد جهات الاتصال إلى «تخزين الجهاز» أو اسمح بالحفظ السحابي.",
                    e,
                )
            }
            // A single bad row must not abort a million-number job. Retrying in
            // smaller batches also works around provider transaction-size limits.
            if (numbers.size > 100) {
                merge(numbers.chunked(100).map { insertChunk(it, account) })
            } else if (numbers.size > 1) {
                merge(numbers.map { insertChunk(listOf(it), account) })
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
        val inserted = ArrayList<Pair<String, Long>>()
        var failed = 0
        for (result in results) {
            inserted.addAll(result.inserted)
            failed += result.failed
        }
        return ContactInsertResult(inserted, failed)
    }

    private fun applyInsertBatch(
        numbers: List<String>,
        account: ContactAccount?,
    ): ContactInsertResult {
        val opsPerContact = 4
        val ops = ArrayList<ContentProviderOperation>(numbers.size * opsPerContact)
        for (number in numbers) {
            val rawIndex = ops.size
            // Always provide a non-null ContentValues map. Passing an empty
            // insert operation makes some ContactsProvider versions crash while
            // validating the row. Explicit null account columns mean local
            // storage; cloud/SIM defaults are passed as real values after the
            // user grants consent.
            val rawContact = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, account?.type)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, account?.name)
            ops.add(rawContact.build())
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, ContactIdentity.displayName(number))
                    .build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, ContactIdentity.note(number))
                    .build()
            )
        }
        val results = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        val inserted = ArrayList<Pair<String, Long>>(numbers.size)
        for (i in numbers.indices) {
            val rawResult = results.getOrNull(i * opsPerContact)?.uri
            if (rawResult != null) {
                inserted.add(numbers[i] to ContentUris.parseId(rawResult))
            }
        }
        return ContactInsertResult(inserted, numbers.size - inserted.size)
    }

    /**
     * Streams every phone number already saved on the device, normalized to the
     * local 09XXXXXXXX form. This includes contacts created by this app and
     * contacts from any other account, which is the basis for duplicate-free
     * generation. Invalid/non-Syrian phone values are ignored.
     */
    fun savedPhoneNumbers(): Sequence<String> = sequence {
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
                val number = SyrianNumbers.normalize(raw) ?: continue
                yield(number)
            }
        } finally {
            cursor.close()
        }
    }

    /**
     * Streams raw contacts that carry the neutral ownership note. The cursor is
     * consumed by the database recovery routine in batches, so a large history
     * does not need to be held in memory.
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
            ContactIdentity.NOTE_PREFIX + "%",
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
                val number = ContactIdentity.numberFromNote(cursor.getString(1)) ?: continue
                if (rawContactId > 0) yield(OwnedContact(rawContactId, number))
            }
        } finally {
            cursor.close()
        }
    }

    /**
     * Deletes raw contacts by ID. Raw-contact deletion removes the phone/name rows
     * and the aggregated contact when no other raw contact remains.
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
