package com.numx.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(
    val displayName: String,
    val uri: Uri,
    val mimeType: String,
    val format: ExportFormat? = null,
)

data class ExportResult(
    val files: List<ExportedFile>,
    val exportedCount: Long,
    val lastNumber: String?,
    val mobileCount: Long = 0,
    val fixedCount: Long = 0,
)

/**
 * Streams CSV/vCard output directly to the URI chosen by the user through the
 * Storage Access Framework. Nothing is buffered in a temporary file, so very
 * large ranges do not double the required disk space.
 *
 * Large exports can be written to a directory as numbered parts plus a
 * manifest and SHA-256 checksums, which keeps individual files manageable for
 * spreadsheet and import tools.
 */
class ExportManager(
    private val context: Context,
    private val database: NumXDatabase,
    private val engine: NumberingEngine,
    private val metadata: MetadataRepository,
) {

    suspend fun exportAddedToUri(
        uri: Uri,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val total = database.countAdded() + database.countEmergencyAdded()
        if (total == 0L) throw IllegalStateException("لا توجد أرقام مضافة للتصدير")
        val source = addedSource()
        writeSingle(uri, format, total, source, onProgress)
    }

    suspend fun exportRangeToUri(
        uri: Uri,
        country: Country,
        lineType: LineType,
        startNsn: String,
        count: Long,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        require(count > 0) { "عدد الأرقام غير صالح" }
        val source = rangeSource(country, lineType, startNsn, count)
        writeSingle(uri, format, count, source, onProgress)
    }

    suspend fun exportAddedToDirectory(
        treeUri: Uri,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val total = database.countAdded() + database.countEmergencyAdded()
        if (total == 0L) throw IllegalStateException("لا توجد أرقام مضافة للتصدير")
        writeSplit(
            treeUri = treeUri,
            folderName = "contacts_${stamp()}",
            format = format,
            total = total,
            source = addedSource(),
            manifestExtra = JSONObject().put("scope", "added"),
            onProgress = onProgress,
        )
    }

    suspend fun exportRangeToDirectory(
        treeUri: Uri,
        country: Country,
        lineType: LineType,
        startNsn: String,
        count: Long,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        require(count > 0) { "عدد الأرقام غير صالح" }
        writeSplit(
            treeUri = treeUri,
            folderName = "${country.iso2}_${lineType.code}_${stamp()}",
            format = format,
            total = count,
            source = rangeSource(country, lineType, startNsn, count),
            manifestExtra = JSONObject()
                .put("scope", "range")
                .put("countryIso", country.iso2)
                .put("countryAr", country.nameAr)
                .put("lineType", lineType.code)
                .put("startNsn", startNsn)
                .put("planVersion", country.planVersion),
            onProgress = onProgress,
        )
    }

    fun share(files: List<ExportedFile>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { it.uri })
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = files.first().mimeType
                putExtra(Intent.EXTRA_STREAM, files.first().uri)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.clipData = ClipData.newUri(
            context.contentResolver,
            "NumX export",
            files.first().uri,
        ).also { clip ->
            files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(intent, "مشاركة الملفات")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun rangeSource(
        country: Country,
        lineType: LineType,
        startNsn: String,
        count: Long,
    ): RecordSource {
        val cursor = engine.cursor(country, lineType, startNsn)
            ?: throw IllegalStateException("لا تتوفر خطة ترقيم لهذه الدولة أو النوع")
        var produced = 0L
        var lastNumber: String? = null
        var mobile = 0L
        var fixed = 0L
        return RecordSource(
            next = { max ->
                if (produced >= count) {
                    null
                } else {
                    val take = minOf(max.toLong(), count - produced).toInt()
                    val batch = cursor.nextBatch(take)
                    if (batch.isNullOrEmpty()) {
                        null
                    } else {
                        produced += batch.size
                        lastNumber = batch.last().nsn
                        for (number in batch) {
                            when (number.lineType) {
                                ActualLineType.MOBILE -> mobile++
                                ActualLineType.FIXED -> fixed++
                                ActualLineType.AMBIGUOUS -> Unit
                            }
                        }
                        batch.map { generatedRecord(it, country) }
                    }
                }
            },
            lastNumber = { lastNumber },
            mobileCount = { mobile },
            fixedCount = { fixed },
        )
    }

    private fun addedSource(): RecordSource {
        val generated = database.exportCursor()
        val emergency = database.emergencyExportCursor()
        var lastNumber: String? = null
        var mobile = 0L
        var fixed = 0L
        var onEmergency = false
        return RecordSource(
            next = { max ->
                val batch = ArrayList<ExportRecord>(max)
                while (batch.size < max) {
                    if (!onEmergency) {
                        if (generated.moveToNext()) {
                            val record = recordFromGeneratedRow(
                                number = generated.getString(0),
                                e164 = if (generated.isNull(1)) null else generated.getString(1),
                                iso2 = generated.getString(2),
                                lineTypeCode = generated.getString(3),
                                kind = generated.getString(4),
                            )
                            batch.add(record)
                            lastNumber = generated.getString(0)
                            when (record.lineType) {
                                ActualLineType.MOBILE.labelAr -> mobile++
                                ActualLineType.FIXED.labelAr -> fixed++
                            }
                        } else {
                            onEmergency = true
                        }
                    }
                    if (onEmergency) {
                        if (emergency.moveToNext()) {
                            batch.add(
                                recordFromEmergencyRow(
                                    number = emergency.getString(0),
                                    iso2 = emergency.getString(1),
                                    category = emergency.getString(2),
                                    label = emergency.getString(3),
                                )
                            )
                            lastNumber = emergency.getString(0)
                        } else {
                            break
                        }
                    }
                }
                if (batch.isEmpty()) null else batch
            },
            lastNumber = { lastNumber },
            mobileCount = { mobile },
            fixedCount = { fixed },
            close = {
                runCatching { generated.close() }
                runCatching { emergency.close() }
            },
        )
    }

    private fun generatedRecord(number: GeneratedNumber, country: Country): ExportRecord {
        val e164 = number.e164
        val note = ContactIdentity.noteN2(
            e164 = e164,
            iso2 = country.iso2,
            lineType = number.lineType,
            planVersion = country.planVersion,
            batch = "EXPORT",
        )
        return ExportRecord(
            name = ContactIdentity.displayNameE164(e164),
            phoneValue = e164,
            phoneType = number.lineType.csvValue,
            vCardType = number.lineType.vCardType,
            iso2 = country.iso2,
            callingCode = country.callingCode,
            e164 = e164,
            lineType = number.lineType.labelAr,
            note = note,
            vCardLabel = if (number.lineType == ActualLineType.AMBIGUOUS) {
                "أرضي أو خليوي (غير متميز)"
            } else {
                null
            },
        )
    }

    private fun recordFromGeneratedRow(
        number: String,
        e164: String?,
        iso2: String,
        lineTypeCode: String,
        kind: String,
    ): ExportRecord {
        val country = metadata.country(iso2)
        val actual = ActualLineType.fromCode(lineTypeCode)
        val legacySyrian = iso2 == "SY" && number.startsWith("09") && number.length == 10
        if (legacySyrian) {
            return ExportRecord(
                name = ContactIdentity.displayNameLocal(number),
                phoneValue = number,
                phoneType = ActualLineType.MOBILE.csvValue,
                vCardType = ActualLineType.MOBILE.vCardType,
                iso2 = iso2,
                callingCode = country?.callingCode ?: "963",
                e164 = e164 ?: SyrianNumbers.toE164(number).orEmpty(),
                lineType = ActualLineType.MOBILE.labelAr,
                note = ContactIdentity.noteN1(number),
            )
        }
        val resolvedE164 = e164 ?: run {
            val digits = nationalDigits(number)
            if (kind == NumXDatabase.KIND_GENERATED && country != null && digits.isNotEmpty()) {
                "+${country.callingCode}$digits"
            } else {
                number
            }
        }
        val note = runCatching {
            ContactIdentity.noteN2(
                e164 = resolvedE164,
                iso2 = iso2,
                lineType = actual,
                planVersion = country?.planVersion ?: 1,
                batch = "EXPORT",
            )
        }.getOrElse {
            ContactIdentity.noteN1(number)
        }
        return ExportRecord(
            name = ContactIdentity.displayNameE164(resolvedE164),
            phoneValue = resolvedE164,
            phoneType = actual.csvValue,
            vCardType = actual.vCardType,
            iso2 = iso2,
            callingCode = country?.callingCode.orEmpty(),
            e164 = resolvedE164,
            lineType = actual.labelAr,
            note = note,
            vCardLabel = if (actual == ActualLineType.AMBIGUOUS) {
                "أرضي أو خليوي (غير متميز)"
            } else {
                null
            },
        )
    }

    private fun recordFromEmergencyRow(
        number: String,
        iso2: String,
        category: String,
        label: String,
    ): ExportRecord {
        val country = metadata.country(iso2)
        val display = if (label.isBlank()) "طوارئ" else label
        return ExportRecord(
            name = "$display ${country?.nameAr ?: iso2} — $number",
            phoneValue = number,
            phoneType = "Other",
            vCardType = "OTHER",
            iso2 = iso2,
            callingCode = country?.callingCode.orEmpty(),
            e164 = "",
            lineType = "Emergency",
            note = ContactIdentity.noteE1(iso2, number, category, display),
            vCardLabel = "طوارئ",
        )
    }

    private suspend fun writeSingle(
        uri: Uri,
        format: ExportFormat,
        total: Long,
        source: RecordSource,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ExportResult {
        var done = 0L
        try {
            val stream = openStream(uri)
            val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), BUFFER_SIZE)
            val buffer = StringBuilder(BUFFER_SIZE)
            try {
                if (format == ExportFormat.CSV) writer.write(ContactFormats.CSV_HEADER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = source.nextBatch(EXPORT_BATCH) ?: break
                    for (record in batch) {
                        when (format) {
                            ExportFormat.CSV -> ContactFormats.appendCsvRow(buffer, record)
                            ExportFormat.VCARD -> ContactFormats.appendVCard(buffer, record)
                        }
                        if (buffer.length >= BUFFER_SIZE) {
                            writer.append(buffer)
                            buffer.setLength(0)
                        }
                    }
                    done += batch.size
                    onProgress(done, total)
                }
                if (buffer.isNotEmpty()) writer.append(buffer)
                writer.flush()
            } finally {
                runCatching { writer.close() }
            }
        } finally {
            source.close()
        }
        if (done == 0L) throw IllegalStateException("لا توجد أرقام للكتابة")
        return ExportResult(
            files = listOf(
                ExportedFile(
                    displayName = displayName(uri, format),
                    uri = uri,
                    mimeType = format.mimeType,
                    format = format,
                )
            ),
            exportedCount = done,
            lastNumber = source.lastNumber(),
            mobileCount = source.mobileCount(),
            fixedCount = source.fixedCount(),
        )
    }

    private suspend fun writeSplit(
        treeUri: Uri,
        folderName: String,
        format: ExportFormat,
        total: Long,
        source: RecordSource,
        manifestExtra: JSONObject,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ExportResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("تعذر الوصول إلى المجلد المحدد")
        val folder = root.createDirectory(folderName) ?: root
        val parts = ArrayList<PartInfo>()
        var done = 0L
        var partIndex = 1
        try {
            while (done < total) {
                currentCoroutineContext().ensureActive()
                val partTarget = minOf(SPLIT_ROWS, total - done)
                val fileName = "part-%04d.%s".format(Locale.US, partIndex, format.extension)
                val document = folder.createFile(format.mimeType, fileName)
                    ?: throw IllegalStateException("تعذر إنشاء الملف $fileName")
                val part = writePart(document, format, partTarget, source) { progress ->
                    onProgress(done + progress, total)
                }
                if (part.rows == 0L) {
                    runCatching { document.delete() }
                    break
                }
                done += part.rows
                parts.add(part)
                partIndex++
                if (part.rows < partTarget) break
            }
            if (parts.isEmpty()) throw IllegalStateException("لا توجد أرقام للكتابة")
            val manifest = writeManifest(folder, format, total, done, parts, manifestExtra)
            val exports = parts.map {
                ExportedFile(it.name, it.uri, format.mimeType, format)
            } + manifest
            onProgress(done, total)
            return ExportResult(
                files = exports,
                exportedCount = done,
                lastNumber = source.lastNumber(),
                mobileCount = source.mobileCount(),
                fixedCount = source.fixedCount(),
            )
        } finally {
            source.close()
        }
    }

    private suspend fun writePart(
        document: DocumentFile,
        format: ExportFormat,
        target: Long,
        source: RecordSource,
        onProgress: (done: Long) -> Unit,
    ): PartInfo {
        val stream = runCatching { context.contentResolver.openOutputStream(document.uri, "wt") }
            .getOrNull()
            ?: runCatching { context.contentResolver.openOutputStream(document.uri, "w") }
                .getOrNull()
            ?: throw IllegalStateException("تعذر فتح الملف ${document.name} للكتابة")
        val digest = MessageDigest.getInstance("SHA-256")
        val counting = CountingOutputStream(stream)
        val digestStream = object : OutputStream() {
            override fun write(b: Int) {
                digest.update(b.toByte())
                counting.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                digest.update(b, off, len)
                counting.write(b, off, len)
            }

            override fun flush() = counting.flush()

            override fun close() = counting.close()
        }
        val writer = BufferedWriter(OutputStreamWriter(digestStream, Charsets.UTF_8), BUFFER_SIZE)
        val buffer = StringBuilder(BUFFER_SIZE)
        var rows = 0L
        try {
            if (format == ExportFormat.CSV) writer.write(ContactFormats.CSV_HEADER)
            while (rows < target) {
                currentCoroutineContext().ensureActive()
                val batch = source.nextBatch(minOf(EXPORT_BATCH.toLong(), target - rows).toInt())
                    ?: break
                for (record in batch) {
                    when (format) {
                        ExportFormat.CSV -> ContactFormats.appendCsvRow(buffer, record)
                        ExportFormat.VCARD -> ContactFormats.appendVCard(buffer, record)
                    }
                    if (buffer.length >= BUFFER_SIZE) {
                        writer.append(buffer)
                        buffer.setLength(0)
                    }
                }
                rows += batch.size
                onProgress(rows)
                if (batch.isEmpty()) break
            }
            if (buffer.isNotEmpty()) writer.append(buffer)
            writer.flush()
        } finally {
            runCatching { writer.close() }
        }
        return PartInfo(
            name = document.name ?: "part",
            uri = document.uri,
            rows = rows,
            bytes = counting.count,
            sha256 = digest.digest().toHex(),
            mimeType = format.mimeType,
        )
    }

    private fun writeManifest(
        folder: DocumentFile,
        format: ExportFormat,
        requested: Long,
        exported: Long,
        parts: List<PartInfo>,
        extra: JSONObject,
    ): List<ExportedFile> {
        val manifest = JSONObject()
            .put("app", "NumX")
            .put("format", format.extension)
            .put("requested", requested)
            .put("exported", exported)
            .put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
            .put("metadataVersion", "9.0.42")
            .put("parts", JSONArray().also { array ->
                for (part in parts) {
                    array.put(
                        JSONObject()
                            .put("name", part.name)
                            .put("rows", part.rows)
                            .put("bytes", part.bytes)
                            .put("sha256", part.sha256)
                    )
                }
            })
        for (key in extra.keys()) manifest.put(key, extra.get(key))
        // Serialize once: the checksum must cover the exact bytes written to
        // disk, and JSON object ordering must not be allowed to differ between
        // the file and the checksum computation.
        val manifestText = manifest.toString(2)
        val manifestFile = folder.createFile("application/json", "manifest.json")
            ?: throw IllegalStateException("تعذر إنشاء manifest.json")
        openStream(manifestFile.uri).use { stream ->
            stream.write(manifestText.toByteArray(Charsets.UTF_8))
        }

        val checksums = buildString {
            for (part in parts) append(part.sha256).append("  ").append(part.name).append('\n')
            append(sha256(manifestText.toByteArray(Charsets.UTF_8)))
                .append("  manifest.json\n")
        }
        val checksumFile = folder.createFile("application/octet-stream", "checksums.sha256")
            ?: throw IllegalStateException("تعذر إنشاء checksums.sha256")
        openStream(checksumFile.uri).use { stream ->
            stream.write(checksums.toByteArray(Charsets.UTF_8))
        }

        return listOf(
            ExportedFile(
                manifestFile.name ?: "manifest.json",
                manifestFile.uri,
                "application/json",
            ),
            ExportedFile(
                checksumFile.name ?: "checksums.sha256",
                checksumFile.uri,
                "application/octet-stream",
            ),
        )
    }

    private fun openStream(uri: Uri): OutputStream {
        val resolver = context.contentResolver
        val opened = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
        return opened ?: throw IllegalStateException(
            "تعذر فتح الملف المحدد للكتابة. اختر مجلداً محلياً أو بطاقة SD وحاول مجدداً."
        )
    }

    private fun displayName(uri: Uri, format: ExportFormat): String {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "numbers_${System.currentTimeMillis()}.${format.extension}"
    }

    private fun Boolean.toInt() = if (this) 1 else 0

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    private data class PartInfo(
        val name: String,
        val uri: Uri,
        val rows: Long,
        val bytes: Long,
        val sha256: String,
        val mimeType: String,
    )

    private class RecordSource(
        private val next: (Int) -> List<ExportRecord>?,
        private val lastNumber: () -> String?,
        private val mobileCount: () -> Long,
        private val fixedCount: () -> Long,
        private val close: () -> Unit = {},
    ) {
        fun nextBatch(max: Int): List<ExportRecord>? = next(max)
        fun lastNumber(): String? = lastNumber.invoke()
        fun mobileCount(): Long = mobileCount.invoke()
        fun fixedCount(): Long = fixedCount.invoke()
        fun close() = close.invoke()
    }

    companion object {
        private const val BUFFER_SIZE = 1 shl 20
        private const val EXPORT_BATCH = 4_096
        private const val SPLIT_ROWS = 1_000_000L

        fun suggestedFileName(prefix: String, format: ExportFormat): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "${prefix}_$stamp.${format.extension}"
        }

        fun suggestedFolderName(prefix: String): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "${prefix}_$stamp"
        }

        fun requiresSplit(count: Long): Boolean = count > SPLIT_ROWS
        val splitRows: Long get() = SPLIT_ROWS

        private fun stamp(): String =
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    }
}
