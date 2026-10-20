package com.numx.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            val colors = lightColorScheme(
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
                surfaceContainerLowest = Color.White,
                surfaceContainerLow = Color(0xFFF8FAFC),
                surfaceContainer = Color(0xFFF1F5F9),
                surfaceContainerHigh = Color(0xFFE8EEF5),
                surfaceContainerHighest = Color(0xFFE2E8F0),
            )
            MaterialTheme(colorScheme = colors) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppScreen()
                    }
                }
            }
        }
    }
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

@Composable
fun AppScreen(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var contactsGranted by remember { mutableStateOf(hasContactsPermissions(context)) }
    var notificationsGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var autoRequested by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val pendingAction = remember { mutableStateOf<(() -> Unit)?>(null) }

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

    // Ask once per app launch, immediately, for every permission the app truly
    // needs. The system dialog carries the rationale; the in-app cards below
    // only appear if the user declines or later revokes a permission.
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

    val countValue = SyrianNumbers.normalizeDigits(state.countInput)
        .replace(",", "").replace("،", "").replace(" ", "").trim().toLongOrNull() ?: 0L
    val baselineRate = if (state.stats.lastRatePerSecond > 0) state.stats.lastRatePerSecond else 300.0
    val initialEstimateMs = if (countValue > 0) (countValue / baselineRate * 1000).toLong() else 0L
    val startValid = SyrianNumbers.normalize(state.startInput) != null
    val running = state.operation.isRunning

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
            text = "توليد أرقام خلوية سورية وحفظها في جهات الاتصال",
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
                        "لحفظ الأرقام وحذفها واستعادتها، يحتاج التطبيق إلى صلاحية جهات الاتصال.",
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
                Text("بيانات التوليد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1_000, 10_000, 100_000, 1_000_000).forEach { n ->
                        AssistChip(
                            onClick = { vm.setCount(n) },
                            label = { Text(if (n >= 1_000_000) "1M" else "${n / 1000}K") },
                            enabled = !running,
                        )
                    }
                }
                Text(
                    text = "الوقت المتوقع: ~${SyrianNumbers.formatDuration(initialEstimateMs)} " +
                        "(بناءً على ${String.format(java.util.Locale.US, "%.0f", state.stats.lastRatePerSecond.takeIf { it > 0 } ?: 300.0)} رقم/ثانية)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { ensurePermissions { vm.startGeneration() } },
                modifier = Modifier.weight(1f),
                enabled = !running,
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("توليد وحفظ")
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
                            OperationKind.GENERATE -> "جارٍ التوليد والحفظ"
                            OperationKind.DELETE -> "جارٍ الحذف"
                            OperationKind.EXPORT -> "جارٍ إنشاء الملفات"
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
                            "السرعة: ${String.format(java.util.Locale.US, "%.0f", state.operation.ratePerSecond)} عملية/ثانية",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.operation.kind == OperationKind.GENERATE &&
                        state.operation.skipped > 0
                    ) {
                        Text(
                            "تم تخطي ${SyrianNumbers.formatCount(state.operation.skipped)} رقماً محفوظاً مسبقاً",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
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
                    color = MaterialTheme.colorScheme.secondary,
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
                    color = MaterialTheme.colorScheme.secondary,
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

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ملفات الأرقام", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "ينشئ التطبيق ملف CSV وملف vCard 3.0 قياسيين في مجلد Exports. " +
                        "يمكن التصدير من الأرقام المضافة، أو من نطاق رقم البداية والعدد أعلاه دون إضافته إلى جهات الاتصال.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { vm.exportAll() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running && state.stats.addedCount > 0,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("تصدير الأرقام المضافة")
                }
                OutlinedButton(
                    onClick = { vm.exportRangeFiles() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !running && countValue > 0 && startValid,
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("تصدير نطاق جديد (بدون حفظ في الجهاز)")
                }
                if (state.lastExport.isNotEmpty()) {
                    Text(
                        "آخر تصدير: ${state.lastExportLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    state.lastExport.forEach { file ->
                        Text("• ${file.displayName}", style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { vm.shareLastExport() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("مشاركة الملفات")
                    }
                }
            }
        }

        Text(
            "قبل التوليد يفحص التطبيق الأرقام المحفوظة في جهات الاتصال ويتخطى أي رقم موجود مسبقاً، " +
                "سواء أضافه التطبيق أو أضفته أنت. جهات الاتصال هي العنصر الأبطأ، لذا قد يستغرق المليون رقم وقتاً طويلاً.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            "يمكنك ترك التطبيق أو إغلاق الشاشة أثناء المهام الطويلة؛ تعمل المهمة في خدمة أمامية مع إشعار " +
                "وتستمر عند العودة. لا تستخدم «إيقاف التطبيق قسرياً» أثناء العملية.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
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
}

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}