package com.numx.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class OperationKind { IDLE, GENERATE, DELETE, EXPORT, RESTORE, RESET, EMERGENCY }

data class OperationState(
    val kind: OperationKind = OperationKind.IDLE,
    val done: Long = 0,
    val total: Long = 0,
    val elapsedMs: Long = 0,
    val ratePerSecond: Double = 0.0,
    val etaMs: Long = 0,
    val skipped: Long = 0,
    val indeterminate: Boolean = false,
) {
    val isRunning: Boolean get() = kind != OperationKind.IDLE
    val progress: Float get() = if (total > 0) (done.toDouble() / total).toFloat() else 0f
}

data class UiState(
    val startInput: String = "0930000000",
    val countInput: String = "1000",
    val stats: Stats = Stats(),
    val operation: OperationState = OperationState(),
    val message: String = "",
    val lastExport: List<ExportedFile> = emptyList(),
    val lastExportLabel: String = "",
    val generationMode: GenerationMode = GenerationMode.CONTACTS,
    val exportFormat: ExportFormat = ExportFormat.VCARD,
    val storage: ContactStorageInfo = ContactStorageInfo.UNKNOWN,
    val cloudStorageAllowed: Boolean = false,
    val countries: List<Country> = emptyList(),
    val selectedIso: String = "SY",
    val lineType: LineType = LineType.MOBILE,
    val startNsn: String? = null,
    val startValid: Boolean = false,
    val availableFromStart: Long? = null,
    val emergencyIso: String = "SY",
    val lookupInput: String = "",
    val lookupIso: String = "SY",
    val lookupResult: LookupResult? = null,
    val lookupError: String? = null,
    val lookupRunning: Boolean = false,
    val emergencyEntries: List<EmergencyEntry> = emptyList(),
    val emergencyTotal: Int = 0,
    val emergencyCountries: Int = 0,
    val sources: List<SourceInfo> = emptyList(),
) {
    val selectedCountry: Country?
        get() = countries.firstOrNull { it.iso2 == selectedIso }
    val emergencyCountry: Country?
        get() = countries.firstOrNull { it.iso2 == emergencyIso }
    val lookupCountry: Country?
        get() = countries.firstOrNull { it.iso2 == lookupIso }
}

/**
 * Application-scoped operation engine. All long tasks run here, not in an
 * Activity/ViewModel, so leaving the app or recreating the Activity does not
 * cancel them. A foreground service keeps the process alive while they run.
 */
class OperationController(context: Context) {
    private val appContext = context.applicationContext
    private val database = NumXDatabase(appContext)
    private val metadata = MetadataRepository(appContext)
    private val engine = NumberingEngine(appContext)
    private val lookupEngine = LookupEngine(metadata)
    private val contacts = ContactsManager(appContext)
    private val exporter = ExportManager(appContext, database, engine, metadata)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val lock = Any()
    private var busy = false
    private var job: Job? = null

    init {
        scope.launch {
            runCatching { database.stats() }.onSuccess { stats ->
                _state.update { it.copy(stats = stats) }
            }
        }
        scope.launch {
            val countries = runCatching { metadata.countries() }.getOrDefault(emptyList())
            val sources = runCatching { metadata.sources() }.getOrDefault(emptyList())
            val emergencyTotal = runCatching { metadata.emergencyCount() }.getOrDefault(0)
            val emergencyCountries = runCatching { metadata.emergencyCountryCount() }.getOrDefault(0)
            val default = countries.firstOrNull { it.iso2 == "SY" } ?: countries.firstOrNull()
            _state.update {
                it.copy(
                    countries = countries,
                    sources = sources,
                    emergencyTotal = emergencyTotal,
                    emergencyCountries = emergencyCountries,
                    selectedIso = default?.iso2 ?: it.selectedIso,
                    emergencyIso = default?.iso2 ?: it.emergencyIso,
                    emergencyEntries = default?.let { country ->
                        runCatching { metadata.emergency(country.iso2) }.getOrDefault(emptyList())
                    } ?: it.emergencyEntries,
                    lookupIso = default?.iso2 ?: it.lookupIso,
                )
            }
            default?.let { country ->
                val type = when {
                    country.hasMobile -> LineType.MOBILE
                    country.hasFixed -> LineType.FIXED
                    else -> LineType.MOBILE
                }
                applySelection(country, type, resetStart = false)
            }
        }
        refreshStorageInfo()
    }

    /**
     * Re-reads the default contacts account after returning from system
     * settings, so changing the storage location takes effect immediately.
     */
    fun refreshStorageInfo() {
        scope.launch {
            val storage = withContext(Dispatchers.IO) {
                runCatching { contacts.storageInfo() }
                    .getOrDefault(ContactStorageInfo.UNKNOWN)
            }
            _state.update { current ->
                current.copy(
                    storage = storage,
                    cloudStorageAllowed = if (storage.isCloudOrSim) {
                        current.cloudStorageAllowed
                    } else {
                        false
                    },
                )
            }
        }
    }

    fun setCloudStorageAllowed(value: Boolean) {
        if (value && _state.value.storage.asAccountOrNull() == null) {
            _state.update {
                it.copy(message = "تعذر قراءة الحساب السحابي الافتراضي. اختر «تخزين الجهاز» من إعدادات جهات الاتصال.")
            }
            return
        }
        _state.update { it.copy(cloudStorageAllowed = value) }
    }

    fun setGenerationMode(value: GenerationMode) {
        if (_state.value.operation.isRunning) return
        _state.update { it.copy(generationMode = value, message = "") }
    }

    fun setExportFormat(value: ExportFormat) {
        if (_state.value.operation.isRunning) return
        _state.update { it.copy(exportFormat = value, message = "") }
    }

    fun setMessage(value: String) {
        _state.update { it.copy(message = value) }
    }

    fun selectCountry(iso2: String) {
        if (_state.value.operation.isRunning) return
        val country = _state.value.countries.firstOrNull { it.iso2 == iso2 } ?: return
        val current = _state.value
        val type = when {
            country.supports(current.lineType) -> current.lineType
            country.hasMobile -> LineType.MOBILE
            country.hasFixed -> LineType.FIXED
            else -> current.lineType
        }
        applySelection(country, type, resetStart = true)
    }

    fun setLineType(type: LineType) {
        if (_state.value.operation.isRunning) return
        val country = _state.value.selectedCountry ?: return
        if (!country.supports(type)) {
            _state.update {
                it.copy(message = "هذه الدولة لا تدعم النوع المختار. ${country.nameAr} ${country.support.labelAr}.")
            }
            return
        }
        applySelection(country, type, resetStart = true)
    }

    private fun applySelection(country: Country, type: LineType, resetStart: Boolean) {
        val first = engine.firstNumber(country, type)
        val start = when {
            !resetStart && _state.value.startInput.isNotBlank() -> _state.value.startInput
            first != null -> country.toLocalDisplay(first)
            else -> ""
        }
        _state.update {
            it.copy(
                selectedIso = country.iso2,
                lineType = type,
                startInput = start,
                message = "",
            )
        }
        refreshSelection()
    }

    fun updateStart(value: String) {
        _state.update { it.copy(startInput = value) }
        refreshSelection()
    }

    fun updateCount(value: String) {
        _state.update { it.copy(countInput = value) }
    }

    fun setCount(value: Long) {
        _state.update { it.copy(countInput = value.toString()) }
    }

    /** Selects the complete allocated range for the selected country and type. */
    fun selectFullCountry(format: ExportFormat) {
        if (_state.value.operation.isRunning) return
        val country = _state.value.selectedCountry ?: return
        val type = _state.value.lineType
        if (!country.supports(type)) {
            _state.update { it.copy(message = "النطاق الكامل غير متاح لهذه الدولة أو النوع.") }
            return
        }
        val total = country.countFor(type)
        if (total <= 0) {
            _state.update { it.copy(message = "لا توجد أرقام قابلة للتوليد لهذا النطاق.") }
            return
        }
        val first = engine.firstNumber(country, type)
        _state.update {
            it.copy(
                generationMode = GenerationMode.FILE,
                exportFormat = format,
                startInput = first?.let(country::toLocalDisplay) ?: "",
                countInput = total.toString(),
                message = "تم اختيار كل الأرقام المتاحة: ${SyrianNumbers.formatCount(total)} رقماً. " +
                    "استخدم التصدير المقسم للمجلد للحفاظ على ملفات قابلة للاستخدام.",
            )
        }
        refreshSelection()
    }

    private fun refreshSelection() {
        val current = _state.value
        val country = current.selectedCountry
        if (country == null) {
            _state.update { it.copy(startNsn = null, startValid = false, availableFromStart = null) }
            return
        }
        val type = current.lineType
        val nsn = normalizeNationalInput(
            country = country,
            type = type,
            input = current.startInput,
            isValidPrefix = { engine.isValidStart(country, type, it) },
        )
        val available = nsn?.let { engine.countFrom(country, type, it) }
        _state.update {
            it.copy(
                startNsn = nsn,
                startValid = nsn != null,
                availableFromStart = available,
            )
        }
    }

    fun startGeneration() {
        val current = _state.value
        if (current.generationMode != GenerationMode.CONTACTS) {
            _state.update { it.copy(message = "وضع الملف يكتب إلى الملف الذي تختاره، ولا يحفظ في جهات الاتصال.") }
            return
        }
        val country = current.selectedCountry ?: return
        val type = current.lineType
        if (!country.supports(type) || current.startNsn == null) {
            _state.update { it.copy(message = invalidStartMessage(country, type)) }
            return
        }
        val startNsn = current.startNsn
        val count = parseCount(current.countInput)
        if (count == null || count <= 0) {
            _state.update { it.copy(message = "أدخل عدداً صحيحاً أكبر من صفر") }
            return
        }
        if (count > CONTACTS_GENERATION_LIMIT) {
            _state.update {
                it.copy(
                    message = "الحد الأقصى في وضع جهات الاتصال ${SyrianNumbers.formatCount(CONTACTS_GENERATION_LIMIT.toLong())} رقم. " +
                        "للنطاقات الأكبر اختر وضع الملف.",
                )
            }
            return
        }
        val available = current.availableFromStart ?: 0
        if (count > available) {
            _state.update {
                it.copy(
                    message = "العدد المطلوب أكبر من المتاح من رقم البداية. " +
                        "المتاح: ${SyrianNumbers.formatCount(available)} رقم.",
                )
            }
            return
        }
        if (current.storage.isCloudOrSim && !current.cloudStorageAllowed) {
            _state.update { it.copy(message = current.storage.choiceRequiredMessage) }
            return
        }
        if (!beginOperation(OperationKind.GENERATE, count, indeterminate = true)) return

        OperationService.start(appContext, "جارٍ فحص جهات الاتصال وتوليد الأرقام...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var sessionId: Long? = null
            var generated = 0L
            var inserted = 0L
            var failures = 0L
            var skipped = 0L
            var lastNsn: String? = null
            var status = "failed"
            var failure: String? = null
            try {
                val storageNow = withContext(Dispatchers.IO) {
                    runCatching { contacts.storageInfo() }
                        .getOrDefault(ContactStorageInfo.UNKNOWN)
                }
                _state.update { state ->
                    state.copy(
                        storage = storageNow,
                        cloudStorageAllowed = if (storageNow.isCloudOrSim) {
                            state.cloudStorageAllowed
                        } else {
                            false
                        },
                    )
                }
                if (storageNow.isCloudOrSim && !_state.value.cloudStorageAllowed) {
                    throw ContactStorageException(storageNow.choiceRequiredMessage)
                }
                val account = if (storageNow.isCloudOrSim) storageNow.asAccountOrNull() else null

                val sid = withContext(Dispatchers.IO) {
                    database.createSession(startNsn, count, country.iso2, type)
                }
                sessionId = sid
                _state.update {
                    it.copy(
                        message = "جارٍ فحص جهات الاتصال الحالية...",
                        operation = OperationState(
                            kind = OperationKind.GENERATE,
                            indeterminate = true,
                        ),
                    )
                }

                withContext(Dispatchers.IO) {
                    database.prepareExistingNumberLookup()
                    try {
                        database.populateExistingNumberLookup(contacts.savedPhoneNumbers(country)) { scanned ->
                            coroutineContext.ensureActive()
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            _state.update { state ->
                                state.copy(
                                    operation = state.operation.copy(
                                        done = scanned,
                                        elapsedMs = elapsed,
                                    ),
                                    message = "جارٍ فحص جهات الاتصال الحالية... " +
                                        SyrianNumbers.formatCount(scanned),
                                )
                            }
                        }
                    } catch (e: Exception) {
                        database.clearExistingNumberLookup()
                        throw e
                    }
                }

                _state.update {
                    it.copy(
                        message = "جارٍ التوليد والحفظ...",
                        operation = OperationState(
                            kind = OperationKind.GENERATE,
                            total = count,
                        ),
                    )
                }

                val cursor = engine.cursor(country, type, startNsn)
                    ?: throw IllegalStateException("لا تتوفر خطة ترقيم لهذا النوع")
                var pending = ArrayList<GeneratedNumber>()
                while (inserted < count) {
                    coroutineContext.ensureActive()
                    val fresh: List<GeneratedNumber>
                    if (pending.isNotEmpty()) {
                        fresh = pending
                        pending = ArrayList()
                    } else {
                        val candidates = cursor.nextBatch(CANDIDATE_BATCH) ?: break
                        if (candidates.isEmpty()) break
                        val keys = candidates.flatMap { candidateKeys(it, country) }
                        val existing = withContext(Dispatchers.IO) {
                            database.existingNumbers(keys)
                        }
                        val newOnes = ArrayList<GeneratedNumber>(candidates.size)
                        for (number in candidates) {
                            if (candidateKeys(number, country).none { it in existing }) {
                                newOnes.add(number)
                            }
                        }
                        skipped += candidates.size - newOnes.size
                        if (newOnes.isEmpty()) {
                            _state.update { state ->
                                state.copy(
                                    operation = progressState(
                                        OperationKind.GENERATE,
                                        inserted,
                                        count,
                                        startedAt,
                                        skipped,
                                    ),
                                )
                            }
                            continue
                        }
                        fresh = newOnes
                    }

                    val remaining = count - inserted
                    val take = minOf(remaining, fresh.size.toLong()).toInt()
                    if (take <= 0) break
                    val attempted = fresh.subList(0, take)
                    pending = if (take < fresh.size) {
                        ArrayList(fresh.subList(take, fresh.size))
                    } else {
                        ArrayList()
                    }
                    lastNsn = attempted.lastOrNull()?.nsn ?: lastNsn

                    withContext(NonCancellable) {
                        val drafts = attempted.map { generatedNumber ->
                            contactDraft(generatedNumber, country, "S$sid")
                        }
                        val result = contacts.insertDrafts(drafts, account)
                        if (result.inserted.isEmpty() && attempted.isNotEmpty()) {
                            throw IllegalStateException(
                                "لم يتم حفظ أي رقم في هذه الدفعة (${result.failed} فشل)"
                            )
                        }
                        val byValue = attempted.associateBy { it.e164 }
                        val rows = result.inserted.mapNotNull { insertedContact ->
                            val generatedNumber = byValue[insertedContact.draft.value] ?: return@mapNotNull null
                            insertedContact.rawContactId to generatedNumber
                        }
                        withContext(Dispatchers.IO) {
                            database.recordInsertedBatch(
                                sessionId = sid,
                                numbers = rows.map { (_, number) ->
                                    InsertedNumber(
                                        number = number.e164,
                                        e164 = number.e164,
                                        iso2 = country.iso2,
                                        lineType = number.lineType.code,
                                        kind = NumXDatabase.KIND_GENERATED,
                                    )
                                },
                                rawContactIds = rows.map { it.first },
                                generatedCount = attempted.size.toLong(),
                            )
                        }
                        generated += attempted.size
                        inserted += rows.size
                        failures += result.failed
                    }

                    _state.update { state ->
                        state.copy(
                            operation = progressState(
                                OperationKind.GENERATE,
                                inserted,
                                count,
                                startedAt,
                                skipped,
                            ),
                        )
                    }
                }
                status = if (inserted >= count) "completed" else "exhausted"
            } catch (e: CancellationException) {
                status = "canceled"
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                status = "failed"
            } finally {
                val duration = SystemClock.elapsedRealtime() - startedAt
                val rate = if (duration > 0) inserted * 1000.0 / duration else 0.0
                withContext(NonCancellable) {
                    runCatching {
                        withContext(Dispatchers.IO) { database.clearExistingNumberLookup() }
                    }
                    sessionId?.let { sid ->
                        runCatching {
                            withContext(Dispatchers.IO) {
                                database.finishSession(sid, status, generated, inserted, duration, rate)
                            }
                        }
                    }
                    val stats = runCatching {
                        withContext(Dispatchers.IO) { database.stats() }
                    }.getOrDefault(_state.value.stats)
                    OperationService.stop(appContext)
                    val nextStart = lastNsn?.let { last ->
                        val nextCursor = engine.cursor(country, type, incrementDecimal(last))
                        nextCursor?.nextBatch(1)?.firstOrNull()?.nsn
                            ?.let(country::toLocalDisplay)
                    }
                    _state.update { state ->
                        state.copy(
                            stats = stats,
                            operation = OperationState(),
                            startInput = nextStart ?: state.startInput,
                            message = generationMessage(
                                country,
                                type,
                                status,
                                inserted,
                                skipped,
                                failures,
                                failure,
                            ),
                        )
                    }
                    refreshSelection()
                }
            }
        }
        track(operationJob)
    }

    fun cancel() {
        job?.cancel()
    }

    fun deleteAllAdded() {
        if (!beginOperation(OperationKind.DELETE, 0, indeterminate = false)) return
        OperationService.start(appContext, "جارٍ حذف الأرقام المضافة...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var deleted = 0L
            var failure: String? = null
            var canceled = false
            var noData = false
            var total = 0L
            try {
                total = withContext(Dispatchers.IO) {
                    database.countAdded() + database.countEmergencyAdded()
                }
                if (total == 0L) {
                    noData = true
                    return@launch
                }
                _state.update {
                    it.copy(
                        message = "جارٍ حذف الأرقام المضافة...",
                        operation = OperationState(kind = OperationKind.DELETE, total = total),
                    )
                }
                while (true) {
                    coroutineContext.ensureActive()
                    val ids = withContext(Dispatchers.IO) { database.loadAddedIdBatch(DELETE_BATCH) }
                    if (ids.isEmpty()) break
                    withContext(NonCancellable) {
                        contacts.deleteRawContacts(ids)
                        withContext(Dispatchers.IO) { database.deleteAddedRecords(ids) }
                    }
                    deleted += ids.size
                    _state.update {
                        it.copy(
                            operation = progressState(
                                OperationKind.DELETE,
                                deleted,
                                total,
                                startedAt,
                            ),
                        )
                    }
                }
                while (true) {
                    coroutineContext.ensureActive()
                    val ids = withContext(Dispatchers.IO) { database.loadEmergencyIdBatch(DELETE_BATCH) }
                    if (ids.isEmpty()) break
                    withContext(NonCancellable) {
                        contacts.deleteRawContacts(ids)
                        withContext(Dispatchers.IO) { database.deleteEmergencyRecords(ids) }
                    }
                    deleted += ids.size
                    _state.update {
                        it.copy(
                            operation = progressState(
                                OperationKind.DELETE,
                                deleted,
                                total,
                                startedAt,
                            ),
                        )
                    }
                }
            } catch (e: CancellationException) {
                canceled = true
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                OperationService.stop(appContext)
                val stats = runCatching {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { database.stats() }
                    }
                }.getOrDefault(_state.value.stats)
                _state.update {
                    it.copy(
                        stats = stats,
                        operation = OperationState(),
                        message = when {
                            noData -> "لا توجد أرقام مضافة عبر التطبيق"
                            canceled -> "تم إلغاء الحذف بعد حذف ${SyrianNumbers.formatCount(deleted)} رقم"
                            failure != null -> "تم حذف ${SyrianNumbers.formatCount(deleted)} رقم ثم توقف: $failure"
                            else -> "تم حذف ${SyrianNumbers.formatCount(deleted)} رقم"
                        },
                    )
                }
            }
        }
        track(operationJob)
    }

    fun resetStats() {
        if (!beginOperation(OperationKind.RESET, 0, indeterminate = true)) return
        val operationJob = scope.launch {
            try {
                _state.update { it.copy(message = "جارٍ تصفير الإحصائيات...") }
                val stats = withContext(Dispatchers.IO) {
                    database.resetStats()
                    database.stats()
                }
                _state.update {
                    it.copy(
                        stats = stats,
                        operation = OperationState(),
                        message = "تم تصفير الإحصائيات. الأرقام المضافة ما زالت محفوظة ويمكن حذفها.",
                    )
                }
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(operation = OperationState(), message = "تم إلغاء تصفير الإحصائيات")
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        operation = OperationState(),
                        message = "تعذر تصفير الإحصائيات: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
        track(operationJob)
    }

    /**
     * Rebuilds the local ownership table from the neutral note marker stored in
     * contacts. This is what makes deletion and continuation possible after the
     * app data was removed by an uninstall or a "clear data" action.
     */
    fun restoreFromContacts() {
        if (!beginOperation(OperationKind.RESTORE, 0, indeterminate = true)) return
        OperationService.start(appContext, "جارٍ استعادة السجل من جهات الاتصال...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            _state.update {
                it.copy(
                    message = "جارٍ فحص جهات الاتصال...",
                    operation = OperationState(
                        kind = OperationKind.RESTORE,
                        indeterminate = true,
                    ),
                )
            }
            try {
                val (result, stats) = withContext(Dispatchers.IO) {
                    val restored = database.restoreAddedContacts(contacts.ownedContacts()) { scanned ->
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        _state.update { current ->
                            current.copy(
                                operation = OperationState(
                                    kind = OperationKind.RESTORE,
                                    done = scanned,
                                    elapsedMs = elapsed,
                                    indeterminate = true,
                                ),
                                message = "جارٍ فحص جهات الاتصال... " +
                                    SyrianNumbers.formatCount(scanned),
                            )
                        }
                    }
                    restored to database.stats()
                }
                val next: Triple<String, String, LineType>? = result.lastE164
                    ?.takeIf { it.startsWith("+") }
                    ?.let { e164 ->
                        val country = result.lastIso2?.let(metadata::country) ?: return@let null
                        val type = when (result.lastLineType) {
                            "FIXED" -> LineType.FIXED
                            "AMBIGUOUS" -> LineType.BOTH
                            else -> LineType.MOBILE
                        }
                        if (!country.supports(type)) return@let null
                        val digits = e164.removePrefix("+")
                        if (!digits.startsWith(country.callingCode)) return@let null
                        val nsn = digits.removePrefix(country.callingCode)
                        val nextNumber = engine
                            .cursor(country, type, incrementDecimal(nsn))
                            ?.nextBatch(1)
                            ?.firstOrNull()
                            ?.nsn ?: return@let null
                        Triple(country.iso2, country.toLocalDisplay(nextNumber), type)
                    }
                    ?: result.lastNumber
                        ?.takeIf { it.startsWith("09") && it.length == 10 }
                        ?.let { last ->
                            SyrianNumbers.nextAfter(last)?.let {
                                Triple("SY", it, LineType.MOBILE)
                            }
                        }
                _state.update { current ->
                    val start = next?.second ?: current.startInput
                    current.copy(
                        stats = stats,
                        operation = OperationState(),
                        selectedIso = next?.first ?: current.selectedIso,
                        lineType = next?.third ?: current.lineType,
                        startInput = start,
                        message = when {
                            result.found == 0L && result.emergencyFound == 0L ->
                                "لم يتم العثور على أرقام سابقة تحمل وسم الاستعادة"
                            else -> buildString {
                                append("تمت استعادة ${SyrianNumbers.formatCount(result.found)} رقماً")
                                if (result.emergencyFound > 0) {
                                    append(" و${SyrianNumbers.formatCount(result.emergencyFound)} جهة طوارئ")
                                }
                                result.lastNumber?.let { append(". آخر رقم: $it") }
                                if (next != null) append(" — تم ضبط البداية على ${next.second}.")
                            }
                        },
                    )
                }
                refreshSelection()
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(operation = OperationState(), message = "تم إلغاء استعادة السجل")
                }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        operation = OperationState(),
                        message = "تعذر استعادة السجل: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            } finally {
                OperationService.stop(appContext)
            }
        }
        track(operationJob)
    }

    /**
     * Generates the selected range directly into one SAF document. Ranges
     * larger than the split threshold must use a directory export so that no
     * single file becomes impossible to open or import.
     */
    fun startFileGeneration(uriString: String) {
        val current = _state.value
        if (current.generationMode != GenerationMode.FILE) {
            _state.update { it.copy(message = "اختر وضع الملف أولاً.") }
            return
        }
        val country = current.selectedCountry ?: return
        val type = current.lineType
        if (!country.supports(type) || current.startNsn == null) {
            _state.update { it.copy(message = invalidStartMessage(country, type)) }
            return
        }
        val count = parseCount(current.countInput)
        if (count == null || count <= 0) {
            _state.update { it.copy(message = "أدخل عدداً صحيحاً أكبر من صفر") }
            return
        }
        if (ExportManager.requiresSplit(count)) {
            _state.update {
                it.copy(
                    message = "هذا النطاق أكبر من ${SyrianNumbers.formatCount(ExportManager.splitRows)} رقم. " +
                        "استخدم «تصدير مقسم إلى مجلد» ليُكتب على أجزاء مع manifest وSHA-256.",
                )
            }
            return
        }
        val available = current.availableFromStart ?: 0
        if (count > available) {
            _state.update {
                it.copy(
                    message = "العدد المطلوب أكبر من المتاح من رقم البداية. المتاح: ${SyrianNumbers.formatCount(available)} رقم.",
                )
            }
            return
        }
        val uri = parseDocumentUri(uriString)
        if (uri == null) {
            _state.update { it.copy(message = "لم يتم اختيار ملف وجهة صالح.") }
            return
        }
        startFileOperation(
            country = country,
            type = type,
            startNsn = current.startNsn,
            count = count,
            format = current.exportFormat,
            splitTree = null,
            singleUri = uri,
        )
    }

    fun startFileGenerationToTree(uriString: String) {
        val current = _state.value
        if (current.generationMode != GenerationMode.FILE) {
            _state.update { it.copy(message = "اختر وضع الملف أولاً.") }
            return
        }
        val country = current.selectedCountry ?: return
        val type = current.lineType
        if (!country.supports(type) || current.startNsn == null) {
            _state.update { it.copy(message = invalidStartMessage(country, type)) }
            return
        }
        val count = parseCount(current.countInput)
        if (count == null || count <= 0) {
            _state.update { it.copy(message = "أدخل عدداً صحيحاً أكبر من صفر") }
            return
        }
        val available = current.availableFromStart ?: 0
        if (count > available) {
            _state.update {
                it.copy(
                    message = "العدد المطلوب أكبر من المتاح من رقم البداية. المتاح: ${SyrianNumbers.formatCount(available)} رقم.",
                )
            }
            return
        }
        val tree = parseDocumentUri(uriString)
        if (tree == null) {
            _state.update { it.copy(message = "لم يتم اختيار مجلد وجهة صالح.") }
            return
        }
        startFileOperation(
            country = country,
            type = type,
            startNsn = current.startNsn,
            count = count,
            format = current.exportFormat,
            splitTree = tree,
            singleUri = null,
        )
    }

    private fun startFileOperation(
        country: Country,
        type: LineType,
        startNsn: String,
        count: Long,
        format: ExportFormat,
        splitTree: Uri?,
        singleUri: Uri?,
    ) {
        if (!beginOperation(OperationKind.GENERATE, count, indeterminate = false)) return
        OperationService.start(appContext, "جارٍ كتابة ملف ${format.label}...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var sessionId: Long? = null
            var done = 0L
            var status = "failed"
            var failure: String? = null
            var result: ExportResult? = null
            try {
                val sid = withContext(Dispatchers.IO) {
                    database.createSession(startNsn, count, country.iso2, type)
                }
                sessionId = sid
                _state.update {
                    it.copy(
                        message = if (splitTree != null) {
                            "جارٍ إنشاء ملفات مقسمة مع manifest وSHA-256..."
                        } else {
                            "جارٍ كتابة ${format.label} إلى الملف المحدد..."
                        },
                        operation = OperationState(kind = OperationKind.GENERATE, total = count),
                    )
                }
                val exported = if (splitTree != null) {
                    exporter.exportRangeToDirectory(
                        treeUri = splitTree,
                        country = country,
                        lineType = type,
                        startNsn = startNsn,
                        count = count,
                        format = format,
                    ) { progressed, total ->
                        done = progressed
                        _state.update { state ->
                            state.copy(
                                operation = progressState(
                                    OperationKind.GENERATE,
                                    progressed,
                                    total,
                                    startedAt,
                                ),
                            )
                        }
                    }
                } else {
                    exporter.exportRangeToUri(
                        uri = singleUri!!,
                        country = country,
                        lineType = type,
                        startNsn = startNsn,
                        count = count,
                        format = format,
                    ) { progressed, total ->
                        done = progressed
                        _state.update { state ->
                            state.copy(
                                operation = progressState(
                                    OperationKind.GENERATE,
                                    progressed,
                                    total,
                                    startedAt,
                                ),
                            )
                        }
                    }
                }
                done = exported.exportedCount
                result = exported
                status = "completed"
            } catch (e: CancellationException) {
                status = "canceled"
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                status = "failed"
            } finally {
                val duration = SystemClock.elapsedRealtime() - startedAt
                withContext(NonCancellable) {
                    sessionId?.let { sid ->
                        runCatching {
                            withContext(Dispatchers.IO) {
                                database.recordFileGenerated(
                                    sid,
                                    done,
                                    result?.mobileCount ?: 0,
                                    result?.fixedCount ?: 0,
                                )
                                database.finishSession(
                                    sessionId = sid,
                                    status = status,
                                    generated = done,
                                    inserted = 0,
                                    durationMs = duration,
                                    ratePerSecond = 0.0,
                                )
                            }
                        }
                    }
                    val stats = runCatching {
                        withContext(Dispatchers.IO) { database.stats() }
                    }.getOrDefault(_state.value.stats)
                    OperationService.stop(appContext)
                    _state.update { state ->
                        state.copy(
                            stats = stats,
                            operation = OperationState(),
                            lastExport = result?.files ?: state.lastExport,
                            lastExportLabel = result?.files?.let { files ->
                                "${format.label} — ${files.firstOrNull()?.displayName.orEmpty()}" +
                                    if (files.size > 1) {
                                        " (${files.size} ملفات)"
                                    } else {
                                        ""
                                    }
                            } ?: state.lastExportLabel,
                            message = fileGenerationMessage(status, done, result, failure),
                        )
                    }
                }
            }
        }
        track(operationJob)
    }

    /** Exports only numbers that NumX previously inserted into contacts. */
    fun exportAddedToFile(uriString: String) {
        val format = _state.value.exportFormat
        val total = _state.value.stats.addedCount + _state.value.stats.emergencyAdded
        if (ExportManager.requiresSplit(total)) {
            _state.update {
                it.copy(
                    message = "عدد الجهات المضافة أكبر من ${SyrianNumbers.formatCount(ExportManager.splitRows)}. " +
                        "استخدم «تصدير مقسم إلى مجلد».",
                )
            }
            return
        }
        val uri = parseDocumentUri(uriString)
        if (uri == null) {
            _state.update { it.copy(message = "لم يتم اختيار ملف وجهة صالح.") }
            return
        }
        exportAdded(format, singleUri = uri, treeUri = null)
    }

    fun exportAddedToDirectory(uriString: String) {
        val tree = parseDocumentUri(uriString)
        if (tree == null) {
            _state.update { it.copy(message = "لم يتم اختيار مجلد وجهة صالح.") }
            return
        }
        exportAdded(_state.value.exportFormat, singleUri = null, treeUri = tree)
    }

    private fun exportAdded(format: ExportFormat, singleUri: Uri?, treeUri: Uri?) {
        if (!beginOperation(OperationKind.EXPORT, 0, indeterminate = false)) return
        OperationService.start(appContext, "جارٍ تصدير الأرقام المضافة...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var total = 0L
            var noData = false
            try {
                total = withContext(Dispatchers.IO) {
                    database.countAdded() + database.countEmergencyAdded()
                }
                if (total == 0L) {
                    noData = true
                    return@launch
                }
                _state.update {
                    it.copy(
                        message = "جارٍ كتابة ${format.label}...",
                        operation = OperationState(kind = OperationKind.EXPORT, total = total),
                    )
                }
                val result = if (treeUri != null) {
                    exporter.exportAddedToDirectory(treeUri, format) { done, count ->
                        _state.update {
                            it.copy(
                                operation = progressState(
                                    OperationKind.EXPORT,
                                    done,
                                    count,
                                    startedAt,
                                ),
                            )
                        }
                    }
                } else {
                    exporter.exportAddedToUri(singleUri!!, format) { done, count ->
                        _state.update {
                            it.copy(
                                operation = progressState(
                                    OperationKind.EXPORT,
                                    done,
                                    count,
                                    startedAt,
                                ),
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(
                        operation = OperationState(),
                        lastExport = result.files,
                        lastExportLabel = "${format.label} — ${result.files.firstOrNull()?.displayName.orEmpty()}" +
                            if (result.files.size > 1) " (${result.files.size} ملفات)" else "",
                        message = "تم تصدير ${SyrianNumbers.formatCount(result.exportedCount)} رقم إلى المكان المحدد.",
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(operation = OperationState(), message = "تم إلغاء التصدير") }
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        operation = OperationState(),
                        message = if (noData) "لا توجد أرقام مضافة للتصدير" else
                            "تعذر التصدير: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            } finally {
                OperationService.stop(appContext)
                if (noData) {
                    _state.update {
                        it.copy(
                            operation = OperationState(),
                            message = "لا توجد أرقام مضافة للتصدير",
                        )
                    }
                }
            }
        }
        track(operationJob)
    }

    fun setLookupInput(value: String) {
        _state.update { it.copy(lookupInput = value, lookupError = null) }
    }

    fun setLookupCountry(iso2: String) {
        if (_state.value.countries.none { it.iso2 == iso2 }) return
        _state.update { it.copy(lookupIso = iso2, lookupResult = null, lookupError = null) }
    }

    fun lookup() {
        val current = _state.value
        if (current.lookupRunning) return
        if (current.lookupInput.isBlank()) {
            _state.update { it.copy(lookupError = "أدخل رقماً للبحث.", lookupResult = null) }
            return
        }
        _state.update { it.copy(lookupRunning = true, lookupError = null) }
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { lookupEngine.lookup(current.lookupInput, current.lookupIso) }
                    .getOrElse { error ->
                        LookupResult(
                            input = current.lookupInput,
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
                            sources = emptyList(),
                            notes = emptyList(),
                            error = "تعذر البحث: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
            }
            _state.update {
                it.copy(
                    lookupRunning = false,
                    lookupResult = result,
                    lookupError = result.error,
                )
            }
        }
    }

    fun setEmergencyCountry(iso2: String) {
        if (_state.value.countries.none { it.iso2 == iso2 }) return
        val entries = runCatching { metadata.emergency(iso2) }.getOrDefault(emptyList())
        _state.update { it.copy(emergencyIso = iso2, emergencyEntries = entries) }
    }

    /**
     * Adds explicitly selected emergency/service numbers as contacts. Nothing
     * is dialled and nothing is saved until this method is called by the user's
     * add action.
     */
    fun addEmergencyContacts(entries: List<EmergencyEntry>) {
        if (entries.isEmpty() || _state.value.operation.isRunning) return
        val storage = _state.value.storage
        if (storage.isCloudOrSim && !_state.value.cloudStorageAllowed) {
            _state.update { it.copy(message = storage.choiceRequiredMessage) }
            return
        }
        if (!beginOperation(OperationKind.EMERGENCY, entries.size.toLong(), indeterminate = false)) return
        OperationService.start(appContext, "جارٍ إضافة جهات الطوارئ...")
        val operationJob = scope.launch {
            var added = 0
            var failure: String? = null
            try {
                val storageNow = withContext(Dispatchers.IO) {
                    runCatching { contacts.storageInfo() }
                        .getOrDefault(ContactStorageInfo.UNKNOWN)
                }
                if (storageNow.isCloudOrSim && !_state.value.cloudStorageAllowed) {
                    throw ContactStorageException(storageNow.choiceRequiredMessage)
                }
                val account = if (storageNow.isCloudOrSim) storageNow.asAccountOrNull() else null
                val drafts = entries.map { emergencyDraft(it) }
                val result = contacts.insertDrafts(drafts, account)
                val entryByDraft = drafts.mapIndexed { index, draft -> draft to entries[index] }.toMap()
                val rows = result.inserted.mapNotNull { inserted ->
                    val entry = entryByDraft[inserted.draft] ?: return@mapNotNull null
                    entry to inserted.rawContactId
                }
                withContext(Dispatchers.IO) {
                    database.recordEmergencyBatch(
                        entries = rows.map { (entry, _) ->
                            EmergencyRecord(entry.number, entry.iso2, entry.category, entry.labelAr)
                        },
                        rawContactIds = rows.map { it.second },
                    )
                }
                added = rows.size
                failure = if (result.failed > 0) "${result.failed} فشل" else null
            } catch (e: CancellationException) {
                _state.update {
                    it.copy(operation = OperationState(), message = "تم إلغاء إضافة جهات الطوارئ")
                }
                throw e
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                val stats = runCatching {
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) { database.stats() }
                    }
                }.getOrDefault(_state.value.stats)
                OperationService.stop(appContext)
                _state.update {
                    it.copy(
                        stats = stats,
                        operation = OperationState(),
                        message = when {
                            added == 0 && failure != null -> "تعذرت إضافة جهات الطوارئ: $failure"
                            added == 0 -> "لم تتم إضافة أي جهة طوارئ"
                            failure != null -> "تمت إضافة $added جهة طوارئ، مع $failure"
                            else -> "تمت إضافة $added جهة طوارئ. لم يتم الاتصال بأي رقم."
                        },
                    )
                }
            }
        }
        track(operationJob)
    }

    fun shareLastExport() {
        val files = _state.value.lastExport
        if (files.isEmpty()) return
        runCatching { exporter.share(files) }.onFailure { e ->
            _state.update { it.copy(message = "تعذر المشاركة: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun contactDraft(
        number: GeneratedNumber,
        country: Country,
        batch: String,
    ): ContactDraft {
        val note = ContactIdentity.noteN2(
            e164 = number.e164,
            iso2 = country.iso2,
            lineType = number.lineType,
            planVersion = country.planVersion,
            batch = batch,
        )
        val label = when (number.lineType) {
            ActualLineType.MOBILE -> null
            ActualLineType.FIXED -> "أرضي"
            ActualLineType.AMBIGUOUS -> "أرضي أو خليوي (غير متميز)"
        }
        return ContactDraft(
            value = number.e164,
            displayName = ContactIdentity.displayNameE164(number.e164),
            note = note,
            phoneType = if (number.lineType == ActualLineType.MOBILE) {
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            } else {
                ContactsContract.CommonDataKinds.Phone.TYPE_OTHER
            },
            phoneLabel = label,
        )
    }

    private fun emergencyDraft(entry: EmergencyEntry): ContactDraft = ContactDraft(
        value = entry.number,
        displayName = entry.displayNameAr,
        note = ContactIdentity.noteE1(
            iso2 = entry.iso2,
            number = entry.number,
            category = entry.category,
            label = entry.labelAr,
        ),
        phoneType = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
        phoneLabel = "طوارئ",
    )

    private fun candidateKeys(number: GeneratedNumber, country: Country): List<String> = listOf(
        number.nsn,
        number.e164.removePrefix("+"),
        country.toLocalDisplay(number.nsn),
    )

    private fun invalidStartMessage(country: Country, type: LineType): String {
        val example = country.exampleFor(type)
            ?: engine.firstNumber(country, type)
        return "رقم البداية غير صالح لهذه الدولة أو النوع. مثال: " +
            (example?.let(country::toLocalDisplay) ?: "غير متوفر") +
            " — الصيغ المقبولة: محلية أو +${country.callingCode}..."
    }

    private fun generationMessage(
        country: Country,
        type: LineType,
        status: String,
        inserted: Long,
        skipped: Long,
        failures: Long,
        failure: String?,
    ): String = buildString {
        when (status) {
            "completed" -> append(
                "تم حفظ ${SyrianNumbers.formatCount(inserted)} رقم ${type.labelAr} من ${country.nameAr} في جهات الاتصال",
            )
            "canceled" -> append("تم الإلغاء بعد حفظ ${SyrianNumbers.formatCount(inserted)} رقم")
            "exhausted" -> {
                if (inserted == 0L && skipped > 0L) {
                    append("لم يتم حفظ أرقام جديدة؛ جميع الأرقام المتبقية محفوظة مسبقاً")
                } else {
                    append("تم حفظ ${SyrianNumbers.formatCount(inserted)} رقم (نفدت الأرقام المسموحة)")
                }
            }
            else -> append("توقف التوليد: ${failure ?: "خطأ غير معروف"}")
        }
        if (skipped > 0L) {
            append("، وتم تخطي ${SyrianNumbers.formatCount(skipped)} رقماً محفوظاً مسبقاً")
        }
        if (failures > 0L) {
            append("، وفشل ${SyrianNumbers.formatCount(failures)}")
        }
    }

    private fun fileGenerationMessage(
        status: String,
        done: Long,
        result: ExportResult?,
        failure: String?,
    ): String = when (status) {
        "completed" -> buildString {
            append("تم حفظ ${SyrianNumbers.formatCount(done)} رقماً")
            result?.files?.firstOrNull()?.let { append(" — ${it.displayName}") }
            if ((result?.files?.size ?: 0) > 1) {
                append(" مع manifest وchecksums (${result!!.files.size} ملفات).")
            } else {
                append(".")
            }
        }
        "canceled" ->
            "تم إلغاء التوليد بعد كتابة ${SyrianNumbers.formatCount(done)} رقم. الملفات الجزئية محفوظة في المكان الذي اخترته."
        else -> "تعذر إنشاء الملف: ${failure ?: "خطأ غير معروف"}"
    }

    private fun parseDocumentUri(value: String): Uri? {
        val uri = runCatching { value.toUri() }.getOrNull() ?: return null
        return uri.takeIf { !it.scheme.isNullOrBlank() }
    }

    private fun parseCount(input: String): Long? =
        SyrianNumbers.normalizeDigits(input)
            .replace(",", "").replace("،", "").replace(" ", "").trim().toLongOrNull()

    private fun progressState(
        kind: OperationKind,
        done: Long,
        total: Long,
        startedAt: Long,
        skipped: Long = 0,
    ): OperationState {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val rate = if (elapsed > 0) done * 1000.0 / elapsed else 0.0
        val eta = if (rate > 0 && done < total) ((total - done) / rate * 1000).toLong() else 0
        return OperationState(
            kind = kind,
            done = done,
            total = total,
            elapsedMs = elapsed,
            ratePerSecond = rate,
            etaMs = eta,
            skipped = skipped,
        )
    }

    /**
     * Marks an operation busy synchronously, before its coroutine gets a chance
     * to suspend. This closes the double-tap race where two operations could
     * otherwise start before the UI disables the buttons.
     */
    private fun beginOperation(
        kind: OperationKind,
        total: Long,
        indeterminate: Boolean,
    ): Boolean {
        synchronized(lock) {
            if (busy) return false
            busy = true
        }
        _state.update {
            it.copy(
                operation = OperationState(
                    kind = kind,
                    total = total,
                    indeterminate = indeterminate,
                ),
            )
        }
        return true
    }

    private fun track(operationJob: Job) {
        job = operationJob
        operationJob.invokeOnCompletion {
            _state.update { current ->
                if (current.operation.isRunning) current.copy(operation = OperationState()) else current
            }
            synchronized(lock) { busy = false }
        }
    }

    companion object {
        // Kept deliberately small: progress is published after each batch
        // reaches the contacts provider, so a large batch would leave the UI
        // showing 0 / N for a long time on slower devices.
        private const val CANDIDATE_BATCH = 250
        private const val DELETE_BATCH = 500
    }
}
