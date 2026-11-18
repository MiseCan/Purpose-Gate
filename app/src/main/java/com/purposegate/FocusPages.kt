@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.purposegate

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun FocusNavigation(selected: Int, onDashboard: () -> Unit, onFocus: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        listOf(Triple("看板", Symbols.Insights, onDashboard), Triple("专注", Symbols.Tune, onFocus))
            .forEachIndexed { index, (title, icon, action) ->
                NavigationBarItem(selected = selected == index, onClick = action,
                    icon = { Symbol(icon) }, label = { Text(title) })
            }
    }
}

@Composable
internal fun DashboardPage(navigate: (Page) -> Unit) {
    val context = LocalContext.current
    val model = LocalPurposeGateViewModel.current
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val apps = uiState.apps
    var statsPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = uiState.selected
    val usage = uiState.usage
    val rows = remember(apps, selected, usage) {
        apps.orEmpty().filter { it.packageName in selected }.sortedByDescending { usage[it.packageName] ?: 0L }
    }
    val pauses = uiState.pauses
    val walkaways = uiState.walkaways
    val serviceEnabled = uiState.serviceEnabled
    var celebratingBedtime by remember { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text("看板") },
                actions = {
                    IconButton(onClick = { navigate(Page.SETTINGS) }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                uiState.bedtimeConfirmationDate?.let { date ->
                    item(key = "bedtime_confirmation") {
                        Box(Modifier.padding(bottom = 12.dp)) {
                            BedtimeConfirmationCard {
                                if (model.confirmBedtime(date)) celebratingBedtime = true
                            }
                        }
                    }
                }
                item {
                    Box(Modifier.padding(bottom = 12.dp)) {
                        DashboardSummary(usage.values.sum(), serviceEnabled)
                    }
                }
                item(key = "dashboard_metrics") {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DashboardMetric(Symbols.PauseCircle, "$pauses", "今日停顿", Modifier.weight(1f, fill = false))
                        Text("|", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DashboardMetric(Symbols.Logout, "$walkaways", "主动离开", Modifier.weight(1f, fill = false))
                    }
                }
                item(key = "bedtime_month") {
                    Box(Modifier.padding(bottom = 12.dp)) {
                        BedtimeMonthCard(uiState.bedtimeToday, uiState.bedtimeDays, uiState.bedtimePending, Modifier.fillMaxWidth())
                    }
                }
                item {
                    Box(Modifier.padding(bottom = 8.dp)) {
                        SectionHeader("应用用量", "按今日时长排序 · 点按查看今日统计")
                    }
                }
                when {
                    selected.isEmpty() -> item {
                        InfoSurface(Symbols.Apps, "还没有管控应用，可在“专注 → 管控应用”中添加。")
                    }
                    apps == null -> item {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    }
                    rows.isEmpty() -> item {
                        InfoSurface(Symbols.Apps, "当前没有可展示的应用，请在专注页检查管控应用。")
                    }
                    else -> itemsIndexed(rows, key = { _, app -> app.packageName }) { index, app ->
                        DashboardAppUsage(app, usage[app.packageName] ?: 0L,
                            index, rows.size) { statsPackage = app.packageName }
                    }
                }
                item {
                    Text("本机今日记录 · 每天零点重置。仅统计无障碍服务运行期间的使用，不包含停顿等待。",
                        Modifier.padding(horizontal = 8.dp).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (celebratingBedtime) BedtimeCelebration { celebratingBedtime = false }
        }
    }
    rows.firstOrNull { it.packageName == statsPackage }?.let { app ->
        AppVisitStatsSheet(app, uiState.appVisitStats[app.packageName] ?: AppVisitStats()) {
            statsPackage = null
        }
    }
}

@Composable
private fun AppVisitStatsSheet(app: InstalledApp, stats: AppVisitStats, onDismiss: () -> Unit) {
    PurposeBottomSheet(onDismiss) { close ->
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Image(app.icon, contentDescription = null, modifier = Modifier.padding(10.dp).size(40.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.label, style = MaterialTheme.typography.headlineSmall)
                    Text("今日统计", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("今日打开 ${stats.opens} 次")
                Text("今日累计延长 ${formatUsageDuration(stats.extendedMinutes * 60_000L)}")
            }
        }
        item {
            ExpressiveTonalButton(
                onClick = close,
                modifier = Modifier.fillMaxWidth()
            ) { Text("关闭") }
        }
    }
}

@Composable
private fun DashboardSummary(usedMs: Long, serviceEnabled: Boolean) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("今日管控应用使用", style = MaterialTheme.typography.labelLarge)
        Text(formatUsageDuration(usedMs), style = MaterialTheme.typography.headlineLarge)
        if (serviceEnabled) {
            Text("管控中", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FilledTonalButton(
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                shape = MaterialTheme.shapes.medium
            ) {
                Symbol(Symbols.Shield, size = 18.sp)
                Spacer(Modifier.width(8.dp))
                Text("开启无障碍服务")
            }
        }
    }
}


@Composable
private fun DashboardMetric(
    icon: Int,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Symbol(icon, size = 20.sp, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DashboardAppUsage(app: InstalledApp, usedMs: Long,
    index: Int, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = ExpressiveGroupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(app.icon, null, Modifier.size(40.dp))
            Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(formatUsageDuration(usedMs), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
    }
}


@Composable
internal fun ExpressivePausePage(navigate: (Page) -> Unit) {
    val model = LocalPurposeGateViewModel.current
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val modes = uiState.modes
    val selectedCount = uiState.selected.size
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MediumTopAppBar(
                title = { Text("专注") },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                EntryCard(Symbols.Apps, "管控应用", "选择需要管控的应用",
                    onClick = { navigate(Page.APPS) }, badgeCount = selectedCount)
            }
            item {
                EntryCard(Symbols.Tune, "专注规则", "全局与单独设置",
                    onClick = { navigate(Page.RULES) })
            }
            item { SectionHeader("打开前提醒", "两项可同时开启，自动保存") }
            item {
                SwitchRow(Symbols.Timer, "使用时长", "设置时长，到点提醒", PurposeGateStore.MODE_TIMER in modes) {
                    model.setMode(PurposeGateStore.MODE_TIMER, it)
                }
            }
            item {
                SwitchRow(Symbols.Flag, "使用目的", "确认目的，再继续使用", PurposeGateStore.MODE_PURPOSE in modes) {
                    model.setMode(PurposeGateStore.MODE_PURPOSE, it)
                }
            }
        }
    }
}

@Composable
internal fun RulesPage(onBack: () -> Unit, onEditRule: (String?) -> Unit) {
    val model = LocalPurposeGateViewModel.current
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val apps = uiState.apps?.filter { it.packageName in uiState.selected }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text("专注规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    InfoSurface(Symbols.Shield, "冷却不能通过“跳过”绕过，也不会主动结束会话。未单独设置的应用使用全局规则。")
                    EntryCard(Symbols.Tune, "全局规则", "设置所有未单独配置应用的默认规则", onClick = { onEditRule(null) })
                    SectionHeader("按应用设置边界", "可继续使用全局规则，也可为应用单独设置")
                }
            }
            if (apps == null) item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            if (apps?.isEmpty() == true) item {
                IllustratedState(Symbols.Apps, "先选择管控应用", "返回专注页，在“管控应用”中添加。")
            }
            itemsIndexed(apps.orEmpty(), key = { _, app -> app.packageName }) { index, app ->
                val hasOwnRule = app.packageName in uiState.ownRules
                Surface(
                    onClick = { onEditRule(app.packageName) },
                    shape = ExpressiveGroupShape(index, apps.orEmpty().size),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                            Image(app.icon, null, Modifier.padding(8.dp).size(40.dp))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            if (hasOwnRule) {
                                val rule = uiState.ruleFor(app.packageName)
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    RuleSummary("单独设置")
                                    RuleSummary("确认 ${rule.confirmDelaySeconds} 秒")
                                    RuleSummary("跳过 ${rule.skipDelaySeconds} 秒")
                                    RuleSummary("退出保留 ${rule.sessionRetentionSeconds} 秒")
                                    RuleSummary("冷却 ${rule.cooldownSeconds} 秒")
                                }
                            } else {
                                RuleSummary("使用全局")
                            }
                        }
                        Symbol(Symbols.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleSummary(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun RuleEditorPage(packageName: String?, onBack: () -> Unit) {
    val model = LocalPurposeGateViewModel.current
    val uiState by model.uiState.collectAsStateWithLifecycle()
    val app = uiState.apps?.firstOrNull { it.packageName == packageName }
    val editorKey = packageName ?: "global"
    val globalRule = uiState.globalRule
    val rule = remember(editorKey) {
        if (packageName == null) globalRule else uiState.ruleFor(packageName)
    }
    var useGlobal by rememberSaveable(editorKey) {
        mutableStateOf(packageName != null && packageName !in uiState.ownRules)
    }
    var confirmDelay by rememberSaveable(editorKey) { mutableStateOf(rule.confirmDelaySeconds.toString()) }
    var skipDelay by rememberSaveable(editorKey) { mutableStateOf(rule.skipDelaySeconds.toString()) }
    var retention by rememberSaveable(editorKey) { mutableStateOf(rule.sessionRetentionSeconds.toString()) }
    var cooldown by rememberSaveable(editorKey) { mutableStateOf(rule.cooldownSeconds.toString()) }
    var skip by rememberSaveable(editorKey) { mutableStateOf(rule.allowSkip) }
    val editable = packageName == null || !useGlobal
    val valid = !editable || ((confirmDelay.toIntOrNull() ?: -1) in 0..60 &&
        (skipDelay.toIntOrNull() ?: -1) in 0..60 &&
        (retention.toIntOrNull() ?: -1) in 0..3600 && (cooldown.toIntOrNull() ?: -1) in 0..3600)
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (packageName == null) "全局规则" else app?.label ?: "应用规则",
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (packageName != null) item {
                SwitchRow(Symbols.Tune, "使用全局规则", "关闭后可为这个应用单独设置", useGlobal) { useGlobal = it }
            }
            item {
                Text(if (packageName == null) "保存后，所有未单独设置的应用都会使用这份规则。填 0 可关闭对应限制。"
                    else if (useGlobal) "下方显示当前全局规则。保存后会跟随全局规则的调整。"
                    else "下方设置仅对这个应用生效。填 0 可关闭对应限制。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                RuleNumber(Symbols.PauseCircle, "确认等待", "默认3秒；弹窗出现后开始，0=无需等待", "秒", 0..60,
                    if (editable) confirmDelay else globalRule.confirmDelaySeconds.toString(), { confirmDelay = it },
                    enabled = editable)
            }
            item {
                RuleNumber(Symbols.SkipNext, "跳过等待", "默认6秒；与确认同时开始，0=无需等待", "秒", 0..60,
                    if (editable) skipDelay else globalRule.skipDelaySeconds.toString(), { skipDelay = it },
                    enabled = editable)
            }
            item {
                RuleNumber(Symbols.Schedule, "退出后保留时间", "适用于确认和跳过；0=退出即结束", "秒", 0..3600,
                    if (editable) retention else globalRule.sessionRetentionSeconds.toString(), { retention = it },
                    enabled = editable)
            }
            item {
                RuleNumber(Symbols.Hourglass, "离开后的冷却", "实际使用后离开才开始，返回不会重置", "秒", 0..3600,
                    if (editable) cooldown else globalRule.cooldownSeconds.toString(), { cooldown = it }, ImeAction.Done,
                    enabled = editable)
            }

            item {
                SwitchRow(Symbols.SkipNext, "允许跳过", "关闭后必须确认目的或计时",
                    if (editable) skip else globalRule.allowSkip, enabled = editable) { skip = it }
            }
            item {
                Text("离开后保留计时、目的或跳过状态，期限内返回可恢复；超时后重新弹窗。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExpressiveButton(enabled = valid, onClick = {
                        if (packageName != null && useGlobal) {
                            model.saveRule(packageName, null)
                        } else {
                            val next = AppFocusRule(
                                confirmDelaySeconds = confirmDelay.toInt(), skipDelaySeconds = skipDelay.toInt(),
                                cooldownSeconds = cooldown.toInt(),
                                allowSkip = skip, sessionRetentionSeconds = retention.toInt()
                            )
                            model.saveRule(packageName, next)
                        }
                        onBack()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Symbol(Symbols.Check)
                        Spacer(Modifier.width(8.dp))
                        Text(if (packageName == null) "保存全局规则" else if (useGlobal) "保存并使用全局规则" else "保存应用规则")
                    }
                    ExpressiveTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun PurposeBottomSheet(
    onDismiss: () -> Unit,
    content: LazyListScope.(close: () -> Unit) -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()
    val close: () -> Unit = {
        scope.launch {
            sheetState.hide()
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = null
    ) {
        val window = rememberSheetWindow()
        SheetBackHandler(window, close)
        TransparentSheetNavigationBar(window)
        // Render the Material handle outside the sheet's clickable handle slot.
        BottomSheetDefaults.DragHandle(modifier = Modifier.align(Alignment.CenterHorizontally))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) { content(close) }
    }
}


@Composable
private fun rememberSheetWindow(): android.view.Window? {
    val view = LocalView.current
    return remember(view) {
        generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    }
}

@Composable
private fun SheetBackHandler(window: android.view.Window?, onBack: () -> Unit) {
    val currentOnBack by rememberUpdatedState(onBack)
    if (Build.VERSION.SDK_INT >= 33 && window != null) {
        DisposableEffect(window) {
            val dispatcher = window.onBackInvokedDispatcher
            val callback = OnBackInvokedCallback { currentOnBack() }
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            onDispose { dispatcher.unregisterOnBackInvokedCallback(callback) }
        }
    } else {
        val owner = LocalView.current.findViewTreeOnBackPressedDispatcherOwner()
        if (owner == null) {
            BackHandler(onBack = onBack)
        } else {
            CompositionLocalProvider(LocalOnBackPressedDispatcherOwner provides owner) {
                BackHandler(onBack = onBack)
            }
        }
    }
}

/** A modal sheet owns a separate window; activity edge-to-edge flags do not affect it. */
@Composable
@Suppress("DEPRECATION")
private fun TransparentSheetNavigationBar(window: android.view.Window?) {
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val originalColor = window.navigationBarColor
        val originalContrast = window.isNavigationBarContrastEnforced
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        onDispose {
            window.navigationBarColor = originalColor
            window.isNavigationBarContrastEnforced = originalContrast
        }
    }
}

@Composable
private fun RuleNumber(
    codePoint: Int,
    label: String,
    hint: String,
    unit: String,
    range: IntRange,
    value: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true
) {
    val isError = value.isNotEmpty() && (value.toIntOrNull() ?: -1) !in range
    TextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(4)) },
        label = { Text(label) },
        leadingIcon = { Symbol(codePoint) },
        suffix = { Text(unit) },
        supportingText = {
            Text(if (isError) "请输入 ${range.first}–${range.last} $unit" else "${range.first}–${range.last} $unit · $hint")
        },
        isError = isError,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}


/** A quiet, spring-like breath motif. No raster assets or fixed colors. */
@Composable
internal fun PauseBreath(seconds: Int) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(0.82f, 1f,
        infiniteRepeatable(tween(2500), RepeatMode.Reverse), label = "breath size")
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(132.dp).scale(scale)) { drawCircle(color.copy(alpha = .1f)); drawCircle(color.copy(alpha = .45f), style = Stroke(3.dp.toPx())) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (seconds > 0) "$seconds" else "准备好了", style = if (seconds > 0) MaterialTheme.typography.displayMedium else MaterialTheme.typography.titleLarge)
            Text("慢一点，呼吸一下", style = MaterialTheme.typography.labelMedium)
        }
    }
}
