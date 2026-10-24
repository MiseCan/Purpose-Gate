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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FocusNavigation(selected: Int, onHome: () -> Unit, onFocus: () -> Unit, onStats: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        listOf(Triple("总览", Symbols.PauseCircle, onHome), Triple("专注", Symbols.Tune, onFocus),
            Triple("记录", Symbols.Insights, onStats)).forEachIndexed { i, (title, icon, action) ->
            NavigationBarItem(selected = selected == i, onClick = action,
                icon = { Symbol(icon, contentDescription = null) }, label = { Text(title) })
        }
    }
}

@Composable
internal fun FocusHero(eyebrow: String, title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(eyebrow, Modifier.padding(horizontal = 14.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            }
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(body, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

@Composable
internal fun MetricTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
internal fun ExpressiveHomePage(navigate: (Page) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); refresh++ } }
    val selected = remember(refresh) { PurposeGateStore.selectedPackages(context) }
    val used = remember(refresh) { selected.sumOf { PurposeGateStore.focusUsageMs(context, it) } / 60_000 }
    val pauses = remember(refresh) { PurposeGateStore.focusCount(context, "pauses") }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("PurposeGate", onSettings = { navigate(Page.SETTINGS) }) },
        bottomBar = { FocusNavigation(0, { navigate(Page.HOME) }, { navigate(Page.PAUSE) }, { navigate(Page.STATS) }) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                FocusHero("留一点空间给自己", "先停顿，\n再出发。", "把无意识的打开，变成有目的的选择。") {
                    PauseIllustration(Modifier.fillMaxWidth().height(112.dp), accent = MaterialTheme.colorScheme.surface)
                    ExpressiveButton(onClick = { navigate(Page.APPS) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (selected.isEmpty()) "选择想少用一点的应用" else "管理 ${selected.size} 个守护应用")
                        Spacer(Modifier.width(8.dp)); Symbol(Symbols.ChevronRight)
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("$pauses 次", "今天的停顿", Modifier.weight(1f))
                MetricTile("$used 分钟", "守护应用使用", Modifier.weight(1f))
            } }
        }
    }
}

@Composable
internal fun ExpressivePausePage(navigate: (Page) -> Unit) {
    val context = LocalContext.current
    var modes by remember { mutableStateOf(PurposeGateStore.modes(context)) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("专注") },
        bottomBar = { FocusNavigation(1, { navigate(Page.HOME) }, { navigate(Page.PAUSE) }, { navigate(Page.STATS) }) }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { FocusHero("从一次打开开始", "决定怎么用，\n也决定何时停。", "每个应用都可以有不同的停顿与使用边界。可调整退出后保留时长，期限内返回会恢复计时和目的。") }
            item { EntryCard(Symbols.Apps, "守护应用", "选择需要停顿的应用", { navigate(Page.APPS) }, PurposeGateStore.selectedPackages(context).size) }
            item { EntryCard(Symbols.Shield, "专注规则", "全局默认与各应用的单独设置", { navigate(Page.RULES) }, containerColor = MaterialTheme.colorScheme.tertiaryContainer) }
            item { SectionHeader("打开前，先问自己", "两项都选时合并为一个弹窗；都关闭时只执行额度与冷却") }
            item {
                SwitchRow(Symbols.Timer, "给这次使用一个时长", "到点再决定是否继续", PurposeGateStore.MODE_TIMER in modes) {
                    modes = modes.setMembership(PurposeGateStore.MODE_TIMER, it); PurposeGateStore.setModes(context, modes)
                }
            }
            item {
                SwitchRow(Symbols.Flag, "给这次使用一个目的", "先想清楚，要完成什么", PurposeGateStore.MODE_PURPOSE in modes) {
                    modes = modes.setMembership(PurposeGateStore.MODE_PURPOSE, it); PurposeGateStore.setModes(context, modes)
                }
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
        // Back always dismisses; dragging still provides the half-expanded state.
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
                RuleNumber(Symbols.Schedule, "退出后保留时间", "0=退出即结束；期限内返回恢复计时和目的", "秒", 0..3600,
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
                Text("离开应用时悬浮条立即收起，计时和目的保留设定时间；超时结束会话，期限内返回可恢复。",
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


@Composable
internal fun StatsPage(navigate: (Page) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }; while (true) { delay(2000); revision++ } }
    val selected = remember(revision) { PurposeGateStore.selectedPackages(context) }
    val rows = remember(revision, apps) { apps.filter { it.packageName in selected }.sortedByDescending { PurposeGateStore.focusUsageMs(context, it.packageName) } }
    Scaffold(topBar = { TopBar("今天的记录") },
        bottomBar = { FocusNavigation(2, { navigate(Page.HOME) }, { navigate(Page.PAUSE) }, { navigate(Page.STATS) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { FocusHero("今天 · 本机记录", "${selected.sumOf { PurposeGateStore.focusUsageMs(context, it) } / 60_000} 分钟", "守护应用的实际使用时长。不包含停顿弹窗等待，未开启服务期间不统计。") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile("${PurposeGateStore.focusCount(context, "pauses")}", "停顿次数", Modifier.weight(1f))
                MetricTile("${PurposeGateStore.focusCount(context, "walkaways")}", "主动离开", Modifier.weight(1f))
            } }
            item { SectionHeader("时间去了哪里", "每天本地时间零点重新计数") }
            if (rows.isEmpty()) item { InfoSurface(Symbols.Apps, "还没有守护应用，先到专注页添加。") }
            items(rows, key = { it.packageName }) { app ->
                val used = PurposeGateStore.focusUsageMs(context, app.packageName)
                val budget = PurposeGateStore.focusRule(context, app.packageName).dailyLimitMinutes
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Image(app.icon, null, Modifier.size(36.dp)); Text(app.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${used / 60_000} 分钟", style = MaterialTheme.typography.labelLarge)
                        }
                        if (budget > 0) {
                            LinearProgressIndicator(progress = { (used.toFloat() / (budget * 60_000L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text("每日额度 $budget 分钟", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
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
