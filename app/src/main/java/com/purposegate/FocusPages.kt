@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.purposegate

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val owner = LocalLifecycleOwner.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }
    LaunchedEffect(context, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { apps = withContext(Dispatchers.IO) { loadInstalledApps(context) } }
            while (true) { revision++; delay(2000) }
        }
    }
    val selected = remember(revision) { PurposeGateStore.selectedPackages(context) }
    val usage = remember(revision, selected) {
        selected.associateWith { PurposeGateStore.focusUsageMs(context, it) }
    }
    val budgets = remember(revision, selected) {
        selected.associateWith { PurposeGateStore.focusRule(context, it).dailyLimitMinutes }
    }
    val rows = remember(apps, selected, usage) {
        apps.orEmpty().filter { it.packageName in selected }.sortedByDescending { usage[it.packageName] ?: 0L }
    }
    val pauses = remember(revision) { PurposeGateStore.focusCount(context, "pauses") }
    val walkaways = remember(revision) { PurposeGateStore.focusCount(context, "walkaways") }
    val serviceEnabled = remember(revision) { isAccessibilityServiceEnabled(context) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("看板", onSettings = { navigate(Page.SETTINGS) }) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Box(Modifier.padding(bottom = 12.dp)) {
                    DashboardSummary(usage.values.sum(), serviceEnabled)
                }
            }
            item {
                Row(Modifier.padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardMetric(Symbols.PauseCircle, "$pauses", "今天的停顿", Modifier.weight(1f))
                    DashboardMetric(Symbols.Logout, "$walkaways", "主动离开", Modifier.weight(1f))
                }
            }
            item {
                Box(Modifier.padding(bottom = 8.dp)) {
                    SectionHeader("应用用量", "按今日时长排序 · 点按应用可调整规则")
                }
            }
            when {
                selected.isEmpty() -> item {
                    InfoSurface(Symbols.Apps, "还没有守护应用，可在“专注 → 守护应用”中添加。")
                }
                apps == null -> item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                rows.isEmpty() -> item {
                    InfoSurface(Symbols.Apps, "当前没有可展示的应用，请在专注页检查守护应用。")
                }
                else -> itemsIndexed(rows, key = { _, app -> app.packageName }) { index, app ->
                    DashboardAppUsage(app, usage[app.packageName] ?: 0L,
                        budgets[app.packageName] ?: 0, index, rows.size) { editing = app }
                }
            }
            item {
                Text("本机今日记录 · 每天零点重置。仅统计无障碍服务运行期间的使用，不包含停顿等待。",
                    Modifier.padding(horizontal = 8.dp).padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    editing?.let { app ->
        RuleEditor(app, onDismiss = { editing = null }, onSaved = { editing = null; revision++ })
    }
}

@Composable
private fun DashboardSummary(usedMs: Long, serviceEnabled: Boolean) {
    StatusSummaryCard("今日守护应用使用", dashboardDuration(usedMs), serviceEnabled)
}

@Composable
private fun StatusSummaryCard(label: String, value: String, serviceEnabled: Boolean, supportingText: String? = null) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Surface(shape = CircleShape,
                    color = if (serviceEnabled) MaterialTheme.colorScheme.surface.copy(alpha = .6f)
                        else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (serviceEnabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onErrorContainer) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Symbol(if (serviceEnabled) Symbols.Shield else Symbols.Error, size = 16.sp)
                        Text(if (serviceEnabled) "守护中" else "服务未开启", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(value, style = MaterialTheme.typography.headlineLarge)
            if (supportingText != null) {
                Text(supportingText, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f))
            }
        }
    }
}


@Composable
private fun DashboardMetric(icon: Int, value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(value, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                Symbol(icon, size = 22.sp, tint = MaterialTheme.colorScheme.primary)
            }
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DashboardAppUsage(app: InstalledApp, usedMs: Long, budget: Int,
    index: Int, count: Int, onClick: () -> Unit) {
    val reached = budget > 0 && usedMs >= budget * 60_000L
    val accent = if (reached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(onClick = onClick, shape = ExpressiveGroupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(app.icon, null, Modifier.size(40.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (budget > 0) "每日额度 $budget 分钟" else "未设置每日额度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(dashboardDuration(usedMs), style = MaterialTheme.typography.labelLarge, color = accent)
            }
            if (budget > 0) {
                LinearProgressIndicator(
                    progress = { (usedMs.toFloat() / (budget * 60_000L)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = accent)
                if (reached) Text("已达今日额度", style = MaterialTheme.typography.labelSmall, color = accent)
            }
        }
    }
}

private fun dashboardDuration(usedMs: Long): String {
    val minutes = usedMs / 60_000L
    return when {
        usedMs in 1L until 60_000L -> "不足 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60L == 0L -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }
}


@Composable
internal fun ExpressivePausePage(navigate: (Page) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var modes by remember { mutableStateOf(PurposeGateStore.modes(context)) }
    var selectedCount by remember { mutableIntStateOf(PurposeGateStore.selectedPackages(context).size) }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                modes = PurposeGateStore.modes(context)
                selectedCount = PurposeGateStore.selectedPackages(context).size
                serviceEnabled = isAccessibilityServiceEnabled(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("专注") }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { FocusModeSummary(modes, serviceEnabled) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FocusQuickTile(Symbols.Apps, "守护应用", "选择需要提醒的应用",
                        onClick = { navigate(Page.APPS) }, modifier = Modifier.weight(1f), badgeCount = selectedCount)
                    FocusQuickTile(Symbols.Tune, "专注规则", "全局与单独设置",
                        onClick = { navigate(Page.RULES) }, modifier = Modifier.weight(1f))
                }
            }
            item { SectionHeader("打开前提醒", "两项可同时开启，自动保存") }
            item {
                SwitchRow(Symbols.Timer, "使用时长", "设置时长，到点提醒", PurposeGateStore.MODE_TIMER in modes) {
                    modes = modes.setMembership(PurposeGateStore.MODE_TIMER, it)
                    PurposeGateStore.setModes(context, modes)
                }
            }
            item {
                SwitchRow(Symbols.Flag, "使用目的", "确认目的，再继续使用", PurposeGateStore.MODE_PURPOSE in modes) {
                    modes = modes.setMembership(PurposeGateStore.MODE_PURPOSE, it)
                    PurposeGateStore.setModes(context, modes)
                }
            }
        }
    }
}

@Composable
private fun FocusModeSummary(modes: Set<String>, serviceEnabled: Boolean) {
    val hasTimer = PurposeGateStore.MODE_TIMER in modes
    val hasPurpose = PurposeGateStore.MODE_PURPOSE in modes
    val title = when {
        hasTimer && hasPurpose -> "时长 + 目的"
        hasTimer -> "使用时长"
        hasPurpose -> "使用目的"
        else -> "仅执行规则"
    }
    val description = when {
        hasTimer && hasPurpose -> "打开应用前，设定时长并确认目的。"
        hasTimer -> "为这次使用设定时长，到点再决定是否继续。"
        hasPurpose -> "打开应用前，先确认这次要做什么。"
        else -> "时长和目的提醒已关闭，冷却与每日额度仍生效。"
    }
    StatusSummaryCard("当前提醒方式", title, serviceEnabled, description)
}

@Composable
private fun FocusQuickTile(icon: Int, title: String, subtitle: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, badgeCount: Int? = null) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Symbol(icon, size = 24.sp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                if (badgeCount != null) {
                    Badge { Text("$badgeCount") }
                } else {
                    Symbol(Symbols.ChevronRight, size = 20.sp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
internal fun RulesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }
    var editingGlobal by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(revision) {
        apps = withContext(Dispatchers.IO) {
            loadInstalledApps(context).filter { it.packageName in PurposeGateStore.selectedPackages(context) }
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("专注规则", onBack = onBack) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            item {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    InfoSurface(Symbols.Shield, "每日额度和冷却不能通过“跳过”绕过。没有单独设置的应用会使用全局规则。")
                    EntryCard(Symbols.Tune, "全局规则", "设置所有未单独配置应用的默认规则", onClick = { editingGlobal = true })
                    SectionHeader("按应用设置边界", "可继续使用全局规则，也可为应用单独设置")
                }
            }
            if (apps == null) item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            }
            if (apps?.isEmpty() == true) item {
                IllustratedState(Symbols.Apps, "先选择守护应用", "返回专注页，在“守护应用”中添加。")
            }
            itemsIndexed(apps.orEmpty(), key = { _, app -> app.packageName }) { index, app ->
                val rule = remember(revision, app.packageName) { PurposeGateStore.focusRule(context, app.packageName) }
                val hasOwnRule = remember(revision, app.packageName) { PurposeGateStore.hasFocusRule(context, app.packageName) }
                Surface(
                    onClick = { editing = app },
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                RuleSummary(if (hasOwnRule) "单独设置" else "使用全局")
                                RuleSummary("停顿 ${rule.pauseSeconds} 秒")
                                RuleSummary("退出保留 ${rule.sessionRetentionSeconds} 秒")
                                RuleSummary("冷却 ${rule.cooldownSeconds} 秒")
                                RuleSummary(if (rule.dailyLimitMinutes == 0) "每日不限" else "每日 ${rule.dailyLimitMinutes} 分钟")
                            }
                        }
                        Symbol(Symbols.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    editing?.let { app ->
        RuleEditor(app, onDismiss = { editing = null }, onSaved = { editing = null; revision++ })
    }
    if (editingGlobal) {
        RuleEditor(null, onDismiss = { editingGlobal = false }, onSaved = { editingGlobal = false; revision++ })
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
private fun RuleEditor(app: InstalledApp?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val editorKey = app?.packageName ?: "global"
    val globalRule = remember(editorKey) { PurposeGateStore.globalFocusRule(context) }
    val rule = remember(editorKey) {
        if (app == null) globalRule else PurposeGateStore.focusRule(context, app.packageName)
    }
    var useGlobal by rememberSaveable(editorKey) {
        mutableStateOf(app != null && !PurposeGateStore.hasFocusRule(context, app.packageName))
    }
    var pause by rememberSaveable(editorKey) { mutableStateOf(rule.pauseSeconds.toString()) }
    var retention by rememberSaveable(editorKey) { mutableStateOf(rule.sessionRetentionSeconds.toString()) }
    var cooldown by rememberSaveable(editorKey) { mutableStateOf(rule.cooldownSeconds.toString()) }
    var budget by rememberSaveable(editorKey) { mutableStateOf(rule.dailyLimitMinutes.toString()) }
    var skip by rememberSaveable(editorKey) { mutableStateOf(rule.allowSkip) }
    val editable = app == null || !useGlobal
    val valid = !editable || ((pause.toIntOrNull() ?: -1) in 0..60 &&
        (retention.toIntOrNull() ?: -1) in 0..3600 && (cooldown.toIntOrNull() ?: -1) in 0..3600 &&
        (budget.toIntOrNull() ?: -1) in 0..1440)
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.PartiallyExpanded, SheetValue.Expanded)
    )
    val scope = rememberCoroutineScope()

    fun expandForInput() {
        if (sheetState.targetValue != SheetValue.Expanded) scope.launch { sheetState.expand() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,

        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        TransparentRuleEditorNavigationBar()
        // Restore the original direct dismissal; vertical dragging keeps the native M3 motion.
        BackHandler {
            scope.launch {
                sheetState.hide()
                onDismiss()
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.9f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (app == null) {
                        SymbolBadge(Symbols.Tune, size = 60.dp, iconSize = 30.sp)
                    } else {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Image(app.icon, null, Modifier.padding(10.dp).size(40.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(app?.label ?: "全局规则", style = MaterialTheme.typography.headlineSmall)
                        Text(if (app == null) "未单独设置的应用共用" else "应用规则",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (app != null) item {
                SwitchRow(Symbols.Tune, "使用全局规则", "关闭后可为这个应用单独设置", useGlobal) { useGlobal = it }
            }
            item {
                Text(if (app == null) "保存后，所有未单独设置的应用都会使用这份规则。填 0 可关闭对应限制。"
                    else if (useGlobal) "下方显示当前全局规则。保存后会跟随全局规则的调整。"
                    else "下方设置仅对这个应用生效。填 0 可关闭对应限制。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                RuleNumber(Symbols.PauseCircle, "打开前停顿", "确认和跳过都需等待", "秒", 0..60,
                    if (editable) pause else globalRule.pauseSeconds.toString(), { pause = it },
                    enabled = editable, onFocus = ::expandForInput)
            }
            item {
                RuleNumber(Symbols.Schedule, "退出后保留时间", "适用于确认和跳过；0=退出即结束", "秒", 0..3600,
                    if (editable) retention else globalRule.sessionRetentionSeconds.toString(), { retention = it },
                    enabled = editable, onFocus = ::expandForInput)
            }
            item {
                RuleNumber(Symbols.Hourglass, "离开后的冷却", "实际使用后离开才开始，返回不会重置", "秒", 0..3600,
                    if (editable) cooldown else globalRule.cooldownSeconds.toString(), { cooldown = it },
                    enabled = editable, onFocus = ::expandForInput)
            }
            item {
                RuleNumber(Symbols.Timer, "每日使用额度", "仅统计服务监听到的实际使用", "分钟", 0..1440,
                    if (editable) budget else globalRule.dailyLimitMinutes.toString(), { budget = it }, ImeAction.Done,
                    enabled = editable, onFocus = ::expandForInput)
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
                        if (app != null && useGlobal) {
                            PurposeGateStore.clearFocusRule(context, app.packageName)
                        } else {
                            val next = AppFocusRule(
                                pauseSeconds = pause.toInt(), cooldownSeconds = cooldown.toInt(),
                                dailyLimitMinutes = budget.toInt(), allowSkip = skip, sessionRetentionSeconds = retention.toInt()
                            )
                            if (app == null) PurposeGateStore.setGlobalFocusRule(context, next)
                            else PurposeGateStore.setFocusRule(context, app.packageName, next)
                        }
                        onSaved()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Symbol(Symbols.Check)
                        Spacer(Modifier.width(8.dp))
                        Text(if (app == null) "保存全局规则" else if (useGlobal) "保存并使用全局规则" else "保存应用规则")
                    }
                    ExpressiveTonalButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消") }
                }
            }
        }
    }
}


/** A modal sheet owns a separate window; activity edge-to-edge flags do not affect it. */
@Composable
@Suppress("DEPRECATION")
private fun TransparentRuleEditorNavigationBar() {
    val view = LocalView.current
    val window = remember(view) {
        generateSequence(view.parent) { it.parent }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    }
    DisposableEffect(window) {
        if (window == null) return@DisposableEffect onDispose { }
        val originalColor = window.navigationBarColor
        val originalContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        onDispose {
            window.navigationBarColor = originalColor
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = originalContrast
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
    enabled: Boolean = true,
    onFocus: () -> Unit = {}
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
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() }
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
