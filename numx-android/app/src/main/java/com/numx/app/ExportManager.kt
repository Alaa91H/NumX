package com.numx.app

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportedFile(
    val displayName: String,
    val uri: Uri,
    val mimeType: String,
    val file: File? = null,
)

data class ExportResult(
    val files: List<ExportedFile>,
    val exportedCount: Long,
    val directoryLabel: String,
)

class ExportManager(
    private val context: Context,
    private val database: NumXDatabase,
) {

    /** Exports numbers that this app has already inserted into contacts. */
    suspend fun exportAll(
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        val total = database.countAdded()
        if (total == 0L) {
            throw IllegalStateException("لا توجد أرقام مضافة للتصدير")
        }
        database.exportCursor().use { cursor ->
            val source = NumberSource { max ->
                if (!cursor.moveToNext()) return@NumberSource null
                val batch = ArrayList<String>(max)
                do {
                    batch.add(cursor.getString(0))
                } while (batch.size < max && cursor.moveToNext())
                batch
            }
            writeFiles("contacts", total, source, onProgress)
        }
    }

    /**
     * Exports a range starting at [startLocal] without inserting anything into
     * the device contacts.
     */
    suspend fun exportRange(
        startLocal: String,
        count: Int,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): ExportResult = withContext(Dispatchers.IO) {
        if (count <= 0) throw IllegalArgumentException("عدد الأرقام غير صالح")
        val cursor = SyrianNumberCursor(startLocal)
        var produced = 0L
        val source = NumberSource { max ->
            if (produced >= count) return@NumberSource null
            val take = minOf(max.toLong(), count - produced).toInt()
            val batch = cursor.nextBatch(take) ?: return@NumberSource null
            produced += batch.size
            batch
        }
        writeFiles("range", count.toLong(), source, onProgress)
    }

    fun share(files: List<ExportedFile>) {
        val uris = ArrayList(files.map { it.uri })
        if (uris.isEmpty()) return
        val clip = ClipData.newRawUri("", uris.first()).apply {
            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "مشاركة الملفات").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private suspend fun writeFiles(
        baseName: String,
        total: Long,
        source: NumberSource,
        onProgress: (done: Long, total: Long) -> Unit,
    ): ExportResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val csvName = "${baseName}_$stamp.csv"
        val vcfName = "${baseName}_$stamp.vcf"

        val opened = mutableListOf<Target>()
        try {
            opened += openTarget(csvName, "text/csv")
            opened += openTarget(vcfName, "text/x-vcard")
            val csvWriter = opened[0].writer()
            val vcfWriter = opened[1].writer()
            var done = 0L
            try {
                csvWriter.write(ContactFormats.CSV_HEADER)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val batch = source.nextBatch(1000) ?: break
                    if (batch.isEmpty()) break
                    for (number in batch) {
                        csvWriter.write(ContactFormats.csvRow(number))
                        vcfWriter.write(ContactFormats.vCard(number))
                        done++
                    }
                    onProgress(done, total)
                }
                csvWriter.flush()
                vcfWriter.flush()
                onProgress(done, total)
            } finally {
                // finish()/abort() owns the underlying stream.
                runCatching { csvWriter.flush() }
                runCatching { vcfWriter.flush() }
            }
            if (done == 0L) throw IllegalStateException("لا توجد أرقام للتصدير")
            val files = opened.map { it.finish() }
            return ExportResult(
                files = files,
                exportedCount = done,
                directoryLabel = if (Build.VERSION.SDK_INT >= 29) {
                    "Downloads/Exports"
                } else {
                    "مجلد الملفات"
                },
            )
        } catch (e: Exception) {
            for (target in opened) {
                runCatching { target.abort() }
            }
            throw e
        }
    }

    private fun openTarget(displayName: String, mimeType: String): Target {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Exports")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("تعذر إنشاء ملف التصدير")
            val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull()
            if (stream == null) {
                runCatching { resolver.delete(uri, null, null) }
                throw IllegalStateException("تعذر فتح ملف التصدير")
            }
            return Target(displayName, uri, mimeType, stream, isMediaStore = true)
        } else {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val dir = File(base, "Exports").apply { mkdirs() }
            val file = File(dir, displayName)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val stream = file.outputStream()
            return Target(displayName, uri, mimeType, stream, isMediaStore = false, file = file)
        }
    }

    private fun interface NumberSource {
        fun nextBatch(max: Int): List<String>?
    }

    private inner class Target(
        val displayName: String,
        val uri: Uri,
        val mimeType: String,
        private val stream: OutputStream,
        private val isMediaStore: Boolean,
        private val file: File? = null,
    ) {
        fun writer(): BufferedWriter =
            BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), 1 shl 20)

        fun finish(): ExportedFile {
            stream.flush()
            stream.close()
            if (isMediaStore) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            }
            return ExportedFile(displayName, uri, mimeType, file)
        }

        fun abort() {
            runCatching { stream.close() }
            if (isMediaStore) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            } else {
                runCatching { file?.delete() }
            }
        }
    }
}
