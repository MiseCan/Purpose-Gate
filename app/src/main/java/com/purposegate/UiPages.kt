@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.purposegate

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PageHost(target: Page, navigate: (Page) -> Unit, appsParent: Page = Page.PAUSE) {
    when (target) {
        Page.HOME, Page.STATS -> DashboardPage(navigate)
        Page.PAUSE -> ExpressivePausePage(navigate)
        Page.RULES -> RulesPage { navigate(Page.PAUSE) }
        Page.APPS -> AppsPage { navigate(appsParent) }
        Page.SETTINGS -> SettingsPage(navigate, onBack = { navigate(Page.HOME) })
        Page.CHECKS -> ChecksPage { navigate(Page.SETTINGS) }
        Page.APPEARANCE -> OverlaySettingsPage { navigate(Page.SETTINGS) }
        Page.PRESETS -> PresetSettingsPage { navigate(Page.SETTINGS) }
        Page.ABOUT -> AboutPage { navigate(Page.SETTINGS) }
    }
}

@Composable
internal fun SettingsPage(navigate: (Page) -> Unit, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("设置", onBack = onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { SectionHeader("使用偏好", "调整常用选项与悬浮条") }
            item {
                EntryCard(Symbols.PlaylistAdd, "时间与目的预设", "添加、排序和删除快捷选项",
                    onClick = { navigate(Page.PRESETS) })
            }
            item {
                EntryCard(Symbols.Visibility, "悬浮条外观", "大小 · 透明度 · 拖动位置",
                    onClick = { navigate(Page.APPEARANCE) })
            }
            item { SectionHeader("支持与说明", "管理授权，了解应用") }
            item {
                EntryCard(Symbols.VerifiedUser, "管控状态与权限", "检查授权、后台运行与常驻通知",
                    onClick = { navigate(Page.CHECKS) })
            }
            item {
                EntryCard(Symbols.Info, "了解 PurposeGate", "功能规则、隐私与设计参考",
                    onClick = { navigate(Page.ABOUT) })
            }
        }
    }
}

@Composable
internal fun AboutPage(onBack: () -> Unit) {
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("关于", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Surface(shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PauseIllustration(Modifier.fillMaxWidth().height(112.dp),
                            accent = MaterialTheme.colorScheme.surface)
                        Text("PurposeGate", style = MaterialTheme.typography.headlineMedium)
                        Text(ABOUT_INTRO, style = MaterialTheme.typography.bodyLarge)
                        Surface(shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = .6f)) {
                            Text("$ABOUT_VERSION_PREFIX ${BuildConfig.VERSION_NAME}",
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            ABOUT_SECTIONS.forEach { section ->
                item {
                    ChildSection(section.icon, section.title) {
                        section.points.forEach { point ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.padding(top = 7.dp)) {
                                    Symbol(Symbols.Check, size = 16.sp,
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                                Text(point, Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AppsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var usageAccess by remember { mutableStateOf(hasUsageStatsAccess(context)) }
    var reloadKey by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reloadKey++
                usageAccess = hasUsageStatsAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(context, reloadKey) {
        apps = withContext(Dispatchers.IO) { loadInstalledApps(context) }
    }
    var selected by remember { mutableStateOf(PurposeGateStore.selectedPackages(context)) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val allApps = apps.orEmpty()
    val visible = allApps.filter {
        (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) &&
            when (filter) {
                AppFilter.ALL -> true
                AppFilter.SYSTEM -> it.isSystem
                AppFilter.USER -> !it.isSystem
                AppFilter.SELECTED -> it.packageName in selected
            }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("选择应用", onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(value = query, onValueChange = { query = it },
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Symbol(Symbols.Search) },
                trailingIcon = {
                    if (query.isNotEmpty()) ExpressiveIconButton(Symbols.Close, "清除搜索", { query = "" })
                }, singleLine = true, shape = MaterialTheme.shapes.extraLarge,
                colors = childFieldColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            ChildChoiceRow(AppFilter.entries.map { it.label }, filter.ordinal,
                onSelected = { filter = AppFilter.entries[it] },
                modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${visible.size} 个应用", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("已选择 ${selected.size}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (!usageAccess) {
                Box(Modifier.padding(horizontal = 20.dp)) {
                    InfoSurface(Symbols.Insights, "开启使用情况访问后，按最近使用时间排序。") {
                        ExpressiveTonalButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }) { Text("开启") }
                    }
                }
            }
            when {
                apps == null -> Box(Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center) { LoadingIndicator() }
                visible.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                    contentAlignment = Alignment.Center) {
                    IllustratedState(Symbols.Search, "没有匹配的应用",
                        if (query.isBlank()) "换个筛选条件试试。" else "试试其它关键字。")
                }
                else -> LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    itemsIndexed(visible, key = { _, app -> app.packageName },
                        contentType = { _, _ -> "installed-app" }) { index, app ->
                        val checked = app.packageName in selected
                        val color by animateColorAsState(
                            if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerLow, label = "app selection")
                        Surface(shape = ExpressiveGroupShape(index, visible.size), color = color) {
                            Row(Modifier.fillMaxWidth()
                                .toggleable(checked, role = Role.Switch, onValueChange = { enabled ->
                                    selected = selected.setMembership(app.packageName, enabled)
                                    PurposeGateStore.setSelectedPackages(context, selected)
                                }).heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Image(app.icon, null, Modifier.size(44.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.titleMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(if (app.isSystem) "系统应用" else "用户应用",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked, onCheckedChange = null,
                                    thumbContent = if (checked) {
                                        { Symbol(Symbols.Check, size = 16.sp) }
                                    } else null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChecksPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var notificationTitle by rememberSaveable { mutableStateOf(PurposeGateStore.ongoingTitle(context)) }
    var notificationText by rememberSaveable { mutableStateOf(PurposeGateStore.ongoingText(context)) }
    var serviceEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var usageAccess by remember { mutableStateOf(hasUsageStatsAccess(context)) }
    var appCount by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    fun refresh() {
        serviceEnabled = isAccessibilityServiceEnabled(context)
        usageAccess = hasUsageStatsAccess(context)
        appCount = launcherApplications(context).map { it.packageName }
            .distinct().count { it != context.packageName }
    }
    DisposableEffect(lifecycleOwner) {
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("管控状态与权限", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { SectionHeader("运行状态", "从系统设置返回后自动更新") }
            item { StatusCard("无障碍服务",
                if (serviceEnabled) "正在执行所选应用的管控规则" else "应用使用管控需要开启此服务",
                serviceEnabled, "已开启", "未开启") }
            item {
                ExpressiveButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) {
                    Symbol(Symbols.OpenInNew, size = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (serviceEnabled) "管理无障碍服务" else "开启无障碍服务")
                }
            }
            item { StatusCard("设备应用列表", "已读取 $appCount 个有启动入口的应用",
                appCount > 0, "可以读取", "读取失败") }
            item { StatusCard("使用情况访问",
                if (usageAccess) "应用列表按最近使用时间排序" else "应用列表按名称排序",
                usageAccess, "已开启", "未开启") }
            item {
                ExpressiveTonalButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) {
                    Symbol(Symbols.Insights, size = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("管理使用情况访问")
                }
            }
            item {
                ExpressiveOutlinedButton(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Symbol(Symbols.RestartAlt, size = 20.sp)
                    Spacer(Modifier.width(8.dp)); Text("重新检查")
                }
            }
            item {
                ChildSection(Symbols.Notifications, "常驻通知", "服务运行时显示，可自定义文字") {
                    TextField(notificationTitle, { notificationTitle = it.take(80) },
                        label = { Text("通知标题") },
                        supportingText = { Text("留空使用默认标题 · ${notificationTitle.length}/80") },
                        singleLine = true, shape = MaterialTheme.shapes.large,
                        colors = childFieldColors(), modifier = Modifier.fillMaxWidth())
                    TextField(notificationText, { notificationText = it.take(200) },
                        label = { Text("通知正文") },
                        supportingText = { Text("${notificationText.length}/200") },
                        minLines = 2, maxLines = 5, shape = MaterialTheme.shapes.large,
                        colors = childFieldColors(), modifier = Modifier.fillMaxWidth())
                    ExpressiveButton(onClick = {
                        PurposeGateStore.saveOngoing(context, notificationTitle, notificationText)
                        context.sendBroadcast(Intent(PurposeAccessibilityService.ACTION_REFRESH_NOTIFICATION)
                            .setPackage(context.packageName))
                        scope.launch { snackbar.showSnackbar("已保存并更新通知") }
                    }, modifier = Modifier.fillMaxWidth()) { Text("保存并更新通知") }
                    ExpressiveTonalButton(onClick = { requestNotificationPermission(context) },
                        modifier = Modifier.fillMaxWidth()) { Text("管理通知权限") }
                }
            }
            item { SectionHeader("后台运行", "系统授权和电池设置") }
            item {
                EntryCard(Symbols.Shield, "电池优化", "在系统中将 PurposeGate 设为不优化", onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                })
            }
            item {
                EntryCard(Symbols.Settings, "后台活动与自启动", "打开应用详情，检查设备的后台限制", onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }
                })
            }
            item { InfoSurface(Symbols.Info,
                "可在系统中允许后台活动、自启动，并在最近任务中锁定本应用。无障碍授权由系统保存，应用无法替系统重新授权。") }
        }
    }
}

@Composable
internal fun OverlaySettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var alpha by remember { mutableFloatStateOf(PurposeGateStore.overlayAlpha(context)) }
    var scale by remember { mutableFloatStateOf(PurposeGateStore.overlayScale(context)) }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("悬浮窗设置", onBack = onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {

            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("显示预览", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val previewScale = scale
                        Surface(Modifier.widthIn(max = 320.dp).fillMaxWidth()
                            .graphicsLayer(alpha = alpha),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("查资料", style = MaterialTheme.typography.labelLarge,
                                    fontSize = (14 * previewScale).sp)
                                Text("05:00", style = MaterialTheme.typography.headlineMedium,
                                    fontSize = (28 * previewScale).sp, fontFamily = CountdownFontFamily)
                            }
                        }
                        Text("示意内容，不会启动计时", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                SliderCard(Symbols.Opacity, "透明度", "${(alpha * 100).toInt()}%",
                    "调整悬浮条的透明度。", alpha, .4f..1f, 5) {
                    alpha = it; PurposeGateStore.setOverlayAlpha(context, it)
                }
            }
            item {
                SliderCard(Symbols.FormatSize, "大小", "${(scale * 100).toInt()}%",
                    "调整悬浮条的文字和布局大小。", scale, .75f..1.5f, 2) {
                    scale = it; PurposeGateStore.setOverlayScale(context, it)
                }
            }
            item { InfoSurface(Symbols.Visibility,
                "设置自动保存。悬浮条只在已选择的应用上方显示，拖动可以换位置。") }
        }
    }
}

@Composable
internal fun PresetSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var timerPresets by remember { mutableStateOf(PurposeGateStore.timerPresets(context)) }
    var purposePresets by remember { mutableStateOf(PurposeGateStore.purposePresets(context)) }
    var minuteInput by rememberSaveable { mutableStateOf("") }
    var purposeInput by rememberSaveable { mutableStateOf("") }
    fun saveTimers(next: List<Int>) {
        timerPresets = next; PurposeGateStore.setTimerPresets(context, next)
    }
    fun savePurposes(next: List<String>) {
        purposePresets = next; PurposeGateStore.setPurposePresets(context, next)
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surface,
        topBar = { TopBar("预设管理", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item { SectionHeader("定时预设", "按这里的顺序显示在停顿弹窗中") }
            item {
                PresetCard(timerPresets.map { "$it 分钟" }, "还没有定时预设，仍可在弹窗里输入分钟数。",
                    "添加分钟数", "1–9999 分钟，不能与已有预设重复", minuteInput,
                    { minuteInput = it.filter(Char::isDigit).take(4) }, true,
                    minuteInput.toIntOrNull()?.let { it in 1..9999 && it !in timerPresets } == true,
                    onAdd = {
                        val value = minuteInput.toIntOrNull()
                        if (value != null && value in 1..9999 && value !in timerPresets) {
                            saveTimers(timerPresets + value); minuteInput = ""
                            scope.launch { snackbar.showSnackbar("已添加 $value 分钟") }
                        }
                    }, onMove = { from, to -> saveTimers(timerPresets.moved(from, to)) },
                    onDelete = { index -> saveTimers(timerPresets.filterIndexed { i, _ -> i != index }) })
            }
            item { SectionHeader("目的预设", "常用目的可以添加、删除和调整顺序") }
            item {
                PresetCard(purposePresets, "还没有目的预设，仍可在弹窗里输入目的。",
                    "添加目的", "最多 12 个字，不能与已有预设重复", purposeInput,
                    { purposeInput = it.take(12) }, false,
                    purposeInput.trim().isNotEmpty() && purposeInput.trim() !in purposePresets,
                    onAdd = {
                        val value = purposeInput.trim()
                        if (value.isNotEmpty() && value !in purposePresets) {
                            savePurposes(purposePresets + value); purposeInput = ""
                            scope.launch { snackbar.showSnackbar("已添加「$value」") }
                        }
                    }, onMove = { from, to -> savePurposes(purposePresets.moved(from, to)) },
                    onDelete = { index -> savePurposes(purposePresets.filterIndexed { i, _ -> i != index }) })
            }
            item { InfoSurface(Symbols.Info, "调整后自动保存。预设全部删除后，仍可在停顿弹窗里临时输入时长和目的。") }
        }
    }
}

@Composable
private fun PresetCard(
    items: List<String>, emptyHint: String, fieldLabel: String, fieldSupporting: String,
    fieldValue: String, onFieldValueChange: (String) -> Unit, numberOnly: Boolean,
    addEnabled: Boolean, onAdd: () -> Unit, onMove: (Int, Int) -> Unit, onDelete: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        if (items.isEmpty()) InfoSurface(Symbols.PlaylistAdd, emptyHint)
        items.forEachIndexed { index, label ->
            Surface(shape = ExpressiveGroupShape(index, items.size),
                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ExpressiveIconButton(Symbols.KeyboardArrowUp, "上移 $label",
                            { onMove(index, index - 1) }, enabled = index > 0)
                        Spacer(Modifier.width(8.dp))
                        ExpressiveIconButton(Symbols.KeyboardArrowDown, "下移 $label",
                            { onMove(index, index + 1) }, enabled = index < items.lastIndex)
                        Spacer(Modifier.width(16.dp))
                        FilledTonalIconButton(onClick = { onDelete(index) },
                            shapes = IconButtonDefaults.shapes(), modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer)) {
                            Symbol(Symbols.Delete, "删除 $label")
                        }
                    }
                }
            }
        }
        Surface(Modifier.padding(top = 12.dp), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(fieldValue, onFieldValueChange, label = { Text(fieldLabel) },
                    supportingText = { Text(fieldSupporting) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numberOnly) KeyboardType.Number else KeyboardType.Text),
                    shape = MaterialTheme.shapes.large, colors = childFieldColors(),
                    modifier = Modifier.fillMaxWidth())
                ExpressiveTonalButton(onClick = onAdd, enabled = addEnabled,
                    modifier = Modifier.fillMaxWidth()) {
                    Symbol(Symbols.Add, size = 20.sp)
                    Spacer(Modifier.width(8.dp)); Text("添加预设")
                }
            }
        }
    }
}

@Composable
private fun ChildSection(icon: Int, title: String, subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolBadge(icon, size = 40.dp, iconSize = 22.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

/** Connected expressive toggles stay one selection, including when the selected option is tapped. */
@Composable
private fun ChildChoiceRow(options: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, label ->
            ToggleButton(checked = selectedIndex == index, onCheckedChange = { onSelected(index) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                }, modifier = Modifier.heightIn(min = 52.dp).semantics { role = Role.RadioButton }) {
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun childFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent)

private fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
