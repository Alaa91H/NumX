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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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

private enum class FileAction { GENERATE_RANGE, EXPORT_ADDED }

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

@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var contactsGranted by remember { mutableStateOf(hasContactsPermissions(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showFullCountryDialog by remember { mutableStateOf(false) }
    var pendingFileAction by remember { mutableStateOf(FileAction.GENERATE_RANGE) }
    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }

    val documentLauncher = rememberLauncherForActivityResult(CreateDocumentContract()) { uri ->
        if (uri == null) {
            vm.setMessage("لم يتم اختيار مكان الحفظ؛ لم تبدأ العملية.")
        } else {
            when (pendingFileAction) {
                FileAction.GENERATE_RANGE -> vm.startFileGeneration(uri.toString())
                FileAction.EXPORT_ADDED -> vm.exportAddedToFile(uri.toString())
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

    // Ask once per app launch for every permission the contact operations use.
    // File generation itself works without the contacts permissions.
    LaunchedEffect(Unit) {
        if (!autoRequested) {
            autoRequested = true
            val missing = missingPermissions(context)
            if (missing.isNotEmpty()) permissionLauncher.launch(missing)
        }
    }

    // Refresh after returning from the system settings page.
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

    fun ensurePermissions(action: () -> Unit) {
        if (hasContactsPermissions(context)) {
            contactsGranted = true
            action()
            return
        }
        pendingAction.value = action
        val missing = missingPermissions(context)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing)
        }
    }

    fun chooseFile(action: FileAction, prefix: String) {
        pendingFileAction = action
        val format = state.exportFormat
        documentLauncher.launch(
            CreateDocumentRequest(
                fileName = ExportManager.suggestedFileName(prefix, format),
                mimeType = format.mimeType,
            )
        )
    }

    val countValue = SyrianNumbers.normalizeDigits(state.countInput)
        .replace(",", "").replace("،", "").replace(" ", "").trim().toLongOrNull() ?: 0L
    val availableFromStart = SyrianNumbers.availableCount(state.startInput)
    val baselineRate = if (state.stats.lastRatePerSecond > 0) state.stats.lastRatePerSecond else 300.0
    val initialEstimateMs = if (countValue > 0) (countValue / baselineRate * 1000).toLong() else 0L
    val startValid = SyrianNumbers.normalize(state.startInput) != null
    val running = state.operation.isRunning
    val fileMode = state.generationMode == GenerationMode.FILE
    val format = state.exportFormat
    val contactsCountTooLarge = !fileMode && countValue > CONTACTS_GENERATION_LIMIT
    val fileCountTooLarge = fileMode && countValue > FILE_GENERATION_LIMIT
    val countAboveAvailable = availableFromStart != null && countValue > availableFromStart

    fun startChosenMode() {
        if (fileMode) {
            if (!startValid) {
                vm.setMessage("رقم البداية غير صالح. يجب أن يبدأ بـ 091/092/093/094/095/096/098/099")
                return
            }
            if (countValue <= 0) {
                vm.setMessage("أدخل عدداً صحيحاً أكبر من صفر")
                return
            }
            if (countValue > FILE_GENERATION_LIMIT) {
                vm.setMessage(
                    "الحد الأقصى ${SyrianNumbers.formatCount(FILE_GENERATION_LIMIT.toLong())} رقم في الملف"
                )
                return
            }
            if (availableFromStart == null || countValue > availableFromStart) {
                vm.setMessage(
                    "العدد المطلوب أكبر من المتاح من رقم البداية. " +
                        "المتاح: ${SyrianNumbers.formatCount((availableFromStart ?: 0).toLong())} رقم."
                )
                return
            }
            chooseFile(FileAction.GENERATE_RANGE, "range")
        } else {
            ensurePermissions { vm.startGeneration() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
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
            text = "توليد أرقام خلوية سورية صالحة وحفظها حيث تختار",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "الشبكات المسموحة",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.secondary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Syriatel: 093 / 098 / 099",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "MTN: 094 / 095 / 096",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                "Wafa: 091 / 092",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (!contactsGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "لحفظ الأرقام في جهات الاتصال أو حذفها واستعادتها، يحتاج التطبيق إلى صلاحية جهات الاتصال. " +
                            "يمكنك التوليد إلى ملف دون هذه الصلاحية.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ensurePermissions {} }) { Text("منح الصلاحيات") }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    "package:${context.packageName}".toUri(),
                                )
                            )
                        }) { Text("فتح الإعدادات") }
                    }
                }
            }
        } else if (!notificationsGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "الإشعارات معطلة. سيعمل التطبيق، لكن قد لا تظهر حالة المهمة في الخلفية.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri(),
                            )
                        )
                    }) { Text("تفعيل الإشعارات من الإعدادات") }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "إعدادات التوليد",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "اختر طريقة التوليد",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeOptionCard(
                        title = "جهات الاتصال",
                        subtitle = "توليد وحفظ مباشرة",
                        selected = state.generationMode == GenerationMode.CONTACTS,
                        enabled = !running,
                        onClick = { vm.setGenerationMode(GenerationMode.CONTACTS) },
                        modifier = Modifier.weight(1f),
                    )
                    ModeOptionCard(
                        title = "ملف",
                        subtitle = "CSV أو vCard",
                        selected = fileMode,
                        enabled = !running,
                        onClick = { vm.setGenerationMode(GenerationMode.FILE) },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (fileMode) {
                    Text(
                        "اختر صيغة الملف",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FormatChip(
                            format = ExportFormat.CSV,
                            selected = format == ExportFormat.CSV,
                            enabled = !running,
                            onClick = { vm.setExportFormat(ExportFormat.CSV) },
                            modifier = Modifier.weight(1f),
                        )
                        FormatChip(
                            format = ExportFormat.VCARD,
                            selected = format == ExportFormat.VCARD,
                            enabled = !running,
                            onClick = { vm.setExportFormat(ExportFormat.VCARD) },
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
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    "مكان الحفظ: تختاره أنت",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "عند بدء التوليد يفتح منتقي الملفات لتحديد الاسم والمكان " +
                                        "(Downloads أو بطاقة SD أو أي مجلد). لا تُكتب نسخة مؤقتة إضافية.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (state.stats.addedCount > 0) {
                        OutlinedButton(
                            onClick = { chooseFile(FileAction.EXPORT_ADDED, "contacts") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !running,
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("تصدير الأرقام المضافة (${SyrianNumbers.formatCount(state.stats.addedCount)}) إلى ملف")
                        }
                    }
                } else {
                    StorageNotice(
                        storage = state.storage,
                        cloudAllowed = state.cloudStorageAllowed,
                        enabled = !running,
                        onOpenContacts = { openContactsApp(context) },
                        onAllowCloud = { vm.setCloudStorageAllowed(true) },
                        onDisallowCloud = { vm.setCloudStorageAllowed(false) },
                    )
                    Text(
                        "قبل الحفظ يفحص التطبيق كل الأرقام الموجودة في جهات الاتصال ويتخطى المكرر، " +
                            "سواء أضافه التطبيق أو أضفته أنت.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = state.startInput,
                    onValueChange = vm::updateStart,
                    label = { Text("رقم البداية") },
                    placeholder = { Text("مثال: 0930000000") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = !running,
                )
                OutlinedTextField(
                    value = state.countInput,
                    onValueChange = vm::updateCount,
                    label = { Text("عدد الأرقام المطلوب") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !running,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1_000, 10_000, 100_000, 1_000_000).forEach { n ->
                        AssistChip(
                            onClick = { vm.setCount(n) },
                            label = { Text(if (n >= 1_000_000) "1M" else "${n / 1000}K") },
                            enabled = !running,
                        )
                    }
                    if (fileMode) {
                        listOf(5_000_000, 10_000_000).forEach { n ->
                            AssistChip(
                                onClick = { vm.setCount(n) },
                                label = { Text("${n / 1_000_000}M") },
                                enabled = !running,
                            )
                        }
                    }
                }

                if (fileMode) {
                    OutlinedButton(
                        onClick = { showFullCountryDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !running,
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("كل أرقام الدولة — ${SyrianNumbers.formatCount(SyrianNumbers.totalAllocatedNumbers.toLong())}")
                    }
                    Text(
                        "يشمل الخيار جميع الشبكات الثماني ويُكتب في ملف واحد. " +
                            "الحجم التقديري: CSV ~${formatFileSize(ExportFormat.CSV.estimatedBytes(SyrianNumbers.totalAllocatedNumbers.toLong()))}، " +
                            "vCard ~${formatFileSize(ExportFormat.VCARD.estimatedBytes(SyrianNumbers.totalAllocatedNumbers.toLong()))}. " +
                            "لا يمكن حفظ هذا النطاق في جهات الاتصال.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "لأكثر من ${SyrianNumbers.formatCount(CONTACTS_GENERATION_LIMIT.toLong())} رقم استخدم وضع الملف.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (availableFromStart != null) {
                    Text(
                        "المتاح من رقم البداية: ${SyrianNumbers.formatCount(availableFromStart.toLong())} رقم",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contactsCountTooLarge) {
                    Text(
                        "الحد الأقصى في وضع جهات الاتصال ${SyrianNumbers.formatCount(CONTACTS_GENERATION_LIMIT.toLong())} رقم. " +
                            "اختر وضع الملف للنطاقات الأكبر.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (fileCountTooLarge) {
                    Text(
                        "الحد الأقصى في الملف ${SyrianNumbers.formatCount(FILE_GENERATION_LIMIT.toLong())} رقم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (countAboveAvailable) {
                    Text(
                        "العدد أكبر من المتاح من رقم البداية. سيتوقف التوليد عند آخر رقم متاح.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (fileMode) {
                    Text(
                        "الحجم التقديري: ~${formatFileSize(format.estimatedBytes(countValue))} " +
                            "لصيغة ${format.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "الوقت المتوقع: ~${SyrianNumbers.formatDuration(initialEstimateMs)} " +
                            "(بناءً على ${String.format(Locale.US, "%.0f", state.stats.lastRatePerSecond.takeIf { it > 0 } ?: 300.0)} رقم/ثانية)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { startChosenMode() },
                modifier = Modifier.weight(1f),
                enabled = !running && startValid && countValue > 0,
            ) {
                Icon(
                    if (fileMode) Icons.Default.FileDownload else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(if (fileMode) "توليد إلى ملف" else "توليد وحفظ")
            }
            OutlinedButton(
                onClick = { vm.cancel() },
                modifier = Modifier.weight(1f),
                enabled = running && state.operation.kind != OperationKind.RESTORE,
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("إلغاء")
            }
        }

        if (running) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when (state.operation.kind) {
                            OperationKind.GENERATE ->
                                if (fileMode) "جارٍ كتابة الملف" else "جارٍ التوليد والحفظ"
                            OperationKind.DELETE -> "جارٍ الحذف"
                            OperationKind.EXPORT -> "جارٍ إنشاء الملف"
                            OperationKind.RESTORE -> "جارٍ استعادة السجل"
                            OperationKind.RESET -> "جارٍ تصفير الإحصائيات"
                            OperationKind.IDLE -> ""
                        },
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.operation.indeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            when (state.operation.kind) {
                                OperationKind.GENERATE ->
                                    "تم فحص ${SyrianNumbers.formatCount(state.operation.done)} رقم محفوظ حتى الآن"
                                OperationKind.RESTORE ->
                                    "تم فحص ${SyrianNumbers.formatCount(state.operation.done)} سجل حتى الآن"
                                OperationKind.RESET -> "لحظات..."
                                else -> "جارٍ التنفيذ..."
                            }
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { state.operation.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${SyrianNumbers.formatCount(state.operation.done)} / ${SyrianNumbers.formatCount(state.operation.total)}")
                            Text("${(state.operation.progress * 100).toInt()}%")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("المنقضي: ${SyrianNumbers.formatDuration(state.operation.elapsedMs)}")
                            Text("المتبقي: ~${SyrianNumbers.formatDuration(state.operation.etaMs)}")
                        }
                        Text(
                            "السرعة: ${String.format(Locale.US, "%.0f", state.operation.ratePerSecond)} عملية/ثانية",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.operation.kind == OperationKind.GENERATE &&
                        !fileMode &&
                        state.operation.skipped > 0
                    ) {
                        Text(
                            "تم تخطي ${SyrianNumbers.formatCount(state.operation.skipped)} رقماً محفوظاً مسبقاً",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (state.message.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Text(
                    state.message,
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("الإحصائيات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("تم توليده", SyrianNumbers.formatCount(state.stats.totalGenerated), Modifier.weight(1f))
                    StatBox("تم حذفه", SyrianNumbers.formatCount(state.stats.totalDeleted), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatBox("جلسات التوليد", SyrianNumbers.formatCount(state.stats.totalSessions), Modifier.weight(1f))
                    StatBox("مضاف حالياً", SyrianNumbers.formatCount(state.stats.addedCount), Modifier.weight(1f))
                }
                Text(
                    "إجمالي ما تم إدخاله: ${SyrianNumbers.formatCount(state.stats.totalInserted)} رقم",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { ensurePermissions { vm.restoreFromContacts() } },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running,
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("استعادة السجل من جهات الاتصال")
                }
                Text(
                    "يستخدم التطبيق اسمًا قياسيًا ووسمًا محايدًا في جهات الاتصال. " +
                        "بعد إعادة التثبيت أو مسح البيانات، تستعيد هذه العملية الأرقام للحذف أو المتابعة من آخر رقم.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !running && state.stats.addedCount > 0,
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("حذف المضاف")
                    }
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = !running,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("تصفير الإحصائيات")
                    }
                }
            }
        }

        if (state.lastExport.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "آخر ملف تم إنشاؤه",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        state.lastExportLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    state.lastExport.forEach { file ->
                        Text(
                            "• ${file.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Text(
                        "الملف موجود في المكان الذي اخترته. يمكنك مشاركته من هنا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    FilledTonalButton(onClick = { vm.shareLastExport() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("مشاركة الملف")
                    }
                }
            }
        }

        Text(
            "وضع جهات الاتصال يفحص الأرقام المحفوظة مسبقاً ويتخطى المكرر. وضع الملف لا يعدّل جهات الاتصال؛ " +
                "ينشئ ملفاً بالصيغة التي اخترتها فقط، ولا يحتاج إلى فحص التكرار.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "النطاق الكامل يشمل 8 بادئات × 10,000,000 رقم = 80,000,000 رقم. " +
                "استخدم CSV للنطاق الكامل؛ ملف vCard سيكون أكبر بكثير واستيراده غير عملي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "يمكنك ترك التطبيق أو إغلاق الشاشة أثناء المهام الطويلة؛ تعمل المهمة في خدمة أمامية مع إشعار " +
                "وتستمر عند العودة. لا تستخدم «إيقاف التطبيق قسرياً» أثناء العملية.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "يتابع التطبيق وضع الجهاز الفاتح أو الداكن تلقائياً.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("حذف الأرقام المضافة") },
            text = {
                Text("سيتم حذف ${SyrianNumbers.formatCount(state.stats.addedCount)} جهة اتصال أضافها التطبيق فقط. هل تريد المتابعة؟")
            },
            confirmButton = {
                Button(onClick = {
                    showDeleteDialog = false
                    ensurePermissions { vm.deleteAllAdded() }
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
                Text("سيتم تصفير العدادات والجلسات فقط. الأرقام المضافة إلى جهات الاتصال ستبقى محفوظة ويمكن حذفها لاحقاً.")
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

    if (showFullCountryDialog) {
        AlertDialog(
            onDismissRequest = { showFullCountryDialog = false },
            title = { Text("توليد كل أرقام الدولة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "سيتم توليد ${SyrianNumbers.formatCount(SyrianNumbers.totalAllocatedNumbers.toLong())} رقماً " +
                            "من جميع الشبكات الثماني في ملف واحد."
                    )
                    Text(
                        "الحجم التقديري: CSV ~${formatFileSize(ExportFormat.CSV.estimatedBytes(SyrianNumbers.totalAllocatedNumbers.toLong()))}، " +
                            "vCard ~${formatFileSize(ExportFormat.VCARD.estimatedBytes(SyrianNumbers.totalAllocatedNumbers.toLong()))}."
                    )
                    Text("سيُطلب منك تحديد مكان الحفظ. لا تُضاف الأرقام إلى جهات الاتصال.")
                    if (state.exportFormat == ExportFormat.VCARD) {
                        Text(
                            "الصيغة الحالية vCard. يوصى باختيار CSV قبل المتابعة للنطاق الكامل.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showFullCountryDialog = false
                    vm.selectFullCountry(state.exportFormat)
                }) { Text("متابعة") }
            },
            dismissButton = {
                TextButton(onClick = { showFullCountryDialog = false }) { Text("إلغاء") }
            },
        )
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
        // Starting the intent directly avoids a package-visibility query and
        // still falls through to the next candidate when no app handles it.
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
}

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
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
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}
