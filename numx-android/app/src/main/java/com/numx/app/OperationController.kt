package com.numx.app

import android.content.Context
import android.net.Uri
import android.os.SystemClock
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

enum class OperationKind { IDLE, GENERATE, DELETE, EXPORT, RESTORE, RESET }

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
)

/**
 * Application-scoped operation engine. All long tasks run here, not in an
 * Activity/ViewModel, so leaving the app or recreating the Activity does not
 * cancel them. A foreground service keeps the process alive while they run.
 */
class OperationController(context: Context) {
    private val appContext = context.applicationContext
    private val database = NumXDatabase(appContext)
    private val contacts = ContactsManager(appContext)
    private val exporter = ExportManager(appContext, database)
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

    /** Selects the complete allocated range: every prefix and every network. */
    fun selectFullCountry(format: ExportFormat) {
        if (_state.value.operation.isRunning) return
        _state.update {
            it.copy(
                generationMode = GenerationMode.FILE,
                exportFormat = format,
                startInput = SyrianNumbers.FIRST_ALLOCATED_NUMBER,
                countInput = SyrianNumbers.totalAllocatedNumbers.toString(),
                message = "تم اختيار كل الأرقام المتاحة: ${SyrianNumbers.formatCount(SyrianNumbers.totalAllocatedNumbers.toLong())} رقماً. اختر الصيغة ثم مكان الحفظ.",
            )
        }
    }

    fun updateStart(value: String) {
        _state.update { it.copy(startInput = value) }
    }

    fun updateCount(value: String) {
        _state.update { it.copy(countInput = value) }
    }

    fun setCount(value: Int) {
        _state.update { it.copy(countInput = value.toString()) }
    }

    fun startGeneration() {
        if (_state.value.generationMode != GenerationMode.CONTACTS) {
            _state.update { it.copy(message = "وضع الملف يكتب إلى الملف الذي تختاره، ولا يحفظ في جهات الاتصال.") }
            return
        }
        val start = SyrianNumbers.normalize(_state.value.startInput)
        if (start == null) {
            _state.update { it.copy(message = "رقم البداية غير صالح. يجب أن يبدأ بـ 091/092/093/094/095/096/098/099") }
            return
        }
        val count = parseCount(_state.value.countInput)
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
        val storage = _state.value.storage
        if (storage.isCloudOrSim && !_state.value.cloudStorageAllowed) {
            _state.update { it.copy(message = storage.choiceRequiredMessage) }
            return
        }
        if (!beginOperation(OperationKind.GENERATE, 0, indeterminate = true)) return

        OperationService.start(appContext, "جارٍ فحص جهات الاتصال وتوليد الأرقام...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var sessionId: Long? = null
            var generated = 0L
            var inserted = 0L
            var failures = 0L
            var skipped = 0L
            var lastSaved: String? = null
            var status = "failed"
            var failure: String? = null
            try {
                // Re-check immediately before writing. This also covers the
                // case where the user changed the system default after the
                // screen was last resumed.
                val storageNow = withContext(Dispatchers.IO) {
                    runCatching { contacts.storageInfo() }
                        .getOrDefault(ContactStorageInfo.UNKNOWN)
                }
                _state.update { current ->
                    current.copy(
                        storage = storageNow,
                        cloudStorageAllowed = if (storageNow.isCloudOrSim) {
                            current.cloudStorageAllowed
                        } else {
                            false
                        },
                    )
                }
                if (storageNow.isCloudOrSim && !_state.value.cloudStorageAllowed) {
                    throw ContactStorageException(storageNow.choiceRequiredMessage)
                }
                val account = if (storageNow.isCloudOrSim) {
                    storageNow.asAccountOrNull()
                } else {
                    null
                }

                val sid = withContext(Dispatchers.IO) {
                    database.createSession(start, count)
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
                        database.populateExistingNumberLookup(contacts.savedPhoneNumbers()) { scanned ->
                            coroutineContext.ensureActive()
                            val elapsed = SystemClock.elapsedRealtime() - startedAt
                            _state.update { current ->
                                current.copy(
                                    operation = current.operation.copy(
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
                            total = count.toLong(),
                        ),
                    )
                }

                val cursor = SyrianNumberCursor(start)
                var pending = ArrayList<String>()
                while (inserted < count) {
                    coroutineContext.ensureActive()
                    val fresh: List<String>
                    if (pending.isNotEmpty()) {
                        fresh = pending
                        pending = ArrayList()
                    } else {
                        val candidates = cursor.nextBatch(CANDIDATE_BATCH) ?: break
                        if (candidates.isEmpty()) break
                        val existing = withContext(Dispatchers.IO) {
                            database.existingNumbers(candidates)
                        }
                        val newOnes = ArrayList<String>(candidates.size)
                        for (number in candidates) {
                            if (number !in existing) newOnes.add(number)
                        }
                        skipped += candidates.size - newOnes.size
                        if (newOnes.isEmpty()) {
                            _state.update { current ->
                                current.copy(
                                    operation = progressState(
                                        OperationKind.GENERATE,
                                        inserted,
                                        count.toLong(),
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

                    // Finish a provider batch and its counters inside
                    // NonCancellable, so cancellation cannot leave the UI
                    // claiming that zero numbers were saved after a saved batch.
                    withContext(NonCancellable) {
                        val result = contacts.insertNumbers(attempted, account)
                        if (result.inserted.isEmpty() && attempted.isNotEmpty()) {
                            throw IllegalStateException(
                                "لم يتم حفظ أي رقم في هذه الدفعة (${result.failed} فشل)"
                            )
                        }
                        if (result.inserted.isNotEmpty()) {
                            lastSaved = result.inserted.last().first
                        }
                        val ids = result.inserted.map { it.second }
                        withContext(Dispatchers.IO) {
                            database.recordInsertedBatch(
                                sessionId = sid,
                                numbers = result.inserted.map { it.first },
                                rawContactIds = ids,
                                generatedCount = attempted.size.toLong(),
                            )
                        }
                        generated += attempted.size
                        inserted += ids.size
                        failures += result.failed
                    }

                    _state.update { current ->
                        current.copy(
                            operation = progressState(
                                OperationKind.GENERATE,
                                inserted,
                                count.toLong(),
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
                    _state.update { current ->
                        val nextStart = lastSaved?.let { SyrianNumbers.nextAfter(it) }
                        current.copy(
                            stats = stats,
                            operation = OperationState(),
                            startInput = nextStart ?: current.startInput,
                            message = generationMessage(status, inserted, skipped, failures, failure),
                        )
                    }
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
                total = withContext(Dispatchers.IO) { database.countAdded() }
                if (total == 0L) {
                    noData = true
                    return@launch
                }
                _state.update {
                    it.copy(
                        message = "جارٍ حذف الأرقام المضافة...",
                        operation = OperationState(
                            kind = OperationKind.DELETE,
                            total = total,
                        ),
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
                val next = result.lastNumber?.let { SyrianNumbers.nextAfter(it) }
                _state.update { current ->
                    current.copy(
                        stats = stats,
                        operation = OperationState(),
                        startInput = next ?: current.startInput,
                        message = when {
                            result.found == 0L ->
                                "لم يتم العثور على أرقام سابقة تحمل وسم الاستعادة"
                            next != null ->
                                "تمت استعادة ${SyrianNumbers.formatCount(result.found)} رقماً. " +
                                    "آخر رقم: ${result.lastNumber} — تم ضبط البداية على $next."
                            else ->
                                "تمت استعادة ${SyrianNumbers.formatCount(result.found)} رقماً. " +
                                    "آخر رقم: ${result.lastNumber} — لا يوجد رقم تالٍ."
                        },
                    )
                }
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
     * Generates the selected range directly into the SAF document chosen by the
     * user. Nothing is inserted into contacts, so no duplicate scan is needed.
     */
    fun startFileGeneration(uriString: String) {
        val current = _state.value
        if (current.generationMode != GenerationMode.FILE) {
            _state.update { it.copy(message = "اختر وضع الملف أولاً.") }
            return
        }
        val start = SyrianNumbers.normalize(current.startInput)
        if (start == null) {
            _state.update { it.copy(message = "رقم البداية غير صالح. يجب أن يبدأ بـ 091/092/093/094/095/096/098/099") }
            return
        }
        val count = parseCount(current.countInput)
        if (count == null || count <= 0) {
            _state.update { it.copy(message = "أدخل عدداً صحيحاً أكبر من صفر") }
            return
        }
        if (count > FILE_GENERATION_LIMIT) {
            _state.update {
                it.copy(message = "الحد الأقصى ${SyrianNumbers.formatCount(FILE_GENERATION_LIMIT.toLong())} رقم في الملف")
            }
            return
        }
        val available = SyrianNumbers.availableCount(start)
        if (available == null || count > available) {
            _state.update {
                it.copy(
                    message = "العدد المطلوب أكبر من المتاح من رقم البداية. " +
                        "المتاح: ${SyrianNumbers.formatCount((available ?: 0).toLong())} رقم.",
                )
            }
            return
        }
        val uri = parseDocumentUri(uriString)
        if (uri == null) {
            _state.update { it.copy(message = "لم يتم اختيار ملف وجهة صالح.") }
            return
        }
        val format = current.exportFormat
        if (!beginOperation(OperationKind.GENERATE, count.toLong(), indeterminate = false)) return
        OperationService.start(appContext, "جارٍ كتابة ملف ${format.label}...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var sessionId: Long? = null
            var done = 0L
            var recorded = 0L
            var status = "failed"
            var failure: String? = null
            var resultFile: ExportedFile? = null
            var lastNumber: String? = null
            try {
                val activeSessionId = withContext(Dispatchers.IO) {
                    database.createSession(start, count)
                }
                sessionId = activeSessionId
                _state.update {
                    it.copy(
                        message = "جارٍ كتابة ${format.label} إلى الملف المحدد...",
                        operation = OperationState(
                            kind = OperationKind.GENERATE,
                            total = count.toLong(),
                        ),
                    )
                }
                val result = exporter.exportRangeToUri(
                    uri = uri,
                    startLocal = start,
                    count = count,
                    format = format,
                ) { progressed, total ->
                    done = progressed
                    if (progressed - recorded >= FILE_STATS_BATCH) {
                        val delta = progressed - recorded
                        if (runCatching { database.recordFileGenerated(activeSessionId, delta) }.isSuccess) {
                            recorded = progressed
                        }
                    }
                    _state.update {
                        it.copy(
                            operation = progressState(
                                OperationKind.GENERATE,
                                progressed,
                                total,
                                startedAt,
                            ),
                        )
                    }
                }
                done = result.exportedCount
                resultFile = result.file
                lastNumber = result.lastNumber
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
                        if (done > recorded) {
                            runCatching {
                                withContext(Dispatchers.IO) { database.recordFileGenerated(sid, done - recorded) }
                            }
                        }
                        runCatching {
                            withContext(Dispatchers.IO) {
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
                        val nextStart = lastNumber?.let { SyrianNumbers.nextAfter(it) }
                        state.copy(
                            stats = stats,
                            operation = OperationState(),
                            lastExport = resultFile?.let { listOf(it) } ?: state.lastExport,
                            lastExportLabel = resultFile?.let {
                                "${it.format.label} — ${it.displayName}"
                            } ?: state.lastExportLabel,
                            startInput = nextStart ?: state.startInput,
                            message = fileGenerationMessage(status, done, resultFile, failure),
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
        val uri = parseDocumentUri(uriString)
        if (uri == null) {
            _state.update { it.copy(message = "لم يتم اختيار ملف وجهة صالح.") }
            return
        }
        if (!beginOperation(OperationKind.EXPORT, 0, indeterminate = false)) return
        OperationService.start(appContext, "جارٍ تصدير الأرقام المضافة...")
        val operationJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            var total = 0L
            var noData = false
            try {
                total = withContext(Dispatchers.IO) { database.countAdded() }
                if (total == 0L) {
                    noData = true
                    return@launch
                }
                _state.update {
                    it.copy(
                        message = "جارٍ كتابة ${format.label}...",
                        operation = OperationState(
                            kind = OperationKind.EXPORT,
                            total = total,
                        ),
                    )
                }
                val result = exporter.exportAddedToUri(uri, format) { done, count ->
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
                _state.update {
                    it.copy(
                        operation = OperationState(),
                        lastExport = listOf(result.file),
                        lastExportLabel = "${format.label} — ${result.file.displayName}",
                        message = "تم تصدير ${SyrianNumbers.formatCount(result.exportedCount)} رقم إلى الملف المحدد.",
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

    fun shareLastExport() {
        val files = _state.value.lastExport
        if (files.isEmpty()) return
        runCatching { exporter.share(files) }.onFailure { e ->
            _state.update { it.copy(message = "تعذر المشاركة: ${e.message ?: e.javaClass.simpleName}") }
        }
    }

    private fun generationMessage(
        status: String,
        inserted: Long,
        skipped: Long,
        failures: Long,
        failure: String?,
    ): String = buildString {
        when (status) {
            "completed" -> append("تم حفظ ${SyrianNumbers.formatCount(inserted)} رقم في جهات الاتصال")
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
        file: ExportedFile?,
        failure: String?,
    ): String = when (status) {
        "completed" ->
            "تم حفظ ${SyrianNumbers.formatCount(done)} رقماً في ملف ${file?.format?.label ?: ""}" +
                (file?.displayName?.let { " — $it" } ?: "")
        "canceled" ->
            "تم إلغاء التوليد بعد كتابة ${SyrianNumbers.formatCount(done)} رقم. الملف الجزئي محفوظ في المكان الذي اخترته."
        else -> "تعذر إنشاء الملف: ${failure ?: "خطأ غير معروف"}"
    }

    private fun parseDocumentUri(value: String): Uri? {
        val uri = runCatching { value.toUri() }.getOrNull() ?: return null
        return uri.takeIf { !it.scheme.isNullOrBlank() }
    }

    private fun parseCount(input: String): Int? =
        SyrianNumbers.normalizeDigits(input)
            .replace(",", "").replace("،", "").replace(" ", "").trim().toIntOrNull()

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
        private const val FILE_STATS_BATCH = 100_000L
    }
}
