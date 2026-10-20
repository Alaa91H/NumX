package com.numx.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(
    val displayName: String,
    val uri: Uri,
    val mimeType: String,
    val format: ExportFormat,
)

data class ExportResult(
    val file: ExportedFile,
    val exportedCount: Long,
    val lastNumber: String?,
)

/**
 * Streams CSV/vCard output directly to the URI chosen by the user through the
 * Storage Access Framework. No temporary copy is created, so the full
 * country-wide range can be written without doubling the required disk space.
 */
class ExportManager(
    private val context: Context,
    private val database: NumXDatabase,
) {

    suspend fun exportAddedToUri(
        uri: Uri,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val total = database.countAdded()
        if (total == 0L) throw IllegalStateException("لا توجد أرقام مضافة للتصدير")
        var lastNumber: String? = null
        database.exportCursor().use { cursor ->
            val source = NumberSource { max ->
                if (!cursor.moveToNext()) return@NumberSource null
                val batch = ArrayList<String>(max)
                do {
                    val number = cursor.getString(0)
                    batch.add(number)
                    lastNumber = number
                } while (batch.size < max && cursor.moveToNext())
                batch
            }
            writeToUri(
                uri = uri,
                format = format,
                total = total,
                source = source,
                onProgress = onProgress,
            ).copy(lastNumber = lastNumber)
        }
    }

    suspend fun exportRangeToUri(
        uri: Uri,
        startLocal: String,
        count: Int,
        format: ExportFormat,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        if (count <= 0) throw IllegalArgumentException("عدد الأرقام غير صالح")
        val cursor = SyrianNumberCursor(startLocal)
        var produced = 0L
        var lastNumber: String? = null
        val source = NumberSource { max ->
            if (produced >= count) return@NumberSource null
            val take = minOf(max.toLong(), count - produced).toInt()
            val batch = cursor.nextBatch(take) ?: return@NumberSource null
            produced += batch.size
            lastNumber = batch.lastOrNull()
            batch
        }
        writeToUri(
            uri = uri,
            format = format,
            total = count.toLong(),
            source = source,
            onProgress = onProgress,
        ).copy(lastNumber = lastNumber)
    }

    fun share(files: List<ExportedFile>) {
        val file = files.firstOrNull() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = file.mimeType
            putExtra(Intent.EXTRA_STREAM, file.uri)
            clipData = ClipData.newRawUri(file.displayName, file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "مشاركة الملف")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private suspend fun writeToUri(
        uri: Uri,
        format: ExportFormat,
        total: Long,
        source: NumberSource,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ExportResult {
        val stream = openStream(uri)
        val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), BUFFER_SIZE)
        val batchBuffer = StringBuilder(BUFFER_SIZE)
        var done = 0L
        try {
            if (format == ExportFormat.CSV) {
                writer.write(ContactFormats.CSV_HEADER)
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = source.nextBatch(EXPORT_BATCH) ?: break
                if (batch.isEmpty()) break
                for (number in batch) {
                    when (format) {
                        ExportFormat.CSV -> ContactFormats.appendCsvRow(batchBuffer, number)
                        ExportFormat.VCARD -> ContactFormats.appendVCard(batchBuffer, number)
                    }
                    if (batchBuffer.length >= BUFFER_SIZE) {
                        writer.append(batchBuffer)
                        batchBuffer.setLength(0)
                    }
                }
                done += batch.size
                onProgress(done, total)
            }
            if (batchBuffer.isNotEmpty()) {
                writer.append(batchBuffer)
            }
            writer.flush()
            if (done == 0L) throw IllegalStateException("لا توجد أرقام للكتابة")
            onProgress(done, total)
            return ExportResult(
                file = ExportedFile(
                    displayName = displayName(uri, format),
                    uri = uri,
                    mimeType = format.mimeType,
                    format = format,
                ),
                exportedCount = done,
                lastNumber = null,
            )
        } finally {
            runCatching { writer.flush() }
            runCatching { writer.close() }
        }
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

    private fun interface NumberSource {
        fun nextBatch(max: Int): List<String>?
    }

    companion object {
        private const val BUFFER_SIZE = 1 shl 20
        private const val EXPORT_BATCH = 4_096

        fun suggestedFileName(prefix: String, format: ExportFormat): String {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return "${prefix}_$stamp.${format.extension}"
        }
    }
}
