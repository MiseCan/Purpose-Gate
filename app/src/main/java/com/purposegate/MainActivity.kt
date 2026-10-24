@file:OptIn(ExperimentalMaterial3Api::class)

package com.purposegate

/** 应用入口与页面导航（页面切换动画、预见式返回也在这里）。 */

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PurposeGateTheme { PurposeGateApp() } }
    }
}



















@Composable
internal fun PurposeGateApp() {
    var page by rememberSaveable { mutableStateOf(Page.HOME) }
    // 返回手势进行时要显示的上一级页面（底层），以及当前页的跟手进度。
    var backTarget by remember { mutableStateOf<Page?>(null) }
    val backProgress = remember { Animatable(0f) }
    val settleScope = rememberCoroutineScope()
    var instantSwap by remember { mutableStateOf(false) }

    val navigate: (Page) -> Unit = { target ->
        if (target != page) {
            instantSwap = false
            page = target
        }
    }

    // 全局预见式返回：手势进行时当前页跟手缩小变淡，上一级在底层已经就位；
    // 松手后当前页继续淡出，然后瞬时切页，不再跑第二遍过渡动画（否则两层会同时闪）。
    // Android 13 以下是普通返回，导航结果一致。
    val parentPage = page.parent()
    // Root tabs delegate Back to Android; they never navigate to another tab.
    PredictiveBackHandler(enabled = parentPage != null) { progress ->
        val target = parentPage ?: return@PredictiveBackHandler
        try {
            progress.collect { event ->
                if (backTarget == null) backTarget = target
                backProgress.snapTo(event.progress)
            }
            backProgress.animateTo(1f, tween(150))
            instantSwap = true
            page = target
            backTarget = null
            backProgress.snapTo(0f)
        } catch (cancellation: CancellationException) {
            // 手势取消：当前页滑回原位。用外部作用域，避免跟着手势协程一起被取消。
            settleScope.launch {
                backProgress.animateTo(0f, tween(150))
                backTarget = null
            }
            throw cancellation
        }
    }

    LaunchedEffect(page, instantSwap) {
        if (instantSwap) instantSwap = false
    }

    val progress = backProgress.value
    Box(Modifier.fillMaxSize()) {
        // 底层：上一级页面，手势期间先显示出来
        backTarget?.let { target -> PageHost(target, navigate) }
        // 上层：当前页面，按手势进度缩小变淡
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1f - 0.06f * progress
                    scaleY = 1f - 0.06f * progress
                    alpha = 1f - progress
                }
        ) {
            // 统一的一套页面动画：淡入 + 缩放展开；手势返回时瞬时切换，不叠加动画。
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (instantSwap) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                            .togetherWith(fadeOut(tween(180)) + scaleOut(targetScale = 0.96f, animationSpec = tween(180)))
                    }
                },
                label = "page transition"
            ) { target -> PageHost(target, navigate) }
        }
    }
}
