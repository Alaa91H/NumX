package com.numx.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SystemBarStyle.auto follows the system light/dark setting and updates
        // the status/navigation icon colours when the device configuration
        // changes; the Compose scheme uses the same system preference.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            NumXTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppScreen()
                }
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1),
    onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF475569),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    background = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    error = Color(0xFFB91C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE8EEF5),
    surfaceContainerHighest = Color(0xFFE2E8F0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFFCCFBF1),
    secondary = Color(0xFFCBD5E1),
    onSecondary = Color(0xFF1E293B),
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
    surfaceContainerLowest = Color(0xFF0B1220),
    surfaceContainerLow = Color(0xFF0F172A),
    surfaceContainer = Color(0xFF111C2E),
    surfaceContainerHigh = Color(0xFF1E293B),
    surfaceContainerHighest = Color(0xFF334155),
)

@Composable
private fun NumXTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
    ) {
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            content = content,
        )
    }
}

private enum class AppTab { GENERATE, LOOKUP, EMERGENCY, STATS }

private enum class SingleAction { GENERATE_RANGE, EXPORT_ADDED }

private enum class DirectoryAction { GENERATE_RANGE, EXPORT_ADDED }

private enum class CountryPickerTarget { GENERATE, LOOKUP, EMERGENCY }

private fun tabLabel(tab: AppTab): String = when (tab) {
    AppTab.GENERATE -> "توليد"
    AppTab.LOOKUP -> "بحث"
    AppTab.EMERGENCY -> "طوارئ"
    AppTab.STATS -> "إحصاء"
}

private fun tabIcon(tab: AppTab): ImageVector = when (tab) {
    AppTab.GENERATE -> Icons.Default.Phone
    AppTab.LOOKUP -> Icons.Default.Search
    AppTab.EMERGENCY -> Icons.Default.LocalHospital
    AppTab.STATS -> Icons.Default.BarChart
}

private fun hasContactsPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun missingPermissions(context: Context): Array<String> = buildList {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        add(Manifest.permission.READ_CONTACTS)
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        add(Manifest.permission.WRITE_CONTACTS)
    }
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

private fun parseCountInput(value: String): Long =
    SyrianNumbers.normalizeDigits(value)
        .replace(",", "").replace("،", "").replace(" ", "").trim().toLongOrNull() ?: 0L

private fun ceilDiv(value: Long, divisor: Long): Long =
    if (value <= 0) 0 else (value / divisor) + if (value % divisor == 0L) 0 else 1

private fun emergencyKey(entry: EmergencyEntry): String =
    "${entry.iso2}|${entry.number}|${entry.category}|${entry.labelAr}"

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:${context.packageName}".toUri(),
        )
    )
}

@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var contactsGranted by remember { mutableStateOf(hasContactsPermissions(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(AppTab.GENERATE.name) }
    val tab = AppTab.entries.firstOrNull { it.name == tabName } ?: AppTab.GENERATE

    var pickerTarget by remember { mutableStateOf<CountryPickerTarget?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showFullCountryDialog by remember { mutableStateOf(false) }
    var pendingSingle by remember { mutableStateOf<SingleAction?>(null) }
    var pendingDirectory by remember { mutableStateOf<DirectoryAction?>(null) }
    var selectedEmergency by remember(state.emergencyIso) {
        mutableStateOf<Set<String>>(emptySet())
    }
    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
        val action = pendingSingle
        pendingSingle = null
        if (uri == null) {
            vm.setMessage("لم يتم اختيار مكان الحفظ؛ لم تبدأ العملية.")
        } else {
            when (action) {
                SingleAction.GENERATE_RANGE -> vm.startFileGeneration(uri.toString())
                SingleAction.EXPORT_ADDED -> vm.exportAddedToFile(uri.toString())
                null -> Unit
            }
        }
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val action = pendingDirectory
        pendingDirectory = null
        if (uri == null) {
            vm.setMessage("لم يتم اختيار مجلد الحفظ؛ لم تبدأ العملية.")
        } else {
            when (action) {
                DirectoryAction.GENERATE_RANGE -> vm.startFileGenerationToTree(uri.toString())
                DirectoryAction.EXPORT_ADDED -> vm.exportAddedToDirectory(uri.toString())
                null -> Unit
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        contactsGranted = hasContactsPermissions(context)
        notificationsGranted = hasNotificationPermission(context)
        if (contactsGranted) {
            pendingAction.value?.invoke()
        }
        pendingAction.value = null
    }

    LaunchedEffect(Unit) {
        if (!autoRequested) {
            autoRequested = true
            val missing = missingPermissions(context)
            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
        }
    }

    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                contactsGranted = hasContactsPermissions(context)
                notificationsGranted = hasNotificationPermission(context)
                vm.refreshStorageInfo()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    fun requestPermissionsThen(action: () -> Unit) {
        if (hasContactsPermissions(context)) {
            contactsGranted = true
            action()
            return
        }
        pendingAction.value = action
        val missing = missingPermissions(context)
        if (missing.isEmpty()) {
            vm.setMessage("صلاحية جهات الاتصال غير ممنوحة. افتح إعدادات التطبيق لمنحها.")
        } else {
            permissionLauncher.launch(missing)
        }
    }

    fun chooseSingle(action: SingleAction, prefix: String) {
        pendingSingle = action
        val format = state.exportFormat
        documentLauncher.launch(
            CreateDocumentRequest(
                fileName = ExportManager.suggestedFileName(prefix, format),
                mimeType = format.mimeType,
            )
        )
    }

    fun chooseDirectory(action: DirectoryAction) {
        pendingDirectory = action
        directoryLauncher.launch(null)
    }

    val country = state.selectedCountry
    val running = state.operation.isRunning
    val fileMode = state.generationMode == GenerationMode.FILE
    val format = state.exportFormat
    val countValue = parseCountInput(state.countInput)
    val supported = country != null && country.supports(state.lineType)
    val requiresSplit = ExportManager.requiresSplit(countValue)
    val contactsTooLarge = !fileMode && countValue > CONTACTS_GENERATION_LIMIT
    val countAboveAvailable = state.availableFromStart != null &&
        countValue > (state.availableFromStart ?: 0)
    val addedTotal = state.stats.addedCount + state.stats.emergencyAdded

    fun startChosenMode() {
        if (country == null || !supported) {
            vm.setMessage("هذه الدولة أو نوع الخط غير مدعوم للتوليد.")
            return
        }
        if (!state.startValid) {
            vm.setMessage("رقم البداية غير صالح لهذه الدولة أو النوع. جرّب المثال المعروض.")
            return
        }
        if (countValue <= 0) {
            vm.setMessage("أدخل عدداً صحيحاً أكبر من صفر.")
            return
        }
        if (countAboveAvailable) {
            vm.setMessage(
                "العدد المطلوب أكبر من المتاح من رقم البداية. " +
                    "المتاح: ${SyrianNumbers.formatCount(state.availableFromStart ?: 0)} رقم."
            )
            return
        }
        if (fileMode) {
            if (requiresSplit) {
                vm.setMessage(
                    "الملف الواحد محدود بـ ${SyrianNumbers.formatCount(ExportManager.splitRows)} رقم. " +
                        "استخدم «تصدير مقسم إلى مجلد» للحفاظ على ملفات قابلة للاستخدام."
                )
                return
            }
            chooseSingle(
                SingleAction.GENERATE_RANGE,
                "${country.iso2.lowercase(Locale.US)}_${state.lineType.code.lowercase(Locale.US)}",
            )
        } else {
            if (contactsTooLarge) {
                vm.setMessage(
                    "الحد الأقصى في وضع جهات الاتصال ${SyrianNumbers.formatCount(CONTACTS_GENERATION_LIMIT.toLong())} رقم. " +
                        "للنطاقات الأكبر اختر وضع الملف."
                )
                return
            }
            requestPermissionsThen { vm.startGeneration() }
        }
    }

    fun startSplitMode() {
        if (country == null || !supported) {
            vm.setMessage("هذه الدولة أو نوع الخط غير مدعوم للتوليد.")
            return
        }
        if (!state.startValid) {
            vm.setMessage("رقم البداية غير صالح لهذه الدولة أو النوع. جرّب المثال المعروض.")
            return
        }
        if (countValue <= 0) {
            vm.setMessage("أدخل عدداً صحيحاً أكبر من صفر.")
            return
        }
        if (countAboveAvailable) {
            vm.setMessage(
                "العدد المطلوب أكبر من المتاح من رقم البداية. " +
                    "المتاح: ${SyrianNumbers.formatCount(state.availableFromStart ?: 0)} رقم."
            )
            return
        }
        chooseDirectory(DirectoryAction.GENERATE_RANGE)
    }

    fun exportAdded(single: Boolean) {
        if (addedTotal == 0L) {
            vm.setMessage("لا توجد أرقام مضافة للتصدير.")
            return
        }
        if (single && ExportManager.requiresSplit(addedTotal)) {
            vm.setMessage(
                "عدد الجهات المضافة أكبر من ${SyrianNumbers.formatCount(ExportManager.splitRows)}. " +
                    "استخدم التصدير المقسم إلى مجلد."
            )
            return
        }
        if (single) {
            chooseSingle(SingleAction.EXPORT_ADDED, "contacts")
        } else {
            chooseDirectory(DirectoryAction.EXPORT_ADDED)
        }
    }

    fun addSelectedEmergency() {
        val entries = state.emergencyEntries.filter { emergencyKey(it) in selectedEmergency }
        if (entries.isEmpty()) {
            vm.setMessage("اختر جهة طوارئ واحدة على الأقل.")
            return
        }
        requestPermissionsThen {
            vm.addEmergencyContacts(entries)
            selectedEmergency = emptySet()
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tabName = item.name },
                        icon = { Icon(tabIcon(item), contentDescription = null) },
                        label = { Text(tabLabel(item)) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (running) {
                OperationProgressCard(
                    operation = state.operation,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            if (state.message.isNotBlank()) {
                MessageCard(
                    message = state.message,
                    onDismiss = { vm.setMessage("") },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    AppTab.GENERATE -> GenerateTab(
                        state = state,
                        running = running,
                        countValue = countValue,
                        fileMode = fileMode,
                        format = format,
                        requiresSplit = requiresSplit,
                        contactsTooLarge = contactsTooLarge,
                        countAboveAvailable = countAboveAvailable,
                        contactsGranted = contactsGranted,
                        notificationsGranted = notificationsGranted,
                        onRequestPermissions = {
                            val missing = missingPermissions(context)
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                        },
                        onOpenSettings = { openAppSettings(context) },
                        onPickCountry = { pickerTarget = CountryPickerTarget.GENERATE },
                        onSetLineType = vm::setLineType,
                        onSetMode = vm::setGenerationMode,
                        onSetFormat = vm::setExportFormat,
                        onStartInputChange = vm::updateStart,
                        onCountInputChange = vm::updateCount,
                        onSetCount = vm::setCount,
                        onStart = { startChosenMode() },
                        onSplit = { startSplitMode() },
                        onCancel = vm::cancel,
                        onFullCountry = {
                            if (country != null && supported) {
                                showFullCountryDialog = true
                            } else {
                                vm.setMessage("هذه الدولة أو نوع الخط غير مدعوم للتوليد.")
                            }
                        },
                        onExportAddedSingle = { exportAdded(single = true) },
                        onExportAddedSplit = { exportAdded(single = false) },
                        onShare = vm::shareLastExport,
                        onOpenContacts = { openContactsApp(context) },
                        onAllowCloud = { vm.setCloudStorageAllowed(true) },
                        onDisallowCloud = { vm.setCloudStorageAllowed(false) },
                    )
                    AppTab.LOOKUP -> LookupTab(
                        state = state,
                        onPickCountry = { pickerTarget = CountryPickerTarget.LOOKUP },
                        onInputChange = vm::setLookupInput,
                        onLookup = vm::lookup,
                    )
                    AppTab.EMERGENCY -> EmergencyTab(
                        state = state,
                        running = running,
                        selected = selectedEmergency,
                        onPickCountry = { pickerTarget = CountryPickerTarget.EMERGENCY },
                        onToggle = { key ->
                            selectedEmergency = if (key in selectedEmergency) {
                                selectedEmergency - key
                            } else {
                                selectedEmergency + key
                            }
                        },
                        onSelectAll = {
                            selectedEmergency = state.emergencyEntries
                                .map(::emergencyKey)
                                .toSet()
                        },
                        onClearSelection = { selectedEmergency = emptySet() },
                        onAdd = { addSelectedEmergency() },
                    )
                    AppTab.STATS -> StatsTab(
                        state = state,
                        running = running,
                        addedTotal = addedTotal,
                        contactsGranted = contactsGranted,
                        notificationsGranted = notificationsGranted,
                        onRequestPermissions = {
                            val missing = missingPermissions(context)
                            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
                        },
                        onOpenSettings = { openAppSettings(context) },
                        onRestore = {
                            requestPermissionsThen { vm.restoreFromContacts() }
                        },
                        onDeleteRequest = {
                            if (addedTotal > 0) showDeleteDialog = true
                            else vm.setMessage("لا توجد أرقام مضافة للحذف.")
                        },
                        onResetRequest = { showResetDialog = true },
                        onSetFormat = vm::setExportFormat,
                        onExportAddedSingle = { exportAdded(single = true) },
                        onExportAddedSplit = { exportAdded(single = false) },
                        onShare = vm::shareLastExport,
                    )
                }
            }
        }
    }

    val picker = pickerTarget
    if (picker != null) {
        val selectedIso = when (picker) {
            CountryPickerTarget.GENERATE -> state.selectedIso
            CountryPickerTarget.LOOKUP -> state.lookupIso
            CountryPickerTarget.EMERGENCY -> state.emergencyIso
        }
        CountryPickerDialog(
            title = when (picker) {
                CountryPickerTarget.GENERATE -> "اختيار دولة التوليد"
                CountryPickerTarget.LOOKUP -> "دولة سياق البحث"
                CountryPickerTarget.EMERGENCY -> "دولة أرقام الطوارئ"
            },
            countries = state.countries,
            selectedIso = selectedIso,
            onSelect = { iso ->
                when (picker) {
                    CountryPickerTarget.GENERATE -> vm.selectCountry(iso)
                    CountryPickerTarget.LOOKUP -> vm.setLookupCountry(iso)
                    CountryPickerTarget.EMERGENCY -> vm.setEmergencyCountry(iso)
                }
                pickerTarget = null
            },
            onDismiss = { pickerTarget = null },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("حذف الأرقام المضافة") },
            text = {
                Text(
                    "سيتم حذف ${SyrianNumbers.formatCount(state.stats.addedCount)} جهة اتصال مولّدة " +
                        "و${SyrianNumbers.formatCount(state.stats.emergencyAdded)} جهة طوارئ أضافها التطبيق فقط. " +
                        "لن تُحذف جهات الاتصال الأخرى. هل تريد المتابعة؟"
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteDialog = false
                    requestPermissionsThen { vm.deleteAllAdded() }
                }) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("إلغاء") }
            },
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("تصفير الإحصائيات") },
            text = {
                Text(
                    "سيتم تصفير العدادات والجلسات فقط. الأرقام المضافة إلى جهات الاتصال " +
                        "ستبقى محفوظة ويمكن حذفها لاحقاً."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showResetDialog = false
                    vm.resetStats()
                }) { Text("تصفير") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("إلغاء") }
            },
        )
    }

    if (showFullCountryDialog && country != null) {
        val total = country.countFor(state.lineType)
        val parts = ceilDiv(total, ExportManager.splitRows)
        AlertDialog(
            onDismissRequest = { showFullCountryDialog = false },
            title = { Text("كل أرقام الدولة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${country.nameAr} ${country.flag} — ${state.lineType.labelAr}")
                    Text(
                        "الإجمالي المتاح: ${SyrianNumbers.formatCount(total)} رقم.",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "سيُصدَّر إلى مجلد كأجزاء من ${SyrianNumbers.formatCount(ExportManager.splitRows)} " +
                            "رقم مع manifest.json وchecksums.sha256. عدد الأجزاء المتوقع: ${SyrianNumbers.formatCount(parts)}."
                    )
                    Text(
                        "الحجم التقديري: CSV ~${formatFileSize(ExportFormat.CSV.estimatedBytes(total))}، " +
                            "vCard ~${formatFileSize(ExportFormat.VCARD.estimatedBytes(total))}."
                    )
                    Text(
                        "قد يستغرق هذا النطاق وقتاً طويلاً جداً وقد يستهلك مساحة كبيرة. " +
                            "يمكنك إلغاء العملية في أي وقت، وستبقى الأجزاء المكتوبة محفوظة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.exportFormat == ExportFormat.VCARD && total > 5_000_000) {
                        Text(
                            "الصيغة الحالية vCard. يُوصى باختيار CSV للنطاقات الكبيرة.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFullCountryDialog = false
                        vm.selectFullCountry(state.exportFormat)
                        chooseDirectory(DirectoryAction.GENERATE_RANGE)
                    },
                    enabled = total > 0,
                ) { Text("متابعة إلى المجلد") }
            },
            dismissButton = {
                TextButton(onClick = { showFullCountryDialog = false }) { Text("إلغاء") }
            },
        )
    }
}

@Composable
private fun GenerateTab(
    state: UiState,
    running: Boolean,
    countValue: Long,
    fileMode: Boolean,
    format: ExportFormat,
    requiresSplit: Boolean,
    contactsTooLarge: Boolean,
    countAboveAvailable: Boolean,
    contactsGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickCountry: () -> Unit,
    onSetLineType: (LineType) -> Unit,
    onSetMode: (GenerationMode) -> Unit,
    onSetFormat: (ExportFormat) -> Unit,
    onStartInputChange: (String) -> Unit,
    onCountInputChange: (String) -> Unit,
    onSetCount: (Long) -> Unit,
    onStart: () -> Unit,
    onSplit: () -> Unit,
    onCancel: () -> Unit,
    onFullCountry: () -> Unit,
    onExportAddedSingle: () -> Unit,
    onExportAddedSplit: () -> Unit,
    onShare: () -> Unit,
    onOpenContacts: () -> Unit,
    onAllowCloud: () -> Unit,
    onDisallowCloud: () -> Unit,
) {
    val country = state.selectedCountry
    val type = state.lineType
    val supported = country != null && country.supports(type)
    val canGenerate = supported && !running
    val example = country?.exampleFor(type)?.let { country.toLocalDisplay(it) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "NumX",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "توليد أرقام عالمية — خليوي وأرضي",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "252 دولة وإقليم مع خطط ترقيم دقيقة، وأسماء محايدة N-<E.164>، وتصدير مقسم مع تحقق SHA-256.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PermissionBanner(
            contactsGranted = contactsGranted,
            notificationsGranted = notificationsGranted,
            onRequestPermissions = onRequestPermissions,
            onOpenSettings = onOpenSettings,
        )

        SectionCard("الدولة والنطاق") {
            CountrySelectorCard(
                country = country,
                enabled = !running,
                onClick = onPickCountry,
            )
            if (country == null) {
                Text(
                    "جارٍ تحميل بيانات الدول من الحزمة المدمجة...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            country.nameEn,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "ISO ${country.iso2} • +${country.callingCode} • ${country.support.labelAr}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SupportBadge(country.support)
                }
                if (country.support == SupportTier.UNAVAILABLE) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        )
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "لا تتوفر خطة ترقيم قابلة للتوليد لهذه الدولة.",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "الدولة موجودة في القائمة مع رمزها ورمز اتصالها، لكن البيانات المرجعية " +
                                    "لا تتضمن نطاقات خليوية أو أرضية صالحة. لن يخترع التطبيق أرقاماً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else {
                    Text(
                        "أطوال الأرقام: ${country.lengthsFor(type).joinToString(" / ") { "$it" }} رقم",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "المتاح من البداية: ${SyrianNumbers.formatCount(state.availableFromStart ?: 0)} رقم " +
                            "من إجمالي ${SyrianNumbers.formatCount(country.countFor(type))}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    example?.let {
                        Text(
                            "مثال محلي: $it • دولي: +${country.callingCode}${country.exampleFor(type).orEmpty()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionCard("نوع الخط") {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LineType.entries.forEach { line ->
                    val lineSupported = country?.supports(line) == true
                    FilterChip(
                        selected = type == line,
                        onClick = { onSetLineType(line) },
                        enabled = !running && lineSupported,
                        label = { Text(line.labelAr) },
                    )
                }
            }
            if (country != null && country.hasMobile && country.hasFixed) {
                Text(
                    "«الاثنان» يعني الخليوي + الأرضي فقط، مع حذف الأرقام المشتركة بين الخطتين مرة واحدة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (country != null && !country.hasMobile) {
                Text(
                    "لا تتوفر خطة خليوية لهذه الدولة في البيانات المرجعية.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (country != null && !country.hasFixed) {
                Text(
                    "لا تتوفر خطة أرضية لهذه الدولة في البيانات المرجعية.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("طريقة الإخراج") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOptionCard(
                    title = "جهات الاتصال",
                    subtitle = "توليد وحفظ مباشرة",
                    selected = state.generationMode == GenerationMode.CONTACTS,
                    enabled = !running,
                    onClick = { onSetMode(GenerationMode.CONTACTS) },
                    modifier = Modifier.weight(1f),
                )
                ModeOptionCard(
                    title = "ملف",
                    subtitle = "CSV أو vCard",
                    selected = fileMode,
                    enabled = !running,
                    onClick = { onSetMode(GenerationMode.FILE) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (fileMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip(
                        format = ExportFormat.CSV,
                        selected = format == ExportFormat.CSV,
                        enabled = !running,
                        onClick = { onSetFormat(ExportFormat.CSV) },
                        modifier = Modifier.weight(1f),
                    )
                    FormatChip(
                        format = ExportFormat.VCARD,
                        selected = format == ExportFormat.VCARD,
                        enabled = !running,
                        onClick = { onSetFormat(ExportFormat.VCARD) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "${format.label}: ${format.suitableDevice}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    format.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "الملف الواحد محدود بـ ${SyrianNumbers.formatCount(ExportManager.splitRows)} رقم؛ " +
                        "ما زاد يُكتب إلى مجلد كأجزاء مع manifest.json وchecksums.sha256.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.stats.addedCount + state.stats.emergencyAdded > 0L) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onExportAddedSingle,
                            modifier = Modifier.weight(1f),
                            enabled = !running,
                        ) { Text("تصدير المضاف — ملف") }
                        OutlinedButton(
                            onClick = onExportAddedSplit,
                            modifier = Modifier.weight(1f),
                            enabled = !running,
                        ) { Text("تصدير المضاف — مجلد") }
                    }
                }
            } else {
                StorageNotice(
                    storage = state.storage,
                    cloudAllowed = state.cloudStorageAllowed,
                    enabled = !running,
                    onOpenContacts = onOpenContacts,
                    onAllowCloud = onAllowCloud,
                    onDisallowCloud = onDisallowCloud,
                )
                Text(
                    "قبل الحفظ يفحص التطبيق كل الأرقام الموجودة في جهات الاتصال ويتخطى المكرر، " +
                        "سواء أضافه التطبيق أو أضفته أنت. الأسماء المولّدة محايدة ولا تحتوي اسم دولة أو مشغّل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard("النطاق") {
            OutlinedTextField(
                value = state.startInput,
                onValueChange = onStartInputChange,
                label = { Text("رقم البداية") },
                placeholder = { Text(example ?: "مثال: 0930000000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = canGenerate,
                isError = state.startInput.isNotBlank() && !state.startValid,
            )
            Text(
                "المقبول: رقم محلي، أو NSN، أو +${country?.callingCode ?: "..."}... أو 00${country?.callingCode ?: "..."}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.startInput.isNotBlank() && !state.startValid) {
                Text(
                    "رقم البداية غير صالح لهذه الدولة أو النوع." +
                        (example?.let { " مثال: $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedTextField(
                value = state.countInput,
                onValueChange = onCountInputChange,
                label = { Text("عدد الأرقام المطلوب") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = canGenerate,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L).forEach { value ->
                    AssistChip(
                        onClick = { onSetCount(value) },
                        label = {
                            Text(
                                when {
                                    value >= 1_000_000 -> "${value / 1_000_000}M"
                                    value >= 1_000 -> "${value / 1_000}K"
                                    else -> value.toString()
                                }
                            )
                        },
                        enabled = canGenerate,
                    )
                }
            }
            OutlinedButton(
                onClick = onFullCountry,
                modifier = Modifier.fillMaxWidth(),
                enabled = canGenerate && country != null && country.countFor(type) > 0,
            ) {
                Icon(Icons.Default.Public, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(
                    "كل أرقام الدولة — " +
                        SyrianNumbers.formatCount(country?.countFor(type) ?: 0)
                )
            }
            if (state.availableFromStart != null) {
                Text(
                    "المتاح من رقم البداية: ${SyrianNumbers.formatCount(state.availableFromStart ?: 0)} رقم.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (countAboveAvailable) {
                Text(
                    "العدد أكبر من المتاح؛ سيتوقف التوليد عند آخر رقم متاح.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (contactsTooLarge) {
                Text(
                    "الحد الأقصى في وضع جهات الاتصال " +
                        "${SyrianNumbers.formatCount(CONTACTS_GENERATION_LIMIT.toLong())} رقم. اختر وضع الملف.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (fileMode && requiresSplit) {
                Text(
                    "هذا النطاق يحتاج تصديراً مقسماً إلى مجلد؛ زر الملف الواحد معطّل.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (countValue > 0) {
                Text(
                    "الحجم التقديري: ~${formatFileSize(format.estimatedBytes(countValue))} لصيغة ${format.label}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f),
                enabled = canGenerate && state.startValid && countValue > 0 &&
                    (!fileMode || !requiresSplit) && !contactsTooLarge,
            ) {
                Icon(
                    if (fileMode) Icons.Default.FileDownload else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (fileMode) "ملف واحد" else "توليد وحفظ")
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = running && state.operation.kind != OperationKind.RESTORE,
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("إلغاء")
            }
        }
        if (fileMode) {
            OutlinedButton(
                onClick = onSplit,
                modifier = Modifier.fillMaxWidth(),
                enabled = canGenerate && state.startValid && countValue > 0,
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("تصدير مقسم إلى مجلد (manifest + SHA-256)")
            }
        }

        if (state.lastExport.isNotEmpty()) {
            LastExportCard(
                files = state.lastExport,
                label = state.lastExportLabel,
                onShare = onShare,
            )
        }

        Text(
            "لا يولّد التطبيق أرقام الطوارئ أو المجانية أو المدفوعة أو قصيرة الخدمة. " +
                "وضع جهات الاتصال يفحص الموجود ويتخطى المكرر؛ وضع الملف لا يعدّل جهات الاتصال.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "تعمل المهام الطويلة في خدمة أمامية مع إشعار، وتستمر عند ترك التطبيق أو إغلاق الشاشة. " +
                "لا تستخدم «إيقاف التطبيق قسرياً» أثناء العملية.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LookupTab(
    state: UiState,
    onPickCountry: () -> Unit,
    onInputChange: (String) -> Unit,
    onLookup: () -> Unit,
) {
    val result = state.lookupResult
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "بحث دون اتصال",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "تحقق من رقم، اعرف نوع الخط والدولة والمنطقة والمناطق الزمنية والمشغّل الأصلي من بيانات مدمجة. " +
                "لا يُرسل الرقم إلى أي خادم.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionCard("سياق البحث") {
            CountrySelectorCard(
                country = state.lookupCountry,
                enabled = !state.lookupRunning,
                onClick = onPickCountry,
            )
            Text(
                "اختيار الدولة يساعد على تفسير الأرقام المحلية التي لا تبدأ برمز دولي.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = state.lookupInput,
            onValueChange = onInputChange,
            label = { Text("رقم الهاتف") },
            placeholder = { Text("+963 944 567 890 أو 0944567890") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = !state.lookupRunning,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLookup,
                modifier = Modifier.weight(1f),
                enabled = state.lookupInput.isNotBlank() && !state.lookupRunning,
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("بحث")
            }
            OutlinedButton(
                onClick = { onInputChange("") },
                enabled = state.lookupInput.isNotBlank() && !state.lookupRunning,
            ) { Text("مسح") }
        }
        if (state.lookupRunning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (state.lookupError != null && result == null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                )
            ) {
                Text(
                    state.lookupError,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (result != null) {
            LookupResultCard(result)
        } else if (!state.lookupRunning && state.lookupError == null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Text(
                        "أدخل رقماً ثم اضغط «بحث». تعمل النتيجة بالكامل من البيانات المدمجة في التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "حدود المعلومات",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Bullet("لا يعرض التطبيق اسم المشترك أو عنوانه أو حالة تفعيل الرقم؛ هذه المعلومات غير متاحة دون اتصال.")
                Bullet("المشغّل المعروض هو المشغّل الأصلي حسب البيانات المرجعية، وقد يتغير بعد نقل الرقم (MNP).")
                Bullet("الخط الأرضي لا يُنسب إلى مشغّل غير معروف؛ تظهر «غير متوفر» عند غياب بيانات موثوقة.")
                Bullet("البيانات مدمجة في التطبيق وقد لا تعكس آخر تغييرات الترقيم.")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LookupResultCard(result: LookupResult) {
    val validContainer = if (result.valid) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val validContent = if (result.valid) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    flagForLookup(result.iso2),
                    fontSize = 30.sp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        result.countryAr,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${result.countryEn} • ${result.iso2 ?: "—"} • ${result.callingCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(
                    text = if (result.valid) "صالح" else "غير صالح",
                    container = validContainer,
                    content = validContent,
                )
            }
            HorizontalDivider()
            DetailRow("E.164", result.e164.ifBlank { "—" }, emphasize = true)
            DetailRow("الصيغة الدولية", result.international.ifBlank { "—" })
            DetailRow("الصيغة الوطنية", result.national.ifBlank { "—" })
            DetailRow("نوع الخط", result.lineTypeAr)
            DetailRow("قابل للاتصال وفق الطول", if (result.possible) "نعم" else "لا")
            DetailRow(
                "المنطقة / المدينة",
                result.regionAr ?: result.regionEn ?: "غير متوفر",
            )
            if (result.regionAr != null && result.regionEn != null &&
                result.regionAr != result.regionEn
            ) {
                DetailRow("المنطقة (بالإنجليزية)", result.regionEn)
            }
            DetailRow(
                "المشغّل الأصلي",
                result.carrierAr ?: result.carrierOriginal ?: "غير متوفر",
            )
            if (result.fixedOperatorAr != null || result.fixedOperatorOriginal != null) {
                DetailRow(
                    "مشغّل الخط الأرضي",
                    result.fixedOperatorAr ?: result.fixedOperatorOriginal ?: "غير متوفر",
                )
            }
            DetailRow(
                "المناطق الزمنية",
                if (result.timeZones.isEmpty()) "غير متوفر" else result.timeZones.joinToString("، "),
            )
            if (result.notes.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "ملاحظات",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                result.notes.forEach { note -> Bullet(note) }
            }
            if (result.sources.isNotEmpty()) {
                Text(
                    "المصادر",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                result.sources.forEach { source ->
                    Text(
                        "• $source",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmergencyTab(
    state: UiState,
    running: Boolean,
    selected: Set<String>,
    onPickCountry: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onAdd: () -> Unit,
) {
    val entries = state.emergencyEntries
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "أرقام الطوارئ والخدمات",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            item {
                Text(
                    "اختر الأرقام التي تريد حفظها كجهات اتصال. لا يتصل التطبيق بأي رقم تلقائياً، " +
                        "ولا يحفظ شيئاً قبل ضغط «إضافة المحدد».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                CountrySelectorCard(
                    country = state.emergencyCountry,
                    enabled = !running,
                    onClick = onPickCountry,
                )
            }
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    )
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "بيانات مرجعية — ليست تأكيداً رسمياً",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "كل الإدخالات الحالية مصدرها libphonenumber ShortNumberMetadata 9.0.42 " +
                                "وتحمل مستوى تحقق «مرجعي». لا يصفها التطبيق بأنها رسمية ما لم توجد " +
                                "بيانات رسمية موثقة. تحقق دائماً من الرقم المحلي قبل الاستخدام.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (entries.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        )
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "لا تتوفر بيانات طوارئ لهذه الدولة في النسخة المدمجة.",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                "لن يعرض التطبيق أرقاماً غير مؤكدة أو يخترعها. " +
                                    "إجمالي الإدخالات المتاحة عالمياً: ${SyrianNumbers.formatCount(state.emergencyTotal.toLong())} " +
                                    "لـ ${state.emergencyCountries} دولة/إقليم.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${entries.size} إدخالاً • ${selected.size} محدد",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row {
                            TextButton(
                                onClick = onSelectAll,
                                enabled = !running,
                            ) { Text("تحديد الكل") }
                            TextButton(
                                onClick = onClearSelection,
                                enabled = selected.isNotEmpty() && !running,
                            ) { Text("مسح") }
                        }
                    }
                }
                items(entries, key = { emergencyKey(it) }) { entry ->
                    EmergencyEntryCard(
                        entry = entry,
                        checked = emergencyKey(entry) in selected,
                        enabled = !running,
                        onToggle = { onToggle(emergencyKey(entry)) },
                    )
                }
                item {
                    Text(
                        "أسماء جهات الطوارئ وصفية مثل «الشرطة سوريا — 112» وتُحفظ مع وسم E1 " +
                            "لتمييزها عن الأرقام المولّدة. يمكن حذفها لاحقاً من تبويب الإحصاء.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (entries.isNotEmpty()) {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${selected.size} محدد من ${entries.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "الإضافة تحفظ جهات اتصال فقط",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = onAdd,
                        enabled = selected.isNotEmpty() && !running,
                    ) {
                        Icon(Icons.Default.Person, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("إضافة المحدد")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmergencyEntryCard(
    entry: EmergencyEntry,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                enabled = enabled,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${entry.displayLabelAr} ${entry.countryAr}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        entry.number,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VerificationBadge(entry.verification)
                    Text(
                        entry.labelEn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "المصدر: ${entry.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.checkedAt != null) {
                    Text(
                        "تاريخ التحقق: ${entry.checkedAt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry.noteAr.isNotBlank()) {
                    Text(
                        entry.noteAr,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsTab(
    state: UiState,
    running: Boolean,
    addedTotal: Long,
    contactsGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onRestore: () -> Unit,
    onDeleteRequest: () -> Unit,
    onResetRequest: () -> Unit,
    onSetFormat: (ExportFormat) -> Unit,
    onExportAddedSingle: () -> Unit,
    onExportAddedSplit: () -> Unit,
    onShare: () -> Unit,
) {
    val format = state.exportFormat
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "الإحصائيات والإعدادات",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        PermissionBanner(
            contactsGranted = contactsGranted,
            notificationsGranted = notificationsGranted,
            onRequestPermissions = onRequestPermissions,
            onOpenSettings = onOpenSettings,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("تم توليده", SyrianNumbers.formatCount(state.stats.totalGenerated), Modifier.weight(1f))
            StatBox("مضاف حالياً", SyrianNumbers.formatCount(state.stats.addedCount), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("جهات طوارئ", SyrianNumbers.formatCount(state.stats.emergencyAdded), Modifier.weight(1f))
            StatBox("تم حذفه", SyrianNumbers.formatCount(state.stats.totalDeleted), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("خليوي مولّد", SyrianNumbers.formatCount(state.stats.mobileGenerated), Modifier.weight(1f))
            StatBox("أرضي مولّد", SyrianNumbers.formatCount(state.stats.fixedGenerated), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox("الجلسات", SyrianNumbers.formatCount(state.stats.totalSessions), Modifier.weight(1f))
            StatBox(
                "آخر سرعة",
                String.format(Locale.US, "%.0f/ث", state.stats.lastRatePerSecond),
                Modifier.weight(1f),
            )
        }

        SectionCard("إدارة الأرقام المضافة") {
            Text(
                "يشمل المضاف ${SyrianNumbers.formatCount(state.stats.addedCount)} رقماً مولّداً " +
                    "و${SyrianNumbers.formatCount(state.stats.emergencyAdded)} جهة طوارئ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    enabled = !running,
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("استعادة السجل")
                }
                OutlinedButton(
                    onClick = onDeleteRequest,
                    modifier = Modifier.weight(1f),
                    enabled = !running && addedTotal > 0,
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("حذف المضاف")
                }
            }
            Text(
                "تستعيد العملية سجل الملكية من الوسوم المحايدة N1/N2/E1 في جهات الاتصال، " +
                    "مما يتيح الحذف والمتابعة بعد إعادة التثبيت أو مسح بيانات التطبيق.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onResetRequest,
                enabled = !running,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("تصفير الإحصائيات فقط")
            }
        }

        SectionCard("تصدير الأرقام المضافة") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormatChip(
                    format = ExportFormat.CSV,
                    selected = format == ExportFormat.CSV,
                    enabled = !running,
                    onClick = { onSetFormat(ExportFormat.CSV) },
                    modifier = Modifier.weight(1f),
                )
                FormatChip(
                    format = ExportFormat.VCARD,
                    selected = format == ExportFormat.VCARD,
                    enabled = !running,
                    onClick = { onSetFormat(ExportFormat.VCARD) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "الحجم التقديري: ~${formatFileSize(format.estimatedBytes(addedTotal))}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExportAddedSingle,
                    modifier = Modifier.weight(1f),
                    enabled = !running && addedTotal > 0 &&
                        !ExportManager.requiresSplit(addedTotal),
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("ملف واحد")
                }
                OutlinedButton(
                    onClick = onExportAddedSplit,
                    modifier = Modifier.weight(1f),
                    enabled = !running && addedTotal > 0,
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("مجلد مقسم")
                }
            }
            Text(
                "المجلد المقسم يكتب أجزاء CSV/vCard مع manifest.json وchecksums.sha256.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.lastExport.isNotEmpty()) {
            LastExportCard(
                files = state.lastExport,
                label = state.lastExportLabel,
                onShare = onShare,
            )
        }

        SectionCard("مصادر البيانات") {
            Text(
                "البيانات مدمجة في التطبيق وتعمل دون إنترنت. لا يطلب التطبيق صلاحية الإنترنت.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.sources.forEach { source ->
                HorizontalDivider()
                Text(
                    "${source.name} ${source.version}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    source.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "SHA-256: ${source.sha256.take(24)}…" +
                        (source.license?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "أرقام الطوارئ: ${SyrianNumbers.formatCount(state.emergencyTotal.toLong())} إدخالاً مرجعياً، " +
                    "كلها بمستوى تحقق REFERENCE في هذه النسخة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("سياسة البيانات") {
            Bullet("التغطية: ${state.countries.size} دولة/إقليم مع الاسم العربي والعلم وISO ورمز الاتصال وحالة الدعم.")
            Bullet("الأسماء المولّدة محايدة: N-<E.164> بلا اسم دولة أو مشغّل أو شركة.")
            Bullet("الوسم N2 يحمل REF=N2;E=+...;ISO=..;T=..;P=..;G=..;C=...")
            Bullet("جهات سوريا القديمة N1 تُحفظ كما هي، والبادئات 091/092/093/094/095/096/098/099 مدعومة؛ 090 و097 مرفوضان.")
            Bullet("لا يُدّعى اسم المشترك أو عنوانه أو حالة التفعيل أو المشغّل الحالي بعد نقل الرقم.")
            Bullet("الخط الأرضي المجهول يظهر «غير متوفر» ولا يُنسب إلى مشغّل.")
            Bullet("أرقام الطوارئ استثناء مقصود: أسماء وصفية ووسم E1 وإضافة متعددة الاختيار، دون اتصال أو حفظ تلقائي.")
            Bullet("بيانات الطوارئ تعرض مستوى التحقق والمصدر، ولا تُوصف غير المؤكدة بأنها رسمية.")
        }

        Text(
            "NumX ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — metadata 9.0.42 — " +
                "libphonenumber / geocoder / carrier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "يتابع التطبيق وضع الجهاز الفاتح أو الداكن تلقائياً ويستخدم واجهة عربية من اليمين إلى اليسار.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CountryPickerDialog(
    title: String,
    countries: List<Country>,
    selectedIso: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(countries, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            countries
        } else {
            countries.filter { country ->
                country.nameAr.contains(needle, ignoreCase = true) ||
                    country.nameEn.contains(needle, ignoreCase = true) ||
                    country.iso2.contains(needle, ignoreCase = true) ||
                    country.callingCode.startsWith(needle.removePrefix("+"))
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxSize(0.86f),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${filtered.size} من ${countries.size} دولة/إقليم",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("إغلاق") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("ابحث بالاسم أو ISO أو رمز الاتصال") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(filtered, key = { it.iso2 }) { country ->
                        CountryRow(
                            country = country,
                            selected = country.iso2 == selectedIso,
                            onClick = { onSelect(country.iso2) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryRow(country: Country, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            country.flag.ifBlank { "🌐" },
            fontSize = 26.sp,
        )
        Column(Modifier.weight(1f)) {
            Text(
                country.nameAr,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${country.nameEn} • ${country.iso2} • +${country.callingCode}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            SupportBadge(country.support)
            if (country.support != SupportTier.UNAVAILABLE) {
                Text(
                    "${SyrianNumbers.formatCount(country.mobileCount)} / ${SyrianNumbers.formatCount(country.fixedCount)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CountrySelectorCard(
    country: Country?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(country?.flag ?: "🌐", fontSize = 30.sp)
            Column(Modifier.weight(1f)) {
                Text(
                    country?.nameAr ?: "جارٍ تحميل الدول...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    country?.let { "${it.nameEn} • ${it.iso2} • +${it.callingCode}" }
                        ?: "اختر دولة من القائمة",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = "تغيير الدولة")
        }
    }
}

@Composable
private fun PermissionBanner(
    contactsGranted: Boolean,
    notificationsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!contactsGranted) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            )
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "لحفظ الأرقام في جهات الاتصال أو حذفها واستعادتها، يحتاج التطبيق إلى صلاحية جهات الاتصال. " +
                        "يمكنك التوليد إلى ملف دون هذه الصلاحية.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestPermissions) { Text("منح الصلاحيات") }
                    TextButton(onClick = onOpenSettings) { Text("فتح الإعدادات") }
                }
            }
        }
    } else if (!notificationsGranted) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
        ) {
            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "الإشعارات معطلة. سيعمل التطبيق، لكن قد لا تظهر حالة المهمة في الخلفية.",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                TextButton(onClick = onOpenSettings) { Text("تفعيل الإشعارات من الإعدادات") }
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    }
}

@Composable
private fun OperationProgressCard(operation: OperationState, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (operation.kind) {
                    OperationKind.GENERATE -> "جارٍ التوليد أو الكتابة"
                    OperationKind.DELETE -> "جارٍ الحذف"
                    OperationKind.EXPORT -> "جارٍ التصدير"
                    OperationKind.RESTORE -> "جارٍ استعادة السجل"
                    OperationKind.RESET -> "جارٍ تصفير الإحصائيات"
                    OperationKind.EMERGENCY -> "جارٍ إضافة جهات الطوارئ"
                    OperationKind.IDLE -> ""
                },
                fontWeight = FontWeight.SemiBold,
            )
            if (operation.indeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "تم فحص/تنفيذ ${SyrianNumbers.formatCount(operation.done)} حتى الآن",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(
                    progress = { operation.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${SyrianNumbers.formatCount(operation.done)} / ${SyrianNumbers.formatCount(operation.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${(operation.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "المنقضي: ${SyrianNumbers.formatDuration(operation.elapsedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "المتبقي: ~${SyrianNumbers.formatDuration(operation.etaMs)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (operation.skipped > 0) {
                Text(
                    "تم تخطي ${SyrianNumbers.formatCount(operation.skipped)} رقماً محفوظاً مسبقاً",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LastExportCard(
    files: List<ExportedFile>,
    label: String,
    onShare: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        )
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                "آخر ملف تم إنشاؤه",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (label.isNotBlank()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            files.forEach { file ->
                Text(
                    "• ${file.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            FilledTonalButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("مشاركة الملفات")
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun FormatChip(
    format: ExportFormat,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(format.label, modifier = Modifier.fillMaxWidth()) },
        modifier = modifier,
        enabled = enabled,
    )
}

@Composable
private fun SupportBadge(support: SupportTier) {
    val container = when (support) {
        SupportTier.FULL -> MaterialTheme.colorScheme.primaryContainer
        SupportTier.FIXED_ONLY -> MaterialTheme.colorScheme.secondaryContainer
        SupportTier.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (support) {
        SupportTier.FULL -> MaterialTheme.colorScheme.onPrimaryContainer
        SupportTier.FIXED_ONLY -> MaterialTheme.colorScheme.onSecondaryContainer
        SupportTier.UNAVAILABLE -> MaterialTheme.colorScheme.onErrorContainer
    }
    val label = when (support) {
        SupportTier.FULL -> "مدعوم"
        SupportTier.FIXED_ONLY -> "أرضي فقط"
        SupportTier.UNAVAILABLE -> "غير متاح"
    }
    Surface(
        color = container,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun VerificationBadge(verification: String) {
    val official = verification in setOf("OFFICIAL", "VERIFIED", "CONFIRMED")
    val unverified = verification in setOf("UNVERIFIED", "COMMUNITY", "UNCONFIRMED")
    val container = when {
        official -> MaterialTheme.colorScheme.primaryContainer
        unverified -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        official -> MaterialTheme.colorScheme.onPrimaryContainer
        unverified -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val label = when {
        official -> "موثّق رسمياً"
        unverified -> "غير موثّق"
        else -> "مرجعي — غير مؤكد رسمياً"
    }
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun StatusPill(text: String, container: Color, content: Color) {
    Surface(color = container, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, emphasize: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(0.42f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(0.58f)) {
            Text(
                value,
                style = if (emphasize) {
                    MaterialTheme.typography.titleSmall
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        "• $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StorageNotice(
    storage: ContactStorageInfo,
    cloudAllowed: Boolean,
    enabled: Boolean,
    onOpenContacts: () -> Unit,
    onAllowCloud: () -> Unit,
    onDisallowCloud: () -> Unit,
) {
    when (storage.kind) {
        ContactStorageKind.CLOUD,
        ContactStorageKind.SIM,
        -> {
            val container = if (cloudAllowed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
            val content = if (cloudAllowed) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            }
            Card(colors = CardDefaults.cardColors(containerColor = container)) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "مكان الحفظ: ${storage.displayLabel}",
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                    )
                    if (!cloudAllowed) {
                        Text(
                            "يمنع Android 16+ إنشاء جهات اتصال محلية عندما يكون الحساب الافتراضي سحابياً. " +
                                "غيّر الافتراضي إلى «تخزين الجهاز» من إعدادات جهات الاتصال، " +
                                "أو اسمح بالحفظ في الحساب الحالي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = content,
                        )
                        Text(
                            "المسار: جهات الاتصال ← الإعدادات ← الحساب الافتراضي ← تخزين الجهاز",
                            style = MaterialTheme.typography.bodySmall,
                            color = content,
                        )
                        OutlinedButton(
                            onClick = onOpenContacts,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                        ) { Text("فتح جهات الاتصال") }
                        if (storage.canUseCloudAccount) {
                            TextButton(
                                onClick = onAllowCloud,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = enabled,
                            ) { Text("الحفظ في الحساب الحالي") }
                        } else {
                            Text(
                                "تعذر قراءة بيانات الحساب الافتراضي. استخدم «تخزين الجهاز» من إعدادات جهات الاتصال.",
                                style = MaterialTheme.typography.bodySmall,
                                color = content,
                            )
                        }
                    } else {
                        Text(
                            "ستُحفظ الأرقام في هذا الحساب، وقد تُزامن مع الخدمة السحابية. " +
                                "للأعداد الكبيرة يُفضّل التخزين المحلي.",
                            style = MaterialTheme.typography.bodySmall,
                            color = content,
                        )
                        TextButton(
                            onClick = onDisallowCloud,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = enabled,
                        ) { Text("إلغاء السماح بالحفظ السحابي") }
                    }
                }
            }
        }
        ContactStorageKind.LOCAL,
        ContactStorageKind.NOT_SET,
        -> Text(
            "مكان الحفظ: ذاكرة الجهاز (محلي)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ContactStorageKind.UNKNOWN -> Unit
    }
}

private fun openContactsApp(context: Context) {
    val candidates = listOf(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CONTACTS),
        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
    )
    for (intent in candidates) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun flagForLookup(iso2: String?): String {
    if (iso2 == null || iso2.length != 2) return "🌐"
    val first = iso2[0].uppercaseChar()
    val second = iso2[1].uppercaseChar()
    if (first !in 'A'..'Z' || second !in 'A'..'Z') return "🌐"
    val firstFlag = String(Character.toChars(0x1F1E6 + (first - 'A')))
    val secondFlag = String(Character.toChars(0x1F1E6 + (second - 'A')))
    return firstFlag + secondFlag
}
