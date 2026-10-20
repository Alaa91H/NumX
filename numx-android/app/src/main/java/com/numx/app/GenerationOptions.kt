package com.numx.app

import java.util.Locale

/** How the generated numbers leave the app. */
enum class GenerationMode {
    CONTACTS,
    FILE,
}

/**
 * Supported file formats. The description is shown in the UI so the user can
 * choose the format that fits the device where the file will be used.
 */
enum class ExportFormat(
    val label: String,
    val extension: String,
    val mimeType: String,
    val suitableDevice: String,
    val explanation: String,
    private val estimatedBytesPerNumber: Long,
) {
    CSV(
        label = "CSV",
        extension = "csv",
        mimeType = "text/csv",
        suitableDevice = "مناسب للحاسوب والجداول",
        explanation = "ملف جدولي يُفتح في Excel وGoogle Sheets وأدوات الاستيراد. " +
            "هو الخيار الأفضل للنطاقات الكبيرة والملايين.",
        estimatedBytesPerNumber = 52L,
    ),
    VCARD(
        label = "vCard 3.0",
        extension = "vcf",
        mimeType = "text/x-vcard",
        suitableDevice = "مناسب للهاتف وجهات الاتصال",
        explanation = "بطاقات جهات اتصال قياسية تُستورد في Android وiPhone. " +
            "مناسب للأعداد الصغيرة والمتوسطة، وقد يكون بطيئاً جداً مع الملايين.",
        estimatedBytesPerNumber = 102L,
    );

    fun estimatedBytes(count: Long): Long = count.coerceAtLeast(0) * estimatedBytesPerNumber
}

const val CONTACTS_GENERATION_LIMIT = 5_000_000
const val FILE_GENERATION_LIMIT = 80_000_000

fun formatFileSize(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", safe / 1_000_000_000.0)
        safe >= 1_000_000L -> String.format(Locale.US, "%.1f MB", safe / 1_000_000.0)
        safe >= 1_000L -> String.format(Locale.US, "%.0f KB", safe / 1_000.0)
        else -> "$safe B"
    }
}
