package com.numx.app

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberToTimeZonesMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/**
 * Offline result for one phone number. The object deliberately has no fields
 * for subscriber name, address, activation state or the carrier after number
 * portability: those facts are not available offline and must never be
 * invented.
 */
data class LookupResult(
    val input: String,
    val valid: Boolean,
    val possible: Boolean,
    val iso2: String?,
    val countryAr: String,
    val countryEn: String,
    val callingCode: String,
    val e164: String,
    val international: String,
    val national: String,
    val lineTypeAr: String,
    val regionAr: String?,
    val regionEn: String?,
    val carrierAr: String?,
    val carrierOriginal: String?,
    val fixedOperatorAr: String?,
    val fixedOperatorOriginal: String?,
    val timeZones: List<String>,
    val sources: List<String>,
    val notes: List<String>,
    val error: String? = null,
)

class LookupEngine(private val metadata: MetadataRepository) {
    private val util = PhoneNumberUtil.getInstance()
    private val geocoder = PhoneNumberOfflineGeocoder.getInstance()
    private val carrierMapper = PhoneNumberToCarrierMapper.getInstance()
    private val timeZones = PhoneNumberToTimeZonesMapper.getInstance()
    private val arabic = Locale.forLanguageTag("ar")
    private val english = Locale.ENGLISH

    fun lookup(input: String, selectedIso: String?): LookupResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return failure(input, "أدخل رقماً للبحث.")
        val selected = selectedIso?.let { metadata.country(it) }
        val region = selected?.iso2
            ?.takeIf { runCatching { util.getCountryCodeForRegion(it) > 0 }.getOrDefault(false) }
        val normalizedInput = normalizeInput(trimmed)
        val parsed = try {
            util.parse(normalizedInput, region)
        } catch (e: NumberParseException) {
            return failure(
                input,
                when (e.errorType) {
                    NumberParseException.ErrorType.NOT_A_NUMBER ->
                        "المدخل لا يحتوي رقماً صالحاً."
                    NumberParseException.ErrorType.TOO_SHORT_AFTER_IDD,
                    NumberParseException.ErrorType.TOO_SHORT_NSN ->
                        "الرقم أقصر من الحد الأدنى."
                    NumberParseException.ErrorType.TOO_LONG ->
                        "الرقم أطول من الحد الأقصى."
                    NumberParseException.ErrorType.INVALID_COUNTRY_CODE ->
                        "رمز الدولة غير صالح. جرّب إدخال الرقم بصيغة +963..."
                    else -> "تعذر تحليل الرقم."
                },
            )
        }

        val numberRegion = runCatching { util.getRegionCodeForNumber(parsed) }.getOrNull()
        val country = numberRegion?.let { metadata.country(it) }
            ?: selected?.takeIf { it.callingCode == parsed.countryCode.toString() }
            ?: metadata.countries().firstOrNull { it.callingCode == parsed.countryCode.toString() }

        val nsn = parsed.nationalNumber.toString()
        val syrianOverride = parsed.countryCode == 963 &&
            nsn.length == 9 &&
            (nsn.startsWith("91") || nsn.startsWith("92"))
        val valid = runCatching { util.isValidNumber(parsed) }.getOrDefault(false) || syrianOverride
        val possible = runCatching { util.isPossibleNumber(parsed) }.getOrDefault(false)
        val type = when {
            syrianOverride -> PhoneNumberType.MOBILE
            else -> runCatching { util.getNumberType(parsed) }.getOrDefault(PhoneNumberType.UNKNOWN)
        }

        val iso = country?.iso2 ?: numberRegion
        val descriptionEn = runCatching {
            geocoder.getDescriptionForNumber(parsed, english)
        }.getOrDefault("")
        val descriptionAr = runCatching {
            geocoder.getDescriptionForNumber(parsed, arabic)
        }.getOrDefault("")
        val curated = metadata.geoArabic(descriptionEn)
        val regionAr = when {
            !curated.isNullOrBlank() -> curated
            descriptionAr.isNotBlank() && descriptionAr != "Unknown" -> descriptionAr
            else -> null
        }
        val regionEn = descriptionEn.takeIf { it.isNotBlank() && it != "Unknown" }

        val originalCarrier = if (valid) {
            runCatching { carrierMapper.getNameForNumber(parsed, english) }
                .getOrDefault("")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
        val fixed = if (valid && type == PhoneNumberType.FIXED_LINE) {
            iso?.let { metadata.fixedOperator(it) }
        } else {
            null
        }
        val carrierArabic = originalCarrier?.let { metadata.carrierArabic(it) } ?: originalCarrier

        val zones = if (valid) {
            runCatching { timeZones.getTimeZonesForNumber(parsed) }
                .getOrDefault(emptyList())
                .filter { it.isNotBlank() && it != PhoneNumberToTimeZonesMapper.getUnknownTimeZone() }
        } else {
            emptyList()
        }

        val international = runCatching {
            util.format(parsed, PhoneNumberFormat.INTERNATIONAL)
        }.getOrDefault("")
        val national = runCatching {
            util.format(parsed, PhoneNumberFormat.NATIONAL)
        }.getOrDefault("")
        val e164 = runCatching {
            util.format(parsed, PhoneNumberFormat.E164)
        }.getOrDefault("")

        val notes = buildList {
            add("لا يعرض التطبيق اسم المشترك أو عنوانه أو حالة تفعيل الرقم؛ هذه المعلومات غير متاحة دون اتصال.")
            add("المشغّل المعروض هو المشغّل الأصلي حسب البيانات المرجعية، وقد يتغير بعد نقل الرقم (MNP).")
            if (type == PhoneNumberType.FIXED_LINE) {
                add("الخط الأرضي: يُعرض المشغّل التاريخي المعروف فقط، وإذا لم تتوفر بيانات موثوقة فستظهر «غير متوفر».")
            }
            if (syrianOverride) {
                add("الرقم ضمن بادئات سوريا المعتمدة في NumX (091/092) التي لا تصنّفها بيانات libphonenumber الحالية.")
            }
            if (!valid) {
                add("الرقم غير صالح وفق خطة الترقيم الحالية للدولة؛ قد يكون قديماً أو تجريبياً أو ناقصاً.")
            }
            add("البيانات مدمجة في التطبيق وتعمل دون إنترنت، وقد لا تعكس آخر تغييرات الترقيم.")
        }

        return LookupResult(
            input = trimmed,
            valid = valid,
            possible = possible,
            iso2 = iso,
            countryAr = country?.nameAr ?: "غير معروفة",
            countryEn = country?.nameEn ?: "Unknown",
            callingCode = "+${parsed.countryCode}",
            e164 = e164,
            international = international,
            national = national,
            lineTypeAr = lineTypeLabel(type),
            regionAr = regionAr,
            regionEn = regionEn,
            carrierAr = carrierArabic,
            carrierOriginal = originalCarrier,
            fixedOperatorAr = fixed?.first,
            fixedOperatorOriginal = fixed?.second,
            timeZones = zones,
            sources = buildList {
                add("libphonenumber 9.0.42 — PhoneNumberMetadata")
                add("libphonenumber geocoder 3.42")
                add("libphonenumber carrier 2.42")
                add("NumX Arabic geographic overlay (curated)")
            },
            notes = notes,
        )
    }

    private fun failure(input: String, message: String) = LookupResult(
        input = input,
        valid = false,
        possible = false,
        iso2 = null,
        countryAr = "غير معروفة",
        countryEn = "Unknown",
        callingCode = "",
        e164 = "",
        international = "",
        national = "",
        lineTypeAr = "غير معروف",
        regionAr = null,
        regionEn = null,
        carrierAr = null,
        carrierOriginal = null,
        fixedOperatorAr = null,
        fixedOperatorOriginal = null,
        timeZones = emptyList(),
        sources = listOf("libphonenumber 9.0.42"),
        notes = emptyList(),
        error = message,
    )

    private fun normalizeInput(input: String): String {
        val digits = nationalDigits(input)
        if (input.trim().startsWith("+")) return "+$digits"
        if (input.trim().startsWith("00")) return "+" + digits.removePrefix("00")
        return input.trim()
    }

    private fun lineTypeLabel(type: PhoneNumberType): String = when (type) {
        PhoneNumberType.MOBILE -> "خليوي"
        PhoneNumberType.FIXED_LINE -> "أرضي"
        PhoneNumberType.FIXED_LINE_OR_MOBILE -> "أرضي أو خليوي (غير متميز)"
        PhoneNumberType.TOLL_FREE -> "رقم مجاني"
        PhoneNumberType.PREMIUM_RATE -> "رقم مدفوع"
        PhoneNumberType.SHARED_COST -> "تكلفة مشتركة"
        PhoneNumberType.VOIP -> "VoIP"
        PhoneNumberType.PERSONAL_NUMBER -> "رقم شخصي"
        PhoneNumberType.PAGER -> "بيجر"
        PhoneNumberType.UAN -> "رقم موحّد"
        PhoneNumberType.VOICEMAIL -> "بريد صوتي"
        else -> "غير معروف"
    }
}
