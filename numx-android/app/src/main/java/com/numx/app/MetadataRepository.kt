package com.numx.app

import android.content.Context
import org.json.JSONObject

data class EmergencyEntry(
    val iso2: String,
    val number: String,
    val category: String,
    val labelAr: String,
    val labelEn: String,
    val countryAr: String,
    val countryEn: String,
    val verification: String,
    val source: String,
    val sourceUrl: String,
    val checkedAt: String?,
    val noteAr: String,
) {
    /**
     * Arabic idafa needs an indefinite first term: "شرطة سوريا — 112",
     * not "الشرطة سوريا — 112". The curated labels are definite nouns, so
     * strip the leading article when composing a display name.
     */
    val displayLabelAr: String
        get() = labelAr.removePrefix("ال").ifBlank { labelAr }

    val displayNameAr: String get() = "$displayLabelAr $countryAr — $number"
}

data class SourceInfo(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
    val license: String?,
)

/**
 * Read-only access to the metadata shipped in the APK. Loading is deliberately
 * simple and synchronous on the IO dispatcher: the files are small and every
 * lookup must work with no network permission.
 */
class MetadataRepository(private val context: Context) {
    private val countryList: List<Country> by lazy { loadCountries() }
    private val countryByIso: Map<String, Country> by lazy { countryList.associateBy { it.iso2 } }
    private val emergencyList: List<EmergencyEntry> by lazy { loadEmergency() }
    private val emergencyByIso: Map<String, List<EmergencyEntry>> by lazy {
        emergencyList.groupBy { it.iso2 }.mapValues { (_, entries) ->
            entries.sortedWith(compareBy({ it.number.length }, { it.number }))
        }
    }
    private val geoOverlay: JSONObject by lazy {
        readJson("metadata/geo_ar.json") ?: JSONObject()
    }
    private val carrierOverlay: JSONObject by lazy {
        readJson("metadata/carriers_ar.json") ?: JSONObject()
    }
    private val sourceList: List<SourceInfo> by lazy { loadSources() }

    fun countries(): List<Country> = countryList
    fun country(iso2: String): Country? = countryByIso[iso2.uppercase()]
    fun emergency(iso2: String): List<EmergencyEntry> = emergencyByIso[iso2.uppercase()].orEmpty()
    fun emergencyCount(): Int = emergencyList.size
    fun emergencyCountryCount(): Int = emergencyByIso.size
    fun sources(): List<SourceInfo> = sourceList

    fun geoArabic(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val values = geoOverlay.optJSONObject("descriptions") ?: return null
        return values.optString(description).takeIf { it.isNotBlank() }
    }

    fun carrierArabic(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val values = carrierOverlay.optJSONObject("carriers") ?: return null
        return values.optString(name).takeIf { it.isNotBlank() }
    }

    fun fixedOperator(iso2: String): Pair<String, String>? {
        val values = carrierOverlay.optJSONObject("fixedOperators") ?: return null
        val entry = values.optJSONObject(iso2.uppercase()) ?: return null
        val ar = entry.optString("ar")
        val en = entry.optString("en")
        if (ar.isBlank() && en.isBlank()) return null
        return ar.ifBlank { en } to en
    }

    private fun loadCountries(): List<Country> {
        val root = readJson("metadata/countries.json") ?: return emptyList()
        val array = root.optJSONArray("countries") ?: return emptyList()
        val result = ArrayList<Country>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                Country(
                    iso2 = item.getString("iso2"),
                    nameAr = item.getString("nameAr"),
                    nameEn = item.getString("nameEn"),
                    callingCode = item.getString("callingCode"),
                    nationalPrefix = item.optString("nationalPrefix")
                        .takeIf { it.isNotBlank() },
                    flag = item.optString("flag"),
                    flagFallback = item.optBoolean("flagFallback", false),
                    support = when (item.optString("support")) {
                        "FULL" -> SupportTier.FULL
                        "FIXED_ONLY" -> SupportTier.FIXED_ONLY
                        else -> SupportTier.UNAVAILABLE
                    },
                    hasMobile = item.optBoolean("hasMobile"),
                    hasFixed = item.optBoolean("hasFixed"),
                    mobileLengths = item.optJSONArray("mobileLengths").toIntList(),
                    fixedLengths = item.optJSONArray("fixedLengths").toIntList(),
                    mobileCount = item.optLong("mobileCount"),
                    fixedCount = item.optLong("fixedCount"),
                    bothCount = item.optLong("bothCount"),
                    overlapCount = item.optLong("overlapCount"),
                    exampleMobile = item.optString("exampleMobile").takeIf { it.isNotBlank() },
                    exampleFixed = item.optString("exampleFixed").takeIf { it.isNotBlank() },
                    planVersion = item.optInt("planVersion", 1),
                    mainCountry = item.optString("mainCountry").takeIf { it.isNotBlank() },
                    sharedWith = item.optJSONArray("sharedWith").toStringList(),
                )
            )
        }
        return result.sortedBy { it.nameAr }
    }

    private fun loadEmergency(): List<EmergencyEntry> {
        val root = readJson("metadata/emergency.json") ?: return emptyList()
        val array = root.optJSONArray("entries") ?: return emptyList()
        val result = ArrayList<EmergencyEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                EmergencyEntry(
                    iso2 = item.getString("iso2"),
                    number = item.getString("number"),
                    category = item.optString("category", "GENERAL"),
                    labelAr = item.optString("labelAr", "طوارئ"),
                    labelEn = item.optString("labelEn", "Emergency"),
                    countryAr = item.getString("countryAr"),
                    countryEn = item.getString("countryEn"),
                    verification = item.optString("verification", "REFERENCE"),
                    source = item.optString("source"),
                    sourceUrl = item.optString("sourceUrl"),
                    checkedAt = item.optString("checkedAt").takeIf { it.isNotBlank() && it != "null" },
                    noteAr = item.optString("noteAr"),
                )
            )
        }
        return result
    }

    private fun loadSources(): List<SourceInfo> {
        val root = readJson("metadata/sources.json") ?: return emptyList()
        val array = root.optJSONArray("sources") ?: return emptyList()
        val result = ArrayList<SourceInfo>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result.add(
                SourceInfo(
                    name = item.optString("name"),
                    version = item.optString("version"),
                    url = item.optString("url"),
                    sha256 = item.optString("sha256"),
                    license = item.optString("license").takeIf { it.isNotBlank() },
                )
            )
        }
        return result
    }

    private fun readJson(path: String): JSONObject? = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { reader ->
            JSONObject(reader.readText())
        }
    }.getOrNull()
}

private fun org.json.JSONArray?.toIntList(): List<Int> {
    if (this == null) return emptyList()
    val result = ArrayList<Int>(length())
    for (index in 0 until length()) result.add(optInt(index))
    return result
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        val value = optString(index)
        if (value.isNotBlank()) result.add(value)
    }
    return result
}
